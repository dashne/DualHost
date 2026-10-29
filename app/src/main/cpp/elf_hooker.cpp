#include <link.h>
#include <string.h>
#include <stdio.h>
#include <stdint.h>
#include <unistd.h>
#include <sys/mman.h>
#include <android/log.h>
#include <errno.h>
#include <mutex>
#include "HookRegistry.h"

#define LOG_TAG "DualCore"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static std::mutex g_got_mutex;

struct FindSymbolData {
    const char *TargetLibName;
    const char *TargetSymbolName;
    void **GotAddress;
};

// Adopted from HookEngine's elf_hooker.cpp: validates ELF class/machine
// before scanning, restricts matches to actual GOT/PLT relocation types
// (R_AARCH64_JUMP_SLOT / R_AARCH64_GLOB_DAT), and rejects data symbols
// (STT_OBJECT/STT_TLS). DualHost's original version matched the symbol
// name alone against every relocation entry in the library, which could
// in principle match a non-function/non-GOT relocation of the same name.
static const ElfW(Ehdr)* FindEhdr(struct dl_phdr_info* info) {
    for (int i = 0; i < info->dlpi_phnum; i++) {
        if (info->dlpi_phdr[i].p_type == PT_LOAD && info->dlpi_phdr[i].p_offset == 0) {
            return (const ElfW(Ehdr)*)(info->dlpi_addr + info->dlpi_phdr[i].p_vaddr);
        }
    }
    return nullptr;
}

static bool IsGotRelocation(unsigned long r_info) {
    uint32_t type = ELF64_R_TYPE(r_info);
    return type == 1025u /* R_AARCH64_GLOB_DAT */ || type == 1026u /* R_AARCH64_JUMP_SLOT */;
}

static int FindSymbolCallback(struct dl_phdr_info *info, size_t, void *data) {
    auto *fd = (FindSymbolData *) data;
    if (!info->dlpi_name || !strstr(info->dlpi_name, fd->TargetLibName)) return 0;

    const ElfW(Ehdr)* ehdr = FindEhdr(info);
    if (!ehdr) return 0;
    if (ehdr->e_ident[EI_CLASS] != ELFCLASS64) return 0;
    if (ehdr->e_machine != EM_AARCH64) return 0;

    ElfW(Dyn) *dyn = nullptr;
    for (int i = 0; i < info->dlpi_phnum; i++) {
        if (info->dlpi_phdr[i].p_type == PT_DYNAMIC) {
            dyn = (ElfW(Dyn) *)(info->dlpi_addr + info->dlpi_phdr[i].p_vaddr);
            break;
        }
    }
    if (!dyn) return 0;

    const char *strtab = nullptr;
    ElfW(Sym) *symtab = nullptr;
    size_t strsz = 0;
    ElfW(Rela) *jmprel = nullptr;
    size_t pltrelsz = 0;
    ElfW(Rela) *relaDyn = nullptr;
    size_t relaDynSz = 0;

    for (ElfW(Dyn) *d = dyn; d->d_tag != DT_NULL; d++) {
        switch (d->d_tag) {
            case DT_STRTAB:   strtab    = (const char *)(info->dlpi_addr + d->d_un.d_ptr); break;
            case DT_SYMTAB:   symtab    = (ElfW(Sym) *)(info->dlpi_addr + d->d_un.d_ptr); break;
            case DT_STRSZ:    strsz     = d->d_un.d_val; break;
            case DT_JMPREL:   jmprel    = (ElfW(Rela) *)(info->dlpi_addr + d->d_un.d_ptr); break;
            case DT_PLTRELSZ: pltrelsz  = d->d_un.d_val; break;
            case DT_RELA:     relaDyn   = (ElfW(Rela) *)(info->dlpi_addr + d->d_un.d_ptr); break;
            case DT_RELASZ:   relaDynSz = d->d_un.d_val; break;
        }
    }
    if (!strtab || !symtab || strsz == 0) return 0;

    auto scan = [&](ElfW(Rela) *arr, size_t sz) -> bool {
        if (!arr) return false;
        size_t count = sz / sizeof(ElfW(Rela));
        for (size_t i = 0; i < count; i++) {
            ElfW(Rela) *rel = &arr[i];
            if (!IsGotRelocation(rel->r_info)) continue;
            size_t symIdx = ELF64_R_SYM(rel->r_info);
            if (symIdx == 0) continue;
            ElfW(Sym) *sym = &symtab[symIdx];
            if (sym->st_name == 0 || sym->st_name >= strsz) continue;
            int st_type = ELF64_ST_TYPE(sym->st_info);
            if (st_type == STT_OBJECT || st_type == STT_TLS) continue;
            const char *name = strtab + sym->st_name;
            if (name && strcmp(name, fd->TargetSymbolName) == 0) {
                fd->GotAddress = (void **)(info->dlpi_addr + rel->r_offset);
                return true;
            }
        }
        return false;
    };

    if (scan(jmprel, pltrelsz)) return 1;
    if (scan(relaDyn, relaDynSz)) return 1;
    return 0;
}

extern "C" void **GetGotAddress(const char *libName, const char *symbolName) {
    if (!libName || !symbolName) return nullptr;
    FindSymbolData data{};
    data.TargetLibName = libName;
    data.TargetSymbolName = symbolName;
    data.GotAddress = nullptr;
    dl_iterate_phdr(FindSymbolCallback, &data);
    return data.GotAddress;
}

// Adopted from HookEngine: reads the GOT page's ACTUAL protection from
// /proc/self/maps before mprotect, instead of assuming PROT_READ (what
// DualHost's original version did). Restoring to an assumed PROT_READ
// instead of the page's real original protection could leave a page
// wrong (e.g. too permissive, or too restrictive for something else that
// legitimately shares the page) after our hook touches it.
static bool ReadPageProt(uintptr_t page, int *outProt) {
    FILE *f = fopen("/proc/self/maps", "r");
    if (!f) return false;
    char line[512];
    bool ok = false;
    while (fgets(line, sizeof(line), f)) {
        unsigned long s, e;
        char perms[8] = {0};
        if (sscanf(line, "%lx-%lx %7s", &s, &e, perms) == 3) {
            if (s <= page && page < e) {
                int p = 0;
                if (perms[0] == 'r') p |= PROT_READ;
                if (perms[1] == 'w') p |= PROT_WRITE;
                if (perms[2] == 'x') p |= PROT_EXEC;
                *outProt = p;
                ok = true;
                break;
            }
        }
    }
    fclose(f);
    return ok;
}

extern "C" HookResult HookGotEntryEx(void **gotAddress, void *newFunction,
                                     void **originalFunction,
                                     const char *libName, const char *symName,
                                     HookHandle **outHandle) {
    if (!gotAddress || !newFunction) return HOOK_INTERNAL_ERROR;
    std::lock_guard<std::mutex> lock(g_got_mutex);

    if (*gotAddress == newFunction) return HOOK_ALREADY_HOOKED;
    HookHandle *existing = FindHandle((void*)gotAddress);
    if (existing) {
        if (existing->replacement != newFunction) return HOOK_INTERNAL_ERROR;
        return HOOK_ALREADY_HOOKED;
    }

    long pageSize = sysconf(_SC_PAGESIZE);
    uintptr_t pageStart = (uintptr_t)gotAddress & ~(pageSize - 1);

    int origProt = PROT_READ;
    if (!ReadPageProt(pageStart, &origProt)) {
        LOGE("elf_hooker: cannot read prot for %p", (void*)pageStart);
        return HOOK_INTERNAL_ERROR;
    }

    if (mprotect((void *)pageStart, pageSize, origProt | PROT_WRITE) != 0) {
        LOGE("elf_hooker: mprotect RW failed on %p errno=%d", gotAddress, errno);
        return HOOK_MPROTECT_FAILED;
    }

    void *saved = *gotAddress;
    *gotAddress = newFunction;
    __builtin___clear_cache((char*)pageStart, (char*)(pageStart + pageSize));

    if (mprotect((void *)pageStart, pageSize, origProt) != 0) {
        LOGE("elf_hooker: restore mprotect failed on %p errno=%d — rolling back", gotAddress, errno);
        mprotect((void *)pageStart, pageSize, origProt | PROT_WRITE);
        *gotAddress = saved;
        __builtin___clear_cache((char*)pageStart, (char*)(pageStart + pageSize));
        mprotect((void *)pageStart, pageSize, origProt);
        return HOOK_MPROTECT_FAILED;
    }

    HookHandle *h = RegistryAlloc();
    if (!h) {
        LOGD("elf_hooker: HookRegistry full — rolling back hook on %p", gotAddress);
        mprotect((void *)pageStart, pageSize, origProt | PROT_WRITE);
        *gotAddress = saved;
        __builtin___clear_cache((char*)pageStart, (char*)(pageStart + pageSize));
        mprotect((void *)pageStart, pageSize, origProt);
        return HOOK_INTERNAL_ERROR;
    }
    h->type = HT_GOT;
    h->target = gotAddress;
    h->replacement = newFunction;
    h->saved = saved;
    h->orig_prot = origProt;
    h->page_size = pageSize;
    h->state = HS_INSTALLED;
    if (libName && symName) snprintf(h->name, sizeof(h->name), "%s in %s", symName, libName);

    if (originalFunction && !*originalFunction) *originalFunction = saved;
    if (outHandle) *outHandle = h;
    return HOOK_OK;
}

// Legacy entry point — keeps every existing call site in Hooks.cpp
// (HookGotEntry(gotAddress, newFunction, &_Original_x)) source-compatible.
// Internally routed through HookGotEntryEx so every GOT hook now gets real
// page-protection handling and a HookRegistry accounting entry.
extern "C" bool HookGotEntry(void **gotAddress, void *newFunction, void **originalFunction) {
    HookResult r = HookGotEntryEx(gotAddress, newFunction, originalFunction, nullptr, nullptr, nullptr);
    return r == HOOK_OK || r == HOOK_ALREADY_HOOKED;
}

// New — not wired into any call site yet (DualHost never uninstalls a GOT
// hook today), but HookRegistry's accounting now has a real counterpart
// rather than being fire-and-forget. Available for a future slot-teardown
// path.
extern "C" HookResult UnhookGotEntry(void *slotAddress) {
    std::lock_guard<std::mutex> lock(g_got_mutex);
    HookHandle *h = FindHandle(slotAddress);
    if (!h || h->type != HT_GOT) return HOOK_NOT_HOOKED;
    h->state = HS_REMOVING;

    uintptr_t pageStart = (uintptr_t)slotAddress & ~(h->page_size - 1);
    if (mprotect((void*)pageStart, h->page_size, h->orig_prot | PROT_WRITE) != 0) {
        h->state = HS_INSTALLED;
        return HOOK_MPROTECT_FAILED;
    }
    *(void**)slotAddress = h->saved;
    __builtin___clear_cache((char*)pageStart, (char*)(pageStart + h->page_size));
    if (mprotect((void*)pageStart, h->page_size, h->orig_prot) != 0) {
        h->state = HS_INSTALLED;
        return HOOK_MPROTECT_FAILED;
    }
    RegistryFree(h);
    return HOOK_OK;
}

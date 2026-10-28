#include <link.h>
#include <string.h>
#include <stdio.h>
#include <stdint.h>
#include <unistd.h>
#include <sys/mman.h>
#include <android/log.h>

#define LOG_TAG "DualCore"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

struct FindSymbolData {
    const char *TargetLibName;
    const char *TargetSymbolName;
    void **GotAddress;
};

static int FindSymbolCallback(struct dl_phdr_info *info, size_t size, void *data) {
    auto *findData = (FindSymbolData *) data;

    if (!strstr(info->dlpi_name, findData->TargetLibName)) {
        return 0;
    }

    ElfW(Dyn) *dyn = nullptr;
    for (int i = 0; i < info->dlpi_phnum; i++) {
        if (info->dlpi_phdr[i].p_type == PT_DYNAMIC) {
            dyn = (ElfW(Dyn) *) (info->dlpi_addr + info->dlpi_phdr[i].p_vaddr);
            break;
        }
    }
    if (!dyn) return 1;

    const char *strtab = nullptr;
    ElfW(Sym) *symtab = nullptr;
    ElfW(Rela) *jmprel = nullptr;
    size_t pltrelsz = 0;
    ElfW(Rela) *relaDyn = nullptr;
    size_t relaDynSz = 0;

    for (ElfW(Dyn) *d = dyn; d->d_tag != DT_NULL; d++) {
        switch (d->d_tag) {
            case DT_STRTAB:
                strtab = (const char *) (info->dlpi_addr + d->d_un.d_ptr);
                break;
            case DT_SYMTAB:
                symtab = (ElfW(Sym) *) (info->dlpi_addr + d->d_un.d_ptr);
                break;
            case DT_JMPREL:
                jmprel = (ElfW(Rela) *) (info->dlpi_addr + d->d_un.d_ptr);
                break;
            case DT_PLTRELSZ:
                pltrelsz = d->d_un.d_val;
                break;
            case DT_RELA:
                relaDyn = (ElfW(Rela) *) (info->dlpi_addr + d->d_un.d_ptr);
                break;
            case DT_RELASZ:
                relaDynSz = d->d_un.d_val;
                break;
        }
    }

    if (!strtab || !symtab) return 1;

    auto processRela = [&](ElfW(Rela) *relArr, size_t arrSz) -> bool {
        if (!relArr) return false;
        size_t count = arrSz / sizeof(ElfW(Rela));
        for (size_t i = 0; i < count; i++) {
            ElfW(Rela) *rel = &relArr[i];
            size_t symIdx = ELF64_R_SYM(rel->r_info);
            ElfW(Sym) *sym = &symtab[symIdx];
            const char *symName = strtab + sym->st_name;

            if (symName && strcmp(symName, findData->TargetSymbolName) == 0) {
                findData->GotAddress = (void **) (info->dlpi_addr + rel->r_offset);
                return true;
            }
        }
        return false;
    };

    if (processRela(jmprel, pltrelsz)) return 1;
    if (processRela(relaDyn, relaDynSz)) return 1;

    return 1;
}

extern "C" void **GetGotAddress(const char *libName, const char *symbolName) {
    FindSymbolData data{};
    data.TargetLibName = libName;
    data.TargetSymbolName = symbolName;
    data.GotAddress = nullptr;

    dl_iterate_phdr(FindSymbolCallback, &data);
    return data.GotAddress;
}

// NEW: The function to actually overwrite the GOT
extern "C" bool HookGotEntry(void **gotAddress, void *newFunction, void **originalFunction) {
    if (!gotAddress) return false;

    // Save the original function pointer
    *originalFunction = *gotAddress;

    // Get the system page size (4096 on older, 16384 on your Android 17 emulator)
    long pageSize = sysconf(_SC_PAGESIZE);

    // Calculate the start of the memory page containing the GOT entry
    uintptr_t pageStart = (uintptr_t) gotAddress & ~(pageSize - 1);

    // Change protection to Read/Write
    if (mprotect((void *) pageStart, pageSize, PROT_READ | PROT_WRITE) != 0) {
        LOGE("mprotect failed to make GOT writable!");
        return false;
    }

    // Overwrite the pointer
    *gotAddress = newFunction;

    // Restore protection to Read-Only (standard for GOT)
    mprotect((void *) pageStart, pageSize, PROT_READ);

    return true;
}
#include "ElfResolver.h"
#include <link.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <cstring>
#include <cstdio>
#include <cerrno>
#include <cstdlib>
#include <android/log.h>

#define TAG "DualCore"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#if defined(__clang__) && __has_attribute(retain)
extern "C" __attribute__((used, retain, visibility("hidden")))
const char ElfResolver_SymtabOnlyMarker[] = "ELF_SYMTAB_PROBE_S68";
#else
extern "C" __attribute__((used, visibility("hidden")))
const char ElfResolver_SymtabOnlyMarker[] = "ELF_SYMTAB_PROBE_S68";
#endif

static int g_elf_last_path = -1;
extern "C" int ElfSymbolLookupLastPath() { return g_elf_last_path; }

static uint32_t gnu_hash(const char* s) {
    uint32_t h = 5381;
    for (unsigned char c = *s; c != 0; c = (unsigned char)*++s)
        h = (h << 5) + h + c;
    return h;
}

static uint32_t sysv_hash(const char* s) {
    unsigned long h = 0, g;
    for (unsigned char c = *s; c != 0; c = (unsigned char)*++s) {
        h = (h << 4) + c;
        g = h & 0xf0000000UL;
        if (g) h ^= g >> 24;
        h &= ~g;
    }
    return (uint32_t)h;
}

struct LibFindCtx {
    const char* needle;
    uintptr_t   dlpi_addr;
    char        path[512];
    int         found;
};

static int FindLibCb(struct dl_phdr_info* info, size_t, void* data) {
    LibFindCtx* ctx = (LibFindCtx*)data;
    if (!info->dlpi_name || !info->dlpi_name[0]) return 0;
    if (info->dlpi_name[0] == '[') return 0;
    if (!strstr(info->dlpi_name, ctx->needle)) return 0;
    ctx->dlpi_addr = (uintptr_t)info->dlpi_addr;
    strncpy(ctx->path, info->dlpi_name, sizeof(ctx->path) - 1);
    ctx->path[sizeof(ctx->path) - 1] = '\0';
    ctx->found = 1;
    return 1;
}

extern "C" ElfMappedLib* ElfMappedLib_Open(const char* lib_substr) {
    if (!lib_substr || !lib_substr[0]) return nullptr;
    if (lib_substr[0] == '[') return nullptr;

    char      resolved_path[512] = {0};
    uintptr_t bias = 0;

    struct stat path_probe{};
    if (lib_substr[0] == '/' && stat(lib_substr, &path_probe) == 0) {
        strncpy(resolved_path, lib_substr, sizeof(resolved_path) - 1);
        const char* base = strrchr(lib_substr, '/');
        base = base ? base + 1 : lib_substr;
        LibFindCtx bctx = { base, 0, {0}, 0 };
        dl_iterate_phdr(FindLibCb, &bctx);
        if (bctx.found) {
            bias = bctx.dlpi_addr;
            LOGD("ElfResolver: path-mode basename='%s' matched loaded lib bias=0x%lx",
                 base, (unsigned long)bias);
        } else {
            LOGD("ElfResolver: path-mode '%s' not loaded, using bias=0", lib_substr);
        }
    } else {
        LibFindCtx ctx = { lib_substr, 0, {0}, 0 };
        dl_iterate_phdr(FindLibCb, &ctx);
        if (!ctx.found) {
            LOGE("ElfResolver: lib '%s' not loaded", lib_substr);
            return nullptr;
        }
        strncpy(resolved_path, ctx.path, sizeof(resolved_path) - 1);
        bias = ctx.dlpi_addr;
        if (strstr(resolved_path, "!/") != nullptr) {
            LOGE("ElfResolver: lib '%s' is APK-internal (%s); "
                 "not filesystem-openable. Extract to a path and retry.",
                 lib_substr, resolved_path);
            return nullptr;
        }
    }

    int fd = open(resolved_path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        LOGE("ElfResolver: open(%s) errno=%d", resolved_path, errno);
        return nullptr;
    }
    struct stat st{};
    if (fstat(fd, &st) != 0 || st.st_size <= (off_t)sizeof(Elf64_Ehdr)) {
        close(fd);
        return nullptr;
    }
    void* map = mmap(nullptr, (size_t)st.st_size, PROT_READ, MAP_PRIVATE, fd, 0);
    close(fd);
    if (map == MAP_FAILED) return nullptr;

    const uint8_t*    base_ptr = (const uint8_t*)map;
    const Elf64_Ehdr* ehdr     = (const Elf64_Ehdr*)base_ptr;

    if (memcmp(ehdr->e_ident, ELFMAG, SELFMAG) != 0 ||
        ehdr->e_ident[EI_CLASS] != ELFCLASS64 ||
        ehdr->e_ident[EI_DATA]  != ELFDATA2LSB ||
        ehdr->e_machine != EM_AARCH64 ||
        ehdr->e_shoff == 0 || ehdr->e_shnum == 0 ||
        ehdr->e_shstrndx == SHN_UNDEF || ehdr->e_shstrndx >= ehdr->e_shnum) {
        LOGE("ElfResolver: invalid ELF header in %s", resolved_path);
        munmap(map, (size_t)st.st_size);
        return nullptr;
    }

    ElfMappedLib* out = (ElfMappedLib*)calloc(1, sizeof(ElfMappedLib));
    if (!out) { munmap(map, (size_t)st.st_size); return nullptr; }
    out->dlpi_bias = bias;
    memcpy(out->path, resolved_path, sizeof(out->path));
    out->base      = base_ptr;
    out->size      = (size_t)st.st_size;
    out->ehdr      = ehdr;
    out->shdrs     = (const Elf64_Shdr*)(base_ptr + ehdr->e_shoff);
    out->shnum     = ehdr->e_shnum;
    out->shstrndx  = ehdr->e_shstrndx;
    out->shstrtab  = (const char*)(base_ptr + out->shdrs[ehdr->e_shstrndx].sh_offset);
    return out;
}

extern "C" void ElfMappedLib_Close(ElfMappedLib* lib) {
    if (!lib) return;
    if (lib->base) munmap((void*)lib->base, lib->size);
    free(lib);
}

extern "C" const Elf64_Shdr* ElfMappedLib_FindSection(const ElfMappedLib* lib,
                                                      const char* name) {
    if (!lib || !name) return nullptr;
    for (int i = 0; i < lib->shnum; i++) {
        if (strcmp(lib->shstrtab + lib->shdrs[i].sh_name, name) == 0)
            return &lib->shdrs[i];
    }
    return nullptr;
}

static uint32_t lookup_gnu_hash(const ElfMappedLib* lib,
                                const Elf64_Shdr* dynsym_hdr,
                                const Elf64_Shdr* dynstr_hdr,
                                const Elf64_Shdr* gnu_hash_hdr,
                                const char* symbol) {
    const uint8_t*  gh         = lib->base + gnu_hash_hdr->sh_offset;
    const uint32_t  nbuckets   = *(const uint32_t*)(gh + 0);
    const uint32_t  symoffset  = *(const uint32_t*)(gh + 4);
    const uint32_t  bloom_size = *(const uint32_t*)(gh + 8);
    const uint64_t* bloom      = (const uint64_t*)(gh + 16);
    const uint32_t* buckets    = (const uint32_t*)(bloom + bloom_size);
    const uint32_t* chain      = buckets + nbuckets;

    const uint32_t h      = gnu_hash(symbol);
    const uint32_t bucket = h % nbuckets;
    uint32_t idx          = buckets[bucket];
    if (idx < symoffset) return 0;

    const Elf64_Sym* dynsym      = (const Elf64_Sym*)(lib->base + dynsym_hdr->sh_offset);
    const uint32_t   dynsym_cnt  = (uint32_t)(dynsym_hdr->sh_size / sizeof(Elf64_Sym));
    const char*      dynstr      = (const char*)(lib->base + dynstr_hdr->sh_offset);
    const uint32_t   dynstr_sz   = (uint32_t)dynstr_hdr->sh_size;

    while (idx < dynsym_cnt) {
        uint32_t ch = chain[idx - symoffset];
        if ((ch | 1u) == (h | 1u)) {
            const Elf64_Sym* s = &dynsym[idx];
            if (s->st_name < dynstr_sz && strcmp(dynstr + s->st_name, symbol) == 0)
                return idx;
        }
        if (ch & 1u) break;
        idx++;
    }
    return 0;
}

static uint32_t lookup_sysv_hash(const ElfMappedLib* lib,
                                 const Elf64_Shdr* dynsym_hdr,
                                 const Elf64_Shdr* dynstr_hdr,
                                 const Elf64_Shdr* hash_hdr,
                                 const char* symbol) {
    const uint8_t*  hh      = lib->base + hash_hdr->sh_offset;
    const uint32_t  nbucket = *(const uint32_t*)(hh + 0);
    const uint32_t  nchain  = *(const uint32_t*)(hh + 4);
    const uint32_t* buckets = (const uint32_t*)(hh + 8);
    const uint32_t* chain   = buckets + nbucket;

    const Elf64_Sym* dynsym      = (const Elf64_Sym*)(lib->base + dynsym_hdr->sh_offset);
    const uint32_t   dynsym_cnt  = (uint32_t)(dynsym_hdr->sh_size / sizeof(Elf64_Sym));
    const char*      dynstr      = (const char*)(lib->base + dynstr_hdr->sh_offset);
    const uint32_t   dynstr_sz   = (uint32_t)dynstr_hdr->sh_size;

    uint32_t idx = buckets[sysv_hash(symbol) % nbucket];
    while (idx != 0 && idx < nchain && idx < dynsym_cnt) {
        const Elf64_Sym* s = &dynsym[idx];
        if (s->st_name < dynstr_sz && strcmp(dynstr + s->st_name, symbol) == 0)
            return idx;
        idx = chain[idx];
    }
    return 0;
}

static uint32_t lookup_dynsym_linear(const ElfMappedLib* lib,
                                     const Elf64_Shdr* dynsym_hdr,
                                     const Elf64_Shdr* dynstr_hdr,
                                     const char* symbol) {
    const Elf64_Sym* dynsym     = (const Elf64_Sym*)(lib->base + dynsym_hdr->sh_offset);
    const uint32_t   dynsym_cnt = (uint32_t)(dynsym_hdr->sh_size / sizeof(Elf64_Sym));
    const char*      dynstr     = (const char*)(lib->base + dynstr_hdr->sh_offset);
    const uint32_t   dynstr_sz  = (uint32_t)dynstr_hdr->sh_size;
    for (uint32_t i = 1; i < dynsym_cnt; i++) {
        const Elf64_Sym* s = &dynsym[i];
        if (s->st_shndx == SHN_UNDEF) continue;
        if (s->st_name == 0 || s->st_name >= dynstr_sz) continue;
        if (strcmp(dynstr + s->st_name, symbol) == 0) return i;
    }
    return 0;
}

static void* lookup_symtab(const ElfMappedLib* lib, const char* symbol) {
    const Elf64_Shdr* symtab_hdr = ElfMappedLib_FindSection(lib, ".symtab");
    if (!symtab_hdr) {
        LOGD("ElfResolver: no .symtab section in %s", lib->path);
        return nullptr;
    }
    if (symtab_hdr->sh_link == 0 || symtab_hdr->sh_link >= (uint32_t)lib->shnum) {
        LOGE("ElfResolver: .symtab has bad sh_link=%u (shnum=%d)",
             symtab_hdr->sh_link, lib->shnum);
        return nullptr;
    }
    const Elf64_Shdr* strtab_hdr = &lib->shdrs[symtab_hdr->sh_link];
    const size_t symtab_n   = symtab_hdr->sh_size / sizeof(Elf64_Sym);
    const Elf64_Sym* symtab = (const Elf64_Sym*)(lib->base + symtab_hdr->sh_offset);
    const char*      strtab = (const char*)(lib->base + strtab_hdr->sh_offset);
    const size_t     strtab_sz = strtab_hdr->sh_size;

    for (size_t i = 1; i < symtab_n; i++) {
        const Elf64_Sym* s = &symtab[i];
        if (s->st_shndx == SHN_UNDEF) continue;
        if (s->st_name == 0 || s->st_name >= strtab_sz) continue;
        if (strcmp(strtab + s->st_name, symbol) == 0) {
            return (void*)(lib->dlpi_bias + s->st_value);
        }
    }
    return nullptr;
}

static void* lookup_in_lib(const ElfMappedLib* lib, const char* symbol) {
    const Elf64_Shdr* dynsym_hdr = ElfMappedLib_FindSection(lib, ".dynsym");
    const Elf64_Shdr* dynstr_hdr = ElfMappedLib_FindSection(lib, ".dynstr");
    const Elf64_Shdr* gnu_hdr    = ElfMappedLib_FindSection(lib, ".gnu.hash");
    const Elf64_Shdr* sysv_hdr   = ElfMappedLib_FindSection(lib, ".hash");

    uint32_t idx  = 0;
    int      path = -1;

    if (dynsym_hdr && dynstr_hdr) {
        if (gnu_hdr) {
            idx = lookup_gnu_hash(lib, dynsym_hdr, dynstr_hdr, gnu_hdr, symbol);
            if (idx) path = 0;
        }
        if (!idx && sysv_hdr) {
            idx = lookup_sysv_hash(lib, dynsym_hdr, dynstr_hdr, sysv_hdr, symbol);
            if (idx) path = 1;
        }
        if (!idx) {
            idx = lookup_dynsym_linear(lib, dynsym_hdr, dynstr_hdr, symbol);
            if (idx) path = 2;
        }
    }

    if (idx) {
        g_elf_last_path = path;
        const Elf64_Sym* dynsym = (const Elf64_Sym*)(lib->base + dynsym_hdr->sh_offset);
        return (void*)(lib->dlpi_bias + dynsym[idx].st_value);
    }

    void* symtab_result = lookup_symtab(lib, symbol);
    if (symtab_result) {
        g_elf_last_path = 3;
        return symtab_result;
    }
    return nullptr;
}

extern "C" void* ElfSymbolLookup(const char* lib_substr, const char* symbol) {
    g_elf_last_path = -1;
    if (!lib_substr || !lib_substr[0] || !symbol || !symbol[0]) return nullptr;
    ElfMappedLib* lib = ElfMappedLib_Open(lib_substr);
    if (!lib) return nullptr;
    void* r = lookup_in_lib(lib, symbol);
    ElfMappedLib_Close(lib);
    return r;
}

struct MultiCtx { const char* symbol; void* result; };

static int MultiSearchCb(struct dl_phdr_info* info, size_t, void* data) {
    MultiCtx* mctx = (MultiCtx*)data;
    if (!info->dlpi_name || !info->dlpi_name[0]) return 0;
    if (info->dlpi_name[0] == '[') return 0;
    if (strstr(info->dlpi_name, "!/")) return 0;
    void* r = ElfSymbolLookup(info->dlpi_name, mctx->symbol);
    if (r) { mctx->result = r; return 1; }
    return 0;
}

extern "C" void* ElfSymbolLookupMulti(const char* symbol) {
    if (!symbol || !symbol[0]) return nullptr;
    MultiCtx mctx = { symbol, nullptr };
    dl_iterate_phdr(MultiSearchCb, &mctx);
    return mctx.result;
}

// ─── S68 revision 3 — .symtab enumeration ──────────────────────────
// Used by ElfResolveTest.symtabFallbackFires to discover a .symtab-only
// symbol at runtime. Filters for entries lookup_symtab would actually
// return: skip UNDEF, skip unnamed, skip zero-address. Returns a pointer
// into the mmap'd .strtab — valid only while the ElfMappedLib is open.
extern "C" const char* ElfSymtabNthName(const ElfMappedLib* lib, size_t idx) {
    if (!lib) return nullptr;
    const Elf64_Shdr* symtab_hdr = ElfMappedLib_FindSection(lib, ".symtab");
    if (!symtab_hdr) return nullptr;
    if (symtab_hdr->sh_link == 0 || symtab_hdr->sh_link >= (uint32_t)lib->shnum)
        return nullptr;
    const Elf64_Shdr* strtab_hdr = &lib->shdrs[symtab_hdr->sh_link];
    const size_t symtab_n = symtab_hdr->sh_size / sizeof(Elf64_Sym);
    if (idx == 0 || idx >= symtab_n) return nullptr;

    const Elf64_Sym* symtab = (const Elf64_Sym*)(lib->base + symtab_hdr->sh_offset);
    const Elf64_Sym* s = &symtab[idx];
    if (s->st_shndx == SHN_UNDEF) return nullptr;
    if (s->st_name == 0 || s->st_name >= strtab_hdr->sh_size) return nullptr;
    if (s->st_value == 0) return nullptr;

    const char* strtab = (const char*)(lib->base + strtab_hdr->sh_offset);
    return strtab + s->st_name;
}
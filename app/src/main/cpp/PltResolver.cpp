#include "PltResolver.h"
#include "ElfResolver.h"
#include <cstring>
#include <cstdio>
#include <android/log.h>

#define TAG "DualCore"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static constexpr uint32_t kInsnAdrp     = 0x90000000u;
static constexpr uint32_t kInsnAdrpMask = 0x9F000000u;
static constexpr uint32_t kInsnBtiC     = 0xD503245Fu;
static constexpr uint32_t kRelAarch64JumpSlot = 1026;

static bool LooksLikePltStub(uint64_t abs_addr) {
    if (abs_addr < 0x1000) return false;
    uint32_t insn0 = *(volatile uint32_t*)(uintptr_t)abs_addr;
    return (insn0 & kInsnAdrpMask) == kInsnAdrp || insn0 == kInsnBtiC;
}

extern "C" uint64_t PltResolveForSymbol(const char* lib_substr, const char* symbol) {
    if (!lib_substr || !lib_substr[0] || !symbol || !symbol[0]) return 0;

    ElfMappedLib* lib = ElfMappedLib_Open(lib_substr);
    if (!lib) {
        LOGE("PltResolver: lib '%s' not openable via ElfMappedLib", lib_substr);
        return 0;
    }
    LOGD("PltResolver: lib '%s' path=%s bias=0x%lx",
         lib_substr, lib->path, (unsigned long)lib->dlpi_bias);

    const Elf64_Shdr* dynsym_shdr  = ElfMappedLib_FindSection(lib, ".dynsym");
    const Elf64_Shdr* dynstr_shdr  = ElfMappedLib_FindSection(lib, ".dynstr");
    const Elf64_Shdr* relaplt_shdr = ElfMappedLib_FindSection(lib, ".rela.plt");
    const Elf64_Shdr* plt_shdr     = ElfMappedLib_FindSection(lib, ".plt");
    const Elf64_Shdr* pltsec_shdr  = ElfMappedLib_FindSection(lib, ".plt.sec");

    if (!dynsym_shdr || !dynstr_shdr || !relaplt_shdr) {
        LOGE("PltResolver: missing sections (dynsym=%p dynstr=%p rela.plt=%p)",
             dynsym_shdr, dynstr_shdr, relaplt_shdr);
        ElfMappedLib_Close(lib);
        return 0;
    }
    if (!pltsec_shdr && !plt_shdr) {
        LOGE("PltResolver: no .plt or .plt.sec section");
        ElfMappedLib_Close(lib);
        return 0;
    }

    const Elf64_Sym* dynsym = (const Elf64_Sym*)(lib->base + dynsym_shdr->sh_offset);
    const size_t dynsym_count = dynsym_shdr->sh_size / sizeof(Elf64_Sym);
    const char* dynstr = (const char*)(lib->base + dynstr_shdr->sh_offset);

    const Elf64_Rela* relas = (const Elf64_Rela*)(lib->base + relaplt_shdr->sh_offset);
    const size_t rela_count = relaplt_shdr->sh_size / sizeof(Elf64_Rela);

    LOGD("PltResolver: %zu relocs, .plt=%p .plt.sec=%p",
         rela_count, plt_shdr, pltsec_shdr);

    size_t plt_index = 0;
    for (size_t i = 0; i < rela_count; i++) {
        const Elf64_Rela* r = &relas[i];
        if (ELF64_R_TYPE(r->r_info) != kRelAarch64JumpSlot) continue;
        uint32_t sym_idx = ELF64_R_SYM(r->r_info);
        if (sym_idx == 0 || sym_idx >= dynsym_count) { plt_index++; continue; }
        const char* sym_name = dynstr + dynsym[sym_idx].st_name;
        if (strcmp(sym_name, symbol) != 0) { plt_index++; continue; }

        if (pltsec_shdr) {
            uint64_t off = (uint64_t)plt_index * 16;
            if (off + 16 > pltsec_shdr->sh_size) {
                LOGE("PltResolver: .plt.sec offset %lu exceeds size %lu",
                     (unsigned long)off, (unsigned long)pltsec_shdr->sh_size);
                ElfMappedLib_Close(lib);
                return 0;
            }
            uint64_t abs_addr = lib->dlpi_bias + pltsec_shdr->sh_addr + off;
            LOGD("PltResolver: '%s' plt_index=%zu .plt.sec+0x%lx = 0x%lx",
                 symbol, plt_index, (unsigned long)off, (unsigned long)abs_addr);
            ElfMappedLib_Close(lib);
            return abs_addr;
        }

        const uint64_t base_candidates[] = { 32, 48 };
        for (uint64_t base_off : base_candidates) {
            uint64_t off = base_off + (uint64_t)plt_index * 16;
            if (off + 16 > plt_shdr->sh_size) continue;
            uint64_t abs_addr = lib->dlpi_bias + plt_shdr->sh_addr + off;
            if (LooksLikePltStub(abs_addr)) {
                LOGD("PltResolver: '%s' plt_index=%zu .plt+0x%lx (base=%lu) = 0x%lx",
                     symbol, plt_index, (unsigned long)off,
                     (unsigned long)base_off, (unsigned long)abs_addr);
                ElfMappedLib_Close(lib);
                return abs_addr;
            }
            LOGD("PltResolver: candidate .plt+0x%lx (base=%lu) failed opcode check",
                 (unsigned long)off, (unsigned long)base_off);
        }
        LOGE("PltResolver: '%s' no valid PLT stub at either .plt offset", symbol);
        ElfMappedLib_Close(lib);
        return 0;
    }

    LOGD("PltResolver: '%s' not in .rela.plt", symbol);
    ElfMappedLib_Close(lib);
    return 0;
}
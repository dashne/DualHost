#pragma once
#include <cstdint>
#include <cstddef>
#include <elf.h>

// Feature 7.1 — unified ELF symbol resolution.
//
// See prior revision for full API doc. This revision adds
// ElfSymtabNthName for the symtabFallbackFires discovery test.

struct ElfMappedLib {
    uintptr_t         dlpi_bias;
    char              path[512];
    const uint8_t*    base;
    size_t            size;
    const Elf64_Ehdr* ehdr;
    const Elf64_Shdr* shdrs;
    int               shnum;
    int               shstrndx;
    const char*       shstrtab;
};

extern "C" ElfMappedLib* ElfMappedLib_Open(const char* lib_substr);
extern "C" void          ElfMappedLib_Close(ElfMappedLib* lib);
extern "C" const Elf64_Shdr* ElfMappedLib_FindSection(const ElfMappedLib* lib,
                                                      const char* name);

extern "C" void* ElfSymbolLookup(const char* lib_substr, const char* symbol);
extern "C" void* ElfSymbolLookupMulti(const char* symbol);
extern "C" int   ElfSymbolLookupLastPath();

// S68 revision 3 — .symtab enumeration for symtabFallbackFires.
// Returns the Nth (0-based) well-formed symbol name from .symtab:
//   - skips SHN_UNDEF (imports) — lookup_symtab skips them too
//   - skips st_name==0 (unnamed / section names)
//   - skips st_value==0 (no runtime address — can't be verified)
// Returns nullptr if idx is out of range or skipped. Caller MUST treat
// nullptr as "no name at this index, try next".
extern "C" const char* ElfSymtabNthName(const ElfMappedLib* lib, size_t idx);
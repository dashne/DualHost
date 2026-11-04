#pragma once
#include <cstdint>

// Feature 7.6 — PLT stub resolution.
//
// Resolves the PLT entry address for a given imported symbol in a loaded
// library. Complements Features 7.2–7.5 (dynsym / symtab / hash / multi-lib
// symbol resolution) by returning the CALL-SITE address — the PLT thunk
// the library's own code branches into when it calls the import.
//
// The returned address is the first byte of the PLT entry, which is either
// `adrp x16, ...` (non-BTI .plt layout) or `bti c` (BTI-enabled .plt.sec
// layout on API 37+). The next 12-16 bytes are the stub that jumps through
// the GOT slot which Feature 1.2's GOT hook patches.
//
// AArch64 PLT layout (Bionic / LLD):
//   .plt:
//     [0..32)        PLT0 resolver stub
//     [32..48)       PLT[1] (reserved OR first symbol, layout varies)
//     [48..64)       PLT[2] (second symbol or reserved)
//     ...
//   .plt.sec (BTI builds, when present):
//     [0..16)        first symbol (has `bti c` prefix)
//     [16..32)       second symbol
//     ...
//
// When `.plt.sec` exists, it contains exactly one 16-byte entry per
// `.rela.plt` JUMP_SLOT relocation, indexed 0-based. When `.plt.sec` is
// absent, `.plt` has PLT0 followed by the entries at either 32 or 48
// offset depending on linker convention — resolved at runtime by checking
// the first instruction is `adrp` or `bti c`.
//
// Returns the runtime PLT entry address, or 0 on failure:
//   - library not found by substring
//   - file unreadable
//   - section headers stripped (no SHDR on disk)
//   - symbol not in .rela.plt (not imported, or link-time resolved)
//   - no valid PLT stub at either candidate offset
extern "C" uint64_t PltResolveForSymbol(const char* lib_substr, const char* symbol);
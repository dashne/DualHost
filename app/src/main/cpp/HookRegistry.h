// Adopted from HookEngine (dashne/HookEngine), trimmed to the accounting
// core: install/uninstall bookkeeping, double-install detection, rollback
// support. The per-guest tagging / tag-scoped uninstall feature from the
// original was dropped — DualHost already groups guests by clone id at the
// Kotlin layer, and that feature pulls in an uninstall-by-tag path that
// isn't needed here.
#pragma once
#include <cstdint>
#include <cstddef>

enum HookResult : int {
    HOOK_OK = 0,
    HOOK_UNSUPPORTED_INSN,
    HOOK_SYMBOL_MISSING,
    HOOK_MPROTECT_FAILED,
    HOOK_ALLOC_FAILED,
    HOOK_ALREADY_HOOKED,
    HOOK_NOT_HOOKED,
    HOOK_INTERNAL_ERROR,
};

enum HookType { HT_INLINE = 0, HT_GOT = 1 };
enum HookState { HS_FREE = 0, HS_INSTALLING, HS_INSTALLED, HS_REMOVING };

struct HookHandle {
    HookType  type        = HT_INLINE;
    void*     target      = nullptr;
    void*     replacement = nullptr;
    void*     saved       = nullptr;   // GOT: original pointer. Inline: relay page.
    void*     trampoline  = nullptr;   // Inline only: call-original trampoline.
    size_t    tramp_size  = 0;
    size_t    relay_size  = 0;
    // Inline only: the literal original 4 bytes that were at target[0]
    // before patching. NOT the same as trampoline[0] in general — when the
    // original instruction was PC-relative, trampoline[0] holds the
    // *relocated* re-encoding (see arm64_reloc.h), which is only byte-
    // identical to the original for NORMAL-kind instructions. Uninstall
    // must restore from here, not from the trampoline.
    uint32_t  orig_insn   = 0;
    int       orig_prot   = 0;
    size_t    page_size   = 0;
    int       state       = HS_FREE;
    char      name[96]    = {0};
};

HookHandle* RegistryAlloc();
void        RegistryFree(HookHandle* h);
HookHandle* FindHandle(void* target);
int         RegistrySnapshot(HookHandle* out, int max);
int         RegistryCount();

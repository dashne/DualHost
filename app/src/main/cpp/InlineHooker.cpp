#include <stdint.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>
#include <android/log.h>
#include <errno.h>

#define LOG_TAG "DualCore"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ARM64 Encodings
#define ARM64_BRANCH_OFFSET(offset) (0x14000000 | (((offset) >> 2) & 0x03FFFFFF))
#define ARM64_LDR_X17_8 0x58000051
#define ARM64_BR_X17    0xD61F0220

// Helper to allocate memory near a specific address (within +/- 128MB for the B instruction)
void* AllocateMemoryNear(void* target, size_t size) {
    long pageSize = sysconf(_SC_PAGESIZE);
    uintptr_t targetAddr = (uintptr_t)target;
    const uintptr_t RANGE = 120UL * 1024 * 1024;   // safe margin under 128 MB

    for (int i = 1; i < 120; i++) {
        uintptr_t off = (uintptr_t)i * 1024 * 1024;

        uintptr_t candidates[2];
        int n = 0;
        if (targetAddr >= off)     candidates[n++] = (targetAddr - off) & ~(pageSize - 1);
        if (targetAddr + off > off) candidates[n++] = (targetAddr + off) & ~(pageSize - 1);

        for (int k = 0; k < n; k++) {
            void* hint = (void*)candidates[k];
            // MAP_FIXED_NOREPLACE: fails with EEXIST if the address is taken
            // → no silent far-away placement
            void* m = mmap(hint, size, PROT_READ | PROT_WRITE,
                           MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED_NOREPLACE, -1, 0);
            if (m == MAP_FAILED) continue;
            if (m != hint) { munmap(m, size); continue; }     // paranoid
            uintptr_t got = (uintptr_t)m;
            if (got > targetAddr + RANGE || targetAddr > got + RANGE) {
                munmap(m, size);
                continue;
            }
            if (mprotect(m, size, PROT_READ | PROT_WRITE | PROT_EXEC) != 0) {
                LOGE("mprotect RX on relay %p failed: %d", m, errno);
                munmap(m, size);
                continue;
            }
            return m;
        }
    }
    return nullptr;
}

extern "C" bool InstallInlineHook(void* targetFunc, void* fakeFunc, void** originalTrampoline) {
    if (!targetFunc || !fakeFunc) return false;

    uint32_t* targetCode = (uint32_t*)targetFunc;
    long pageSize = sysconf(_SC_PAGESIZE);

    // 1. Allocate the Relay page (must be within 120MB of target)
    void* relayPage = AllocateMemoryNear(targetFunc, pageSize);
    if (!relayPage) {
        LOGE("Failed to allocate relay page within range of %p — SKIPPING hook", targetFunc);
        return false;
    }

    uint32_t* relayCode = (uint32_t*)relayPage;
    relayCode[0] = ARM64_LDR_X17_8;
    relayCode[1] = ARM64_BR_X17;
    *(uint64_t*)&relayCode[2] = (uint64_t)fakeFunc;

    // 2. Allocate the Trampoline
    void* trampoline = mmap(NULL, pageSize, PROT_READ | PROT_WRITE,
                            MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (trampoline == MAP_FAILED) {
        munmap(relayPage, pageSize);
        return false;
    }

    uint32_t* trampCode = (uint32_t*)trampoline;

    // 3. Save the original first instruction (this is our restore source)
    uint32_t origFirstInsn = targetCode[0];
    trampCode[0] = origFirstInsn;

    // 4. Append jump-back to target + 4
    uint64_t jumpBackAddr = (uint64_t)targetFunc + 4;
    trampCode[1] = ARM64_LDR_X17_8;
    trampCode[2] = ARM64_BR_X17;
    *(uint64_t*)&trampCode[3] = jumpBackAddr;

    // 5. mprotect trampoline to RX (RW-only above, then RX here)
    if (mprotect(trampoline, pageSize, PROT_READ | PROT_EXEC) != 0) {
        LOGE("mprotect trampoline %p failed: %d", trampoline, errno);
        munmap(trampoline, pageSize);
        munmap(relayPage, pageSize);
        return false;
    }

    // 6. Make target writable
    uintptr_t pageStart = (uintptr_t)targetFunc & ~(pageSize - 1);
    if (mprotect((void*)pageStart, pageSize, PROT_READ | PROT_WRITE | PROT_EXEC) != 0) {
        LOGE("mprotect target %p failed: %d", targetFunc, errno);
        munmap(trampoline, pageSize);
        munmap(relayPage, pageSize);
        return false;
    }



    // 7. Compute and write the 4-byte branch
    int64_t offset = (int64_t)relayPage - (int64_t)targetFunc;
    uint32_t branchInstruction = ARM64_BRANCH_OFFSET(offset);
    targetCode[0] = branchInstruction;
    __builtin___clear_cache((char*)targetFunc, (char*)targetFunc + 4);

    // 8. Verify the write took
    if (targetCode[0] != branchInstruction) {
        LOGE("patch verify FAILED at %p: wrote %08x read %08x",
             targetFunc, branchInstruction, targetCode[0]);
        targetCode[0] = origFirstInsn;
        __builtin___clear_cache((char*)targetFunc, (char*)targetFunc + 4);
        munmap(trampoline, pageSize);
        munmap(relayPage, pageSize);
        return false;
    }

    *originalTrampoline = trampoline;
    LOGD("hook %p OK: relay %p, distance 0x%lx",
         targetFunc, relayPage,
         (unsigned long)((intptr_t)relayPage - (intptr_t)targetFunc));
    return true;
}
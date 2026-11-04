#include "HookRegistry.h"
#include <cstring>
#include <mutex>

static constexpr int kMaxHooks = 512;
static HookHandle g_slots[kMaxHooks];
static std::mutex  g_reg_mutex;

HookHandle* RegistryAlloc() {
    std::lock_guard<std::mutex> l(g_reg_mutex);
    for (auto& s : g_slots) {
        if (s.state == HS_FREE) {
            s.state = HS_INSTALLING;
            return &s;
        }
    }
    return nullptr;
}

void RegistryFree(HookHandle* h) {
    if (!h) return;
    std::lock_guard<std::mutex> l(g_reg_mutex);
    h->type = HT_INLINE;
    h->target = h->replacement = h->saved = h->trampoline = nullptr;
    h->tramp_size = 0;
    h->relay_size = 0;
    h->orig_insn = 0;
    h->orig_prot = 0;
    h->page_size = 0;
    h->name[0] = 0;
    h->state = HS_FREE;
}

HookHandle* FindHandle(void* target) {
    std::lock_guard<std::mutex> l(g_reg_mutex);
    for (auto& s : g_slots)
        if (s.state == HS_INSTALLED && s.target == target) return &s;
    return nullptr;
}

int RegistrySnapshot(HookHandle* out, int max) {
    std::lock_guard<std::mutex> l(g_reg_mutex);
    int n = 0;
    for (auto& s : g_slots)
        if (s.state == HS_INSTALLED && n < max) out[n++] = s;
    return n;
}

int RegistryCount() {
    std::lock_guard<std::mutex> l(g_reg_mutex);
    int n = 0;
    for (auto& s : g_slots) if (s.state == HS_INSTALLED) n++;
    return n;
}

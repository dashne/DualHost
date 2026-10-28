#include "Hooks.h"
#include "RedirectTable.h"
#include <android/log.h>
#include <fcntl.h>
#include <stdarg.h>
#include <cstring>
#include <link.h>
#include <stdint.h>
#include <dlfcn.h>
#include <jni.h>

#define LOG_TAG "DualCore"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)



extern "C" bool InstallInlineHook(void* targetFunc, void* fakeFunc, void** originalTrampoline);

int (*_Original__open_2)(const char*, int) = nullptr;
int (*_Original_openat)(int, const char*, int, ...) = nullptr;
int (*_Original_stat)(const char*, struct stat*) = nullptr;
int (*_Original_access)(const char*, int) = nullptr;
ssize_t (*_Original_readlink)(const char*, char*, size_t) = nullptr;
int (*_Original_unlink)(const char*) = nullptr;
int (*_Original_unlinkat)(int, const char*, int) = nullptr;
int (*_Original_rename)(const char*, const char*) = nullptr;
int (*_Original_renameat)(int, const char*, int, const char*) = nullptr;
int (*_Original_mkdir)(const char*, mode_t) = nullptr;
int (*_Original_mkdirat)(int, const char*, mode_t) = nullptr;
int (*_Original_fstatat)(int, const char*, struct stat*, int) = nullptr;
int (*_Original_rmdir)(const char*) = nullptr;
int (*_Original_truncate)(const char*, off_t) = nullptr;

int (*_Original_stat64)(const char*, struct stat64*) = nullptr;
int (*_Original_open64)(const char*, int, ...) = nullptr;
int (*_Original_openat64)(int, const char*, int, ...) = nullptr;
int (*_Original_faccessat)(int, const char*, int, int) = nullptr;
int (*_Original_lstat)(const char*, struct stat*) = nullptr;

void* (*_Original_dlopen)(const char*, int) = nullptr;
void* (*_Original_android_dlopen_ext)(const char*, int, const void*) = nullptr;

const char* (*_Original_getprogname)() = nullptr;
FILE* (*_Original_fopen)(const char*, const char*) = nullptr;
int (*_Original_strcmp)(const char*, const char*) = nullptr;
std::string _GuestProcessName = "";

extern "C" void** GetGotAddress(const char* libName, const char* symbolName);
extern "C" bool HookGotEntry(void** gotAddress, void* newFunction, void** originalFunction);

int Fake__open_2(const char* path, int flags) {
    std::string newPath = RedirectTable::GetInstance().ResolvePath(path);
    if (!newPath.empty()) return _Original__open_2(newPath.c_str(), flags);
    return _Original__open_2(path, flags);
}

int Fake_openat(int dirfd, const char* path, int flags, ...) {
    mode_t mode = 0;
    if (flags & O_CREAT) {
        va_list args; va_start(args, flags); mode = va_arg(args, mode_t); va_end(args);
    }
    std::string newPath = RedirectTable::GetInstance().ResolvePath(path);
    if (!newPath.empty()) return _Original_openat(dirfd, newPath.c_str(), flags, mode);
    return _Original_openat(dirfd, path, flags, mode);
}

int Fake_stat(const char* path, struct stat* buf) {
    std::string newPath = RedirectTable::GetInstance().ResolvePath(path);
    if (!newPath.empty()) {
        LOGD("Intercepted stat: %s -> %s", path, newPath.c_str());
        return _Original_stat(newPath.c_str(), buf);
    }
    return _Original_stat(path, buf);
}

int Fake_access(const char* path, int mode) {
    std::string newPath = RedirectTable::GetInstance().ResolvePath(path);
    if (!newPath.empty()) {
        LOGD("Intercepted stat: %s -> %s", path, newPath.c_str());
        return _Original_access(newPath.c_str(), mode);
    }
    return _Original_access(path, mode);
}

ssize_t Fake_readlink(const char* path, char* buf, size_t bufsiz) {
    std::string newPath = RedirectTable::GetInstance().ResolvePath(path);
    if (!newPath.empty()) return _Original_readlink(newPath.c_str(), buf, bufsiz);
    return _Original_readlink(path, buf, bufsiz);
}

int Fake_unlink(const char* path) {
    std::string newPath = RedirectTable::GetInstance().ResolvePath(path);
    if (!newPath.empty()) {
        LOGD("Intercepted unlink: %s -> %s", path, newPath.c_str());
        return _Original_unlink(newPath.c_str());
    }
    return _Original_unlink(path);
}

int Fake_unlinkat(int dirfd, const char* path, int flags) {
    std::string newPath = RedirectTable::GetInstance().ResolvePath(path);
    if (!newPath.empty()) {
        LOGD("Intercepted unlinkat: %s -> %s", path, newPath.c_str());
        return _Original_unlinkat(dirfd, newPath.c_str(), flags);
    }
    return _Original_unlinkat(dirfd, path, flags);
}

int Fake_rename(const char* oldPath, const char* newPath) {
    std::string redirectOld = RedirectTable::GetInstance().ResolvePath(oldPath);
    std::string redirectNew = RedirectTable::GetInstance().ResolvePath(newPath);
    const char* finalOld = redirectOld.empty() ? oldPath : redirectOld.c_str();
    const char* finalNew = redirectNew.empty() ? newPath : redirectNew.c_str();
    if (finalOld != oldPath || finalNew != newPath)
        LOGD("Intercepted rename: %s -> %s | %s -> %s", oldPath, finalOld, newPath, finalNew);
    return _Original_rename(finalOld, finalNew);
}

int Fake_renameat(int olddirfd, const char* oldPath, int newdirfd, const char* newPath) {
    std::string redirectOld = RedirectTable::GetInstance().ResolvePath(oldPath);
    std::string redirectNew = RedirectTable::GetInstance().ResolvePath(newPath);
    const char* finalOld = redirectOld.empty() ? oldPath : redirectOld.c_str();
    const char* finalNew = redirectNew.empty() ? newPath : redirectNew.c_str();
    if (finalOld != oldPath || finalNew != newPath)
        LOGD("Intercepted renameat: %s -> %s | %s -> %s", oldPath, finalOld, newPath, finalNew);
    return _Original_renameat(olddirfd, finalOld, newdirfd, finalNew);
}

int Fake_mkdir(const char* path, mode_t mode) {
    std::string newPath = RedirectTable::GetInstance().ResolvePath(path);
    if (!newPath.empty()) {
        LOGD("Intercepted mkdir: %s -> %s", path, newPath.c_str());
        return _Original_mkdir(newPath.c_str(), mode);
    }
    return _Original_mkdir(path, mode);
}

int Fake_mkdirat(int dirfd, const char* path, mode_t mode) {
    std::string newPath = RedirectTable::GetInstance().ResolvePath(path);
    if (!newPath.empty()) {
        LOGD("Intercepted mkdirat: %s -> %s", path, newPath.c_str());
        return _Original_mkdirat(dirfd, newPath.c_str(), mode);
    }
    return _Original_mkdirat(dirfd, path, mode);
}

int Fake_rmdir(const char* path) {
    std::string newPath = RedirectTable::GetInstance().ResolvePath(path);
    if (!newPath.empty()) {
        LOGD("Intercepted rmdir: %s -> %s", path, newPath.c_str());
        return _Original_rmdir(newPath.c_str());
    }
    return _Original_rmdir(path);
}

int Fake_truncate(const char* path, off_t len) {
    std::string newPath = RedirectTable::GetInstance().ResolvePath(path);
    if (!newPath.empty()) {
        LOGD("Intercepted truncate: %s -> %s", path, newPath.c_str());
        return _Original_truncate(newPath.c_str(), len);
    }
    return _Original_truncate(path, len);
}

int Fake_fstatat(int dirfd, const char* path, struct stat* buf, int flags) {
    if (path && path[0] != '\0') {
        std::string newPath = RedirectTable::GetInstance().ResolvePath(path);
        if (!newPath.empty()) return _Original_fstatat(dirfd, newPath.c_str(), buf, flags);
    }
    return _Original_fstatat(dirfd, path, buf, flags);
}

const char* Fake_getprogname() {
    if (!_GuestProcessName.empty()) return _GuestProcessName.c_str();
    return _Original_getprogname();
}

FILE* Fake_fopen(const char* path, const char* mode) {
    if (strstr(path, "/proc/self/cmdline")) {
        std::string newPath = RedirectTable::GetInstance().ResolvePath("/proc/self/cmdline");
        if (!newPath.empty()) return _Original_fopen(newPath.c_str(), mode);
    }
    std::string newPath = RedirectTable::GetInstance().ResolvePath(path);
    if (!newPath.empty()) return _Original_fopen(newPath.c_str(), mode);
    return _Original_fopen(path, mode);
}

// Intercept strcmp to bypass Viber's native "UNKNOWN" process check
int Fake_strcmp(const char* s1, const char* s2) {
    if (s1 && s2) {
        if ((strcmp(s1, "UNKNOWN") == 0 && strcmp(s2, "com.viber.voip") == 0) ||
            (strcmp(s1, "com.viber.voip") == 0 && strcmp(s2, "UNKNOWN") == 0)) {
            return 0; // Pretend they are identical
        }
    }
    return _Original_strcmp(s1, s2);
}

int Fake_stat64(const char* path, struct stat64* buf) {
    std::string newPath = RedirectTable::GetInstance().ResolvePath(path);
    if (!newPath.empty()) return _Original_stat64(newPath.c_str(), buf);
    return _Original_stat64(path, buf);
}

int Fake_open64(const char* path, int flags, ...) {
    mode_t mode = 0;
    if (flags & O_CREAT) {
        va_list args; va_start(args, flags); mode = va_arg(args, mode_t); va_end(args);
    }
    std::string newPath = RedirectTable::GetInstance().ResolvePath(path);
    if (!newPath.empty()) {
        LOGD("Intercepted open64: %s -> %s", path, newPath.c_str());
        return _Original_open64(newPath.c_str(), flags, mode);
    }
    return _Original_open64(path, flags, mode);
}

int Fake_openat64(int dirfd, const char* path, int flags, ...) {
    mode_t mode = 0;
    if (flags & O_CREAT) {
        va_list args; va_start(args, flags); mode = va_arg(args, mode_t); va_end(args);
    }
    std::string newPath = RedirectTable::GetInstance().ResolvePath(path);
    if (!newPath.empty()) return _Original_openat64(dirfd, newPath.c_str(), flags, mode);
    return _Original_openat64(dirfd, path, flags, mode);
}

int Fake_faccessat(int dirfd, const char* path, int mode, int flags) {
    std::string newPath = RedirectTable::GetInstance().ResolvePath(path);
    if (!newPath.empty()) return _Original_faccessat(dirfd, newPath.c_str(), mode, flags);
    return _Original_faccessat(dirfd, path, mode, flags);
}

int Fake_lstat(const char* path, struct stat* buf) {
    std::string newPath = RedirectTable::GetInstance().ResolvePath(path);
    if (!newPath.empty()) return _Original_lstat(newPath.c_str(), buf);
    return _Original_lstat(path, buf);
}

// Forward declare the per-library installer
static void InstallHooksOnLibrary(const char* name);

void* Fake_dlopen(const char* filename, int flags) {
    void* handle = _Original_dlopen(filename, flags);
    if (handle && filename && strstr(filename, "libsqliteX.so")) {
        LOGD("dlopen: new lib loaded %s, applying hooks", filename);
        InstallHooksOnLibrary(filename);
    }
    return handle;
}

void* Fake_android_dlopen_ext(const char* filename, int flags, const void* extinfo) {
    void* handle = _Original_android_dlopen_ext(filename, flags, extinfo);
    if (handle && filename && strstr(filename, "libsqliteX.so")) {
        LOGD("android_dlopen_ext: new lib loaded %s, applying hooks", filename);
        InstallHooksOnLibrary(filename);
    }
    return handle;
}

extern "C" void SetGuestProcessName(const char* name) {
    _GuestProcessName = name;
}

static void InstallHooksOnLibrary(const char* name) {
    if (name == nullptr || strlen(name) == 0) return;
    if (strstr(name, "/system/") || strstr(name, "/apex/") ||
        strstr(name, "/vendor/") || strstr(name, "/product/")) return;
    if (strstr(name, "libdualcore.so")) return;

    void** gotAddress = nullptr;

    gotAddress = GetGotAddress(name, "__open_2");
    if (gotAddress && !_Original__open_2) HookGotEntry(gotAddress, (void*)Fake__open_2, (void**)&_Original__open_2);

    gotAddress = GetGotAddress(name, "openat");
    if (gotAddress && !_Original_openat) HookGotEntry(gotAddress, (void*)Fake_openat, (void**)&_Original_openat);

    gotAddress = GetGotAddress(name, "stat");
    if (gotAddress && !_Original_stat) HookGotEntry(gotAddress, (void*)Fake_stat, (void**)&_Original_stat);

    gotAddress = GetGotAddress(name, "access");
    if (gotAddress && !_Original_access) HookGotEntry(gotAddress, (void*)Fake_access, (void**)&_Original_access);

    gotAddress = GetGotAddress(name, "readlink");
    if (gotAddress && !_Original_readlink) HookGotEntry(gotAddress, (void*)Fake_readlink, (void**)&_Original_readlink);

    gotAddress = GetGotAddress(name, "unlink");
    if (gotAddress && !_Original_unlink) HookGotEntry(gotAddress, (void*)Fake_unlink, (void**)&_Original_unlink);

    gotAddress = GetGotAddress(name, "unlinkat");
    if (gotAddress && !_Original_unlinkat) HookGotEntry(gotAddress, (void*)Fake_unlinkat, (void**)&_Original_unlinkat);

    gotAddress = GetGotAddress(name, "rename");
    if (gotAddress && !_Original_rename) HookGotEntry(gotAddress, (void*)Fake_rename, (void**)&_Original_rename);

    gotAddress = GetGotAddress(name, "renameat");
    if (gotAddress && !_Original_renameat) HookGotEntry(gotAddress, (void*)Fake_renameat, (void**)&_Original_renameat);

    gotAddress = GetGotAddress(name, "mkdir");
    if (gotAddress && !_Original_mkdir) HookGotEntry(gotAddress, (void*)Fake_mkdir, (void**)&_Original_mkdir);

    gotAddress = GetGotAddress(name, "mkdirat");
    if (gotAddress && !_Original_mkdirat) HookGotEntry(gotAddress, (void*)Fake_mkdirat, (void**)&_Original_mkdirat);

    gotAddress = GetGotAddress(name, "rmdir");
    if (gotAddress && !_Original_rmdir) HookGotEntry(gotAddress, (void*)Fake_rmdir, (void**)&_Original_rmdir);

    gotAddress = GetGotAddress(name, "truncate");
    if (gotAddress && !_Original_truncate) HookGotEntry(gotAddress, (void*)Fake_truncate, (void**)&_Original_truncate);

    gotAddress = GetGotAddress(name, "fstatat");
    if (gotAddress && !_Original_fstatat) HookGotEntry(gotAddress, (void*)Fake_fstatat, (void**)&_Original_fstatat);

    gotAddress = GetGotAddress(name, "fopen");
    if (gotAddress && !_Original_fopen) HookGotEntry(gotAddress, (void*)Fake_fopen, (void**)&_Original_fopen);

    gotAddress = GetGotAddress(name, "strcmp");
    if (gotAddress && !_Original_strcmp) HookGotEntry(gotAddress, (void*)Fake_strcmp, (void**)&_Original_strcmp);

    gotAddress = GetGotAddress(name, "stat64");
    if (gotAddress && !_Original_stat64) HookGotEntry(gotAddress, (void*)Fake_stat64, (void**)&_Original_stat64);

    gotAddress = GetGotAddress(name, "open64");
    if (gotAddress && !_Original_open64) HookGotEntry(gotAddress, (void*)Fake_open64, (void**)&_Original_open64);

    gotAddress = GetGotAddress(name, "openat64");
    if (gotAddress && !_Original_openat64) HookGotEntry(gotAddress, (void*)Fake_openat64, (void**)&_Original_openat64);

    gotAddress = GetGotAddress(name, "faccessat");
    if (gotAddress && !_Original_faccessat) HookGotEntry(gotAddress, (void*)Fake_faccessat, (void**)&_Original_faccessat);

    gotAddress = GetGotAddress(name, "lstat");
    if (gotAddress && !_Original_lstat) HookGotEntry(gotAddress, (void*)Fake_lstat, (void**)&_Original_lstat);
}

static int HookLibraryCallback(struct dl_phdr_info* info, size_t size, void* data) {
    InstallHooksOnLibrary(info->dlpi_name);
    return 0;
}

static void InstallInlineHooks() {
    void* libc = dlopen("libc.so", RTLD_NOW);
    if (!libc) return;

    void* open2 = dlsym(libc, "__open_2");
    if (open2 && !_Original__open_2) {
        InstallInlineHook(open2, (void*)Fake__open_2, (void**)&_Original__open_2);
        LOGD("Inline hook: __open_2");
    }

    void* openat = dlsym(libc, "openat");
    if (openat && !_Original_openat) {
        InstallInlineHook(openat, (void*)Fake_openat, (void**)&_Original_openat);
        LOGD("Inline hook: openat");
    }

    void* open64 = dlsym(libc, "open64");
    if (open64 && !_Original_open64) {
        InstallInlineHook(open64, (void*)Fake_open64, (void**)&_Original_open64);
        LOGD("Inline hook: open64");
    }

    void* openat64 = dlsym(libc, "openat64");
    if (openat64 && !_Original_openat64) {
        InstallInlineHook(openat64, (void*)Fake_openat64, (void**)&_Original_openat64);
        LOGD("Inline hook: openat64");
    }

    void* stat_fn = dlsym(libc, "stat");
    if (stat_fn && !_Original_stat) {
        InstallInlineHook(stat_fn, (void*)Fake_stat, (void**)&_Original_stat);
        LOGD("Inline hook: stat");
    }

    void* stat64_fn = dlsym(libc, "stat64");
    if (stat64_fn && !_Original_stat64) {
        InstallInlineHook(stat64_fn, (void*)Fake_stat64, (void**)&_Original_stat64);
        LOGD("Inline hook: stat64");
    }

    void* access_fn = dlsym(libc, "access");
    if (access_fn && !_Original_access) {
        InstallInlineHook(access_fn, (void*)Fake_access, (void**)&_Original_access);
        LOGD("Inline hook: access");
    }

    void* faccessat_fn = dlsym(libc, "faccessat");
    if (faccessat_fn && !_Original_faccessat) {
        InstallInlineHook(faccessat_fn, (void*)Fake_faccessat, (void**)&_Original_faccessat);
        LOGD("Inline hook: faccessat");
    }

    void* readlink_fn = dlsym(libc, "readlink");
    if (readlink_fn && !_Original_readlink) {
        InstallInlineHook(readlink_fn, (void*)Fake_readlink, (void**)&_Original_readlink);
        LOGD("Inline hook: readlink");
    }

    void* lstat_fn = dlsym(libc, "lstat");
    if (lstat_fn && !_Original_lstat) {
        InstallInlineHook(lstat_fn, (void*)Fake_lstat, (void**)&_Original_lstat);
        LOGD("Inline hook: lstat");
    }

    // ---- Path-mutating family: covers late-loaded libs like libsqliteX.so ----
    void* unlink_fn = dlsym(libc, "unlink");
    if (unlink_fn && !_Original_unlink) {
        InstallInlineHook(unlink_fn, (void*)Fake_unlink, (void**)&_Original_unlink);
        LOGD("Inline hook: unlink");
    }

    void* unlinkat_fn = dlsym(libc, "unlinkat");
    if (unlinkat_fn && !_Original_unlinkat) {
        InstallInlineHook(unlinkat_fn, (void*)Fake_unlinkat, (void**)&_Original_unlinkat);
        LOGD("Inline hook: unlinkat");
    }

    void* rename_fn = dlsym(libc, "rename");
    if (rename_fn && !_Original_rename) {
        InstallInlineHook(rename_fn, (void*)Fake_rename, (void**)&_Original_rename);
        LOGD("Inline hook: rename");
    }

    void* renameat_fn = dlsym(libc, "renameat");
    if (renameat_fn && !_Original_renameat) {
        InstallInlineHook(renameat_fn, (void*)Fake_renameat, (void**)&_Original_renameat);
        LOGD("Inline hook: renameat");
    }

    void* mkdir_fn = dlsym(libc, "mkdir");
    if (mkdir_fn && !_Original_mkdir) {
        InstallInlineHook(mkdir_fn, (void*)Fake_mkdir, (void**)&_Original_mkdir);
        LOGD("Inline hook: mkdir");
    }

    void* mkdirat_fn = dlsym(libc, "mkdirat");
    if (mkdirat_fn && !_Original_mkdirat) {
        InstallInlineHook(mkdirat_fn, (void*)Fake_mkdirat, (void**)&_Original_mkdirat);
        LOGD("Inline hook: mkdirat");
    }

    void* rmdir_fn = dlsym(libc, "rmdir");
    if (rmdir_fn && !_Original_rmdir) {
        InstallInlineHook(rmdir_fn, (void*)Fake_rmdir, (void**)&_Original_rmdir);
        LOGD("Inline hook: rmdir");
    }

    void* truncate_fn = dlsym(libc, "truncate");
    if (truncate_fn && !_Original_truncate) {
        InstallInlineHook(truncate_fn, (void*)Fake_truncate, (void**)&_Original_truncate);
        LOGD("Inline hook: truncate");
    }

    dlclose(libc);
}

void InstallHooks() {
    dl_iterate_phdr(HookLibraryCallback, nullptr);
    InstallInlineHooks();
    LOGD("Native hooks installed (PLT + inline).");
}
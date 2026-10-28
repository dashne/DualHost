#pragma once
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>
#include <stdio.h>

// Existing hooks
extern int (*_Original__open_2)(const char*, int);
extern int (*_Original_openat)(int, const char*, int, ...);
extern int (*_Original_stat)(const char*, struct stat*);
extern int (*_Original_access)(const char*, int);
extern ssize_t (*_Original_readlink)(const char*, char*, size_t);

// Filesystem mutation hooks
extern int (*_Original_unlink)(const char*);
extern int (*_Original_unlinkat)(int, const char*, int);
extern int (*_Original_rename)(const char*, const char*);
extern int (*_Original_renameat)(int, const char*, int, const char*);
extern int (*_Original_mkdir)(const char*, mode_t);
extern int (*_Original_mkdirat)(int, const char*, mode_t);
extern int (*_Original_fstatat)(int, const char*, struct stat*, int);
extern int (*_Original_rmdir)(const char*);
extern int (*_Original_truncate)(const char*, off_t);

// The initialization function
void InstallHooks();

extern const char* (*_Original_getprogname)();
extern FILE* (*_Original_fopen)(const char*, const char*);
extern "C" void SetGuestProcessName(const char* name);
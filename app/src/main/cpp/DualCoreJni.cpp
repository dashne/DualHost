#include <jni.h>
#include <string>
#include <sys/system_properties.h>
#include <android/log.h>
#include <dlfcn.h>
#include <sys/mman.h>
#include <unistd.h>
#include <cstdio>
#include <cstring>
#include "Hooks.h"
#include "RedirectTable.h"
#include "PltResolver.h"

#define LOG_TAG "DualCore"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

JavaVM* _GlobalJavaVM = nullptr;
jclass _NativeBridgeClass = nullptr;
jmethodID _GetVirtualUidMethod = nullptr;
std::string _FakeProcessName = "";

// ============================================================
// ArtMethod Hooker (replaces Binder.getCallingUid, Process.myProcessName, etc.)
// ============================================================
bool HookJavaMethod(JNIEnv* env, jobject methodObj, void* newFunc, void** originalFunc) {
    jmethodID methodId = env->FromReflectedMethod(methodObj);
    if (!methodId) return false;
    void* artMethod = (void*)methodId;
    void** entryPoint = nullptr;

    for (int offset = 24; offset <= 48; offset += 8) {
        void* val = *(void**)((uint8_t*)artMethod + offset);
        if (val) {
            Dl_info info;
            if (dladdr(val, &info) && info.dli_fname) {
                if (strstr(info.dli_fname, "libandroid_runtime.so") || strstr(info.dli_fname, "libart.so")) {
                    entryPoint = (void**)((uint8_t*)artMethod + offset);
                    break;
                }
            }
        }
    }
    if (!entryPoint) return false;

    if (originalFunc) *originalFunc = *entryPoint;
    long pageSize = sysconf(_SC_PAGESIZE);
    uintptr_t pageStart = (uintptr_t)entryPoint & ~(pageSize - 1);
    if (mprotect((void*)pageStart, pageSize, PROT_READ | PROT_WRITE) != 0) return false;

    *entryPoint = newFunc;
    mprotect((void*)pageStart, pageSize, PROT_READ);
    __builtin___clear_cache((char*)artMethod, (char*)artMethod + 64);
    return true;
}

// Fake Process.myProcessName() that returns the guest package name
jstring FakeGetMyProcessName(JNIEnv* env, jclass /*clazz*/) {
    if (!_FakeProcessName.empty()) {
        return env->NewStringUTF(_FakeProcessName.c_str());
    }
    return env->NewStringUTF("com.unknown.app");
}

// ============================================================
// JNI lifecycle
// ============================================================
extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    _GlobalJavaVM = vm;
    return JNI_VERSION_1_6;
}

int GetDynamicVirtualUid() {
    if (_GlobalJavaVM && _NativeBridgeClass && _GetVirtualUidMethod) {
        JNIEnv* env = nullptr;
        _GlobalJavaVM->AttachCurrentThread(&env, nullptr);
        jint uid = env->CallStaticIntMethod(_NativeBridgeClass, _GetVirtualUidMethod);
        _GlobalJavaVM->DetachCurrentThread();
        return uid;
    }
    return 10000;
}

// ============================================================
// Public JNI functions called from NativeBridge.kt
// ============================================================
extern "C" JNIEXPORT void JNICALL
Java_com_ims134_dualhost_NativeBridge_init(JNIEnv* env, jobject /*this*/) {
    jclass clazz = env->FindClass("com/ims134/dualhost/NativeBridge");
    _NativeBridgeClass = (jclass)env->NewGlobalRef(clazz);
    _GetVirtualUidMethod = env->GetStaticMethodID(clazz, "getVirtualUid", "()I");
}

extern "C" JNIEXPORT void JNICALL
Java_com_ims134_dualhost_NativeBridge_AddRule(
        JNIEnv* env, jobject /*this*/, jstring guestPath, jstring hostPath) {
    const char* g = env->GetStringUTFChars(guestPath, nullptr);
    const char* h = env->GetStringUTFChars(hostPath, nullptr);
    RedirectTable::GetInstance().AddRule(g, h);
    env->ReleaseStringUTFChars(guestPath, g);
    env->ReleaseStringUTFChars(hostPath, h);
}

extern "C" JNIEXPORT void JNICALL
Java_com_ims134_dualhost_NativeBridge_hookMethod(
        JNIEnv* env, jobject /*this*/, jobject methodObj) {
    bool ok = HookJavaMethod(env, methodObj, (void*)FakeGetMyProcessName, nullptr);
    if (ok) LOGD("Hooked Process.myProcessName via ArtMethod");
    else    LOGE("Failed to hook Process.myProcessName");
}

extern "C" JNIEXPORT void JNICALL
Java_com_ims134_dualhost_NativeBridge_InstallHooks(
        JNIEnv* env, jobject /*this*/, jstring guestPkgStr) {
    const char* guestPkg = env->GetStringUTFChars(guestPkgStr, nullptr);
    _FakeProcessName = guestPkg;
    SetGuestProcessName(guestPkg);
    env->ReleaseStringUTFChars(guestPkgStr, guestPkg);

    char cmdline[256] = {0};
    FILE* f = fopen("/proc/self/cmdline", "r");
    if (f) {
        fread(cmdline, 1, sizeof(cmdline) - 1, f);
        fclose(f);
    }
    if (strstr(cmdline, ":p") == nullptr && strstr(cmdline, "com.viber.voip") == nullptr) {
        LOGD("Not in guest process, skipping native hooks.");
        return;
    }
    InstallHooks();
    LOGD("Native hooks installed in guest process.");
}

extern "C" JNIEXPORT void JNICALL
Java_com_ims134_dualhost_NativeBridge_forceSetString(
        JNIEnv* env, jobject /*this*/, jclass clazz, jobject fieldObj, jstring value) {
    jfieldID fid = env->FromReflectedField(fieldObj);
    if (fid != nullptr) {
        env->SetStaticObjectField(clazz, fid, value);
        LOGD("JNI forceSetString: SUCCESS");
    } else {
        env->ExceptionClear();
        LOGE("JNI forceSetString: FAILED");
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ims134_dualhost_NativeBridge_setStaticStringByName(
        JNIEnv* env, jobject /*this*/, jclass clazz, jstring name, jstring value) {
    const char* n = env->GetStringUTFChars(name, nullptr);
    jfieldID fid = env->GetStaticFieldID(clazz, n, "Ljava/lang/String;");
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (!fid) {
        LOGE("setStaticStringByName: field %s not found", n);
        env->ReleaseStringUTFChars(name, n);
        return JNI_FALSE;
    }
    env->SetStaticObjectField(clazz, fid, value);
    LOGD("setStaticStringByName: %s set", n);
    env->ReleaseStringUTFChars(name, n);
    return JNI_TRUE;
}

// Feature 7.6 (adopted from HookEngine's PltResolver). Exposes
// PltResolveForSymbol to Kotlin as NativeBridge.pltResolve(lib, symbol).
// Returns 0 (as a Long) when the library/symbol isn't found, matching
// PltResolveForSymbol's own 0-on-failure contract.
extern "C" JNIEXPORT jlong JNICALL
Java_com_ims134_dualhost_NativeBridge_pltResolve(
        JNIEnv* env, jobject /*this*/, jstring libStr, jstring symbolStr) {
    const char* lib = env->GetStringUTFChars(libStr, nullptr);
    const char* sym = env->GetStringUTFChars(symbolStr, nullptr);
    uint64_t addr = PltResolveForSymbol(lib, sym);
    LOGD("pltResolve(%s, %s) = 0x%llx", lib, sym, (unsigned long long)addr);
    env->ReleaseStringUTFChars(libStr, lib);
    env->ReleaseStringUTFChars(symbolStr, sym);
    return (jlong)addr;
}

//
// Created by ibrahim on 11/25/2026.
//

#include <android/log.h>
#include <future>
#include <thread>
#include <thread>
#include "Reflection.h"

#define VA_TAG "NativeReflection"
#ifdef __ANDROID__
#define ATTACH_ARG(e) (e)
#else
#define ATTACH_ARG(e) ((void **) (e))
#endif

//--------------------------------------------------
template<class Fn>
jobject onNativeThread(Fn fn) {
    auto promise = std::make_shared<std::promise<jobject>>();
    std::future<jobject> future = promise->get_future();

    std::thread([promise, fn]() {

        jobject global = nullptr;
        JNIEnv *env = nullptr;
        auto vm = Reflection::p_vm;

        if (vm->AttachCurrentThread(ATTACH_ARG(&env), nullptr) == JNI_OK) {
            jobject local = fn(env);
            if (env->ExceptionCheck()) {
                env->ExceptionDescribe();
                env->ExceptionClear();
                local = nullptr;
            }
            if (local != nullptr) {
                global = env->NewGlobalRef(local);
                env->DeleteLocalRef(local);
            }
            vm->DetachCurrentThread();
        }
        promise->set_value(global);
    }).detach();

    jobject global = future.get();
    return global;
}

static jobject finish(JNIEnv *env, jobject global) {
    if (global == nullptr) return nullptr;
    jobject local = env->NewLocalRef(global);
    env->DeleteGlobalRef(global);
    return local;
}
//--------------------------------------------------

jclass Reflection::g_jclass = nullptr;
JavaVM *Reflection::p_vm = nullptr;

jobject
Reflection::getDeclaredMethod(JNIEnv *env, jclass, jobject cls, jstring name, jobjectArray params) {
    return callClassMethod(env, cls, name, params, "getDeclaredMethod", "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;", true);
}

jobject Reflection::getMethod(JNIEnv *env, jclass, jobject cls, jstring name, jobjectArray params) {
    return callClassMethod(env, cls, name, params, "getMethod", "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;", true);
}

jobject Reflection::getDeclaredField(JNIEnv *env, jclass, jobject cls, jstring name) {
    return callClassMethod(env, cls, name, nullptr, "getDeclaredField", "(Ljava/lang/String;)Ljava/lang/reflect/Field;", false);
}

void Reflection::Register(JavaVM *vm, const char *kNativeEngineClass,
                          std::vector<JNINativeMethod> &kMethods) {
    JNIEnv *env = nullptr;
    p_vm = vm;

    if (vm->GetEnv((void **) &env, JNI_VERSION_1_6) != JNI_OK) return;

    __android_log_print(ANDROID_LOG_INFO, VA_TAG, "Reflection::Register::Start ");
    if(kNativeEngineClass == nullptr)
    {
        __android_log_print(ANDROID_LOG_ERROR, VA_TAG, "kNativeEngineClass is null.");
        return;
    }
    jclass cls = env->FindClass(kNativeEngineClass);
    if (cls == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, VA_TAG, "Error: Unable to find the NativeEngine class.");
        return;
    }
    __android_log_print(ANDROID_LOG_INFO, VA_TAG, "Reflection::Register::after if (cls == nullptr) {");
    g_jclass = (jclass) env->NewGlobalRef(cls);
    // one by one, so Stub method that is missing in Kotlin is logged instead of aborting the app

    __android_log_print(ANDROID_LOG_INFO, VA_TAG, "Reflection::Register::after g_jclass = (jclass) env->NewGlobalRef(cls);");
    for (const JNINativeMethod &method : kMethods) {
        if (env->RegisterNatives(cls, &method, 1) < 0) {
            env->ExceptionClear();
            __android_log_print(ANDROID_LOG_ERROR, VA_TAG, "Unable to register the native method %s %s",
                                method.name, method.signature);
        }
    }
    // [IDA-FIX-BEGIN #23] IDA: the local ref of the NativeEngine class is deleted after the global ref is taken
    env->DeleteLocalRef(cls);
    // [IDA-FIX-END #23]
    __android_log_print(ANDROID_LOG_INFO, VA_TAG, "Reflection::Register::End ");
}
void Reflection::UnRegister(JavaVM *vm) {
    JNIEnv *env;
    if (GetEnv(vm, &env) && g_jclass != nullptr) {
        env->DeleteGlobalRef(g_jclass);
        g_jclass = nullptr;
    }
}

bool Reflection::GetEnv(JavaVM *vm, JNIEnv **env) {
    return vm->GetEnv((void **) env, JNI_VERSION_1_6) == JNI_OK;
}

jobject Reflection::callClassMethod(JNIEnv *env,
                                    jobject self,
                                    jstring name,
                                    jobjectArray params,
                                    const char *method,
                                    const char *sig,
                                    bool withParams){
    jobject g_self = env->NewGlobalRef(self);
    jobject g_name = env->NewGlobalRef(name);
    jobject g_params = params != nullptr ? env->NewGlobalRef(params) : nullptr;
    jobject out = onNativeThread([=](JNIEnv *t) -> jobject {
        jclass classCls = t->GetObjectClass(g_self);
        jmethodID mid = t->GetMethodID(classCls, method, sig);
        t->DeleteLocalRef(classCls);
        if (mid == nullptr) return nullptr;
        return withParams ? t->CallObjectMethod(g_self, mid, g_name, g_params) : t->CallObjectMethod(g_self, mid, g_name);
    });
    // [IDA-FIX-BEGIN #27] IDA: after the worker finished, Stub pending exception of the calling thread is described and cleared
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
    // [IDA-FIX-END #27]
    env->DeleteGlobalRef(g_self);
    env->DeleteGlobalRef(g_name);
    if (g_params != nullptr) env->DeleteGlobalRef(g_params);
    return finish(env, out);
}

//
// Created by ibrahim on 11/25/2026.
//

#ifndef DUALHOST_REFLECTION_H
#define DUALHOST_REFLECTION_H


#include <jni.h>
#include <vector>

class Reflection {
public:
    static bool GetEnv(JavaVM *vm, JNIEnv** env);
    static jobject callClassMethod(JNIEnv *env, jobject self, jstring name, jobjectArray params, const char *method, const char *sig, bool withParams);
    static jobject getDeclaredMethod(JNIEnv *env, jclass, jobject cls, jstring name, jobjectArray params);
    static jobject getMethod(JNIEnv *env, jclass, jobject cls, jstring name, jobjectArray params);
    static jobject getDeclaredField(JNIEnv *env, jclass, jobject cls, jstring name);
    static void Register(JavaVM *vm,const char *kNativeEngineClass, std::vector<JNINativeMethod>& kMethods);
    static void UnRegister(JavaVM *vm);


private:
    static jclass g_jclass;
    static JavaVM *p_vm;

    template<class Fn>
    friend jobject onNativeThread(Fn fn);
};



#endif //DUALHOST_REFLECTION_H

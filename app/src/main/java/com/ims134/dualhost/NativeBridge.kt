package com.ims134.dualhost

import android.os.Build
import android.util.Log
import java.lang.reflect.Field
import java.lang.reflect.Method



object NativeBridge {
    private var _CurrentVirtualUid: Int = 10000
    private const val TAG = "NativeBridge"

    init {
        try {
            System.loadLibrary("dualcore")
            exemptHiddenApis()
            Log.d(TAG,"exemptHiddenApis is called .................................................")
        } catch (e: Throwable) {
            Log.e("DualHost", "Failed to load native dualcore: ${e.message}")
        }
    }

    @JvmStatic
    fun getVirtualUid(): Int = _CurrentVirtualUid

    fun setVirtualUid(uid: Int) {
        _CurrentVirtualUid = uid
    }

    external fun init()
    external fun AddRule(guestPath: String, hostPath: String)
    external fun InstallHooks(guestPkg: String)
    external fun forceSetString(clazz: Class<*>, field: java.lang.reflect.Field, value: String)

    external fun hookMethod(method: java.lang.reflect.Method)

    external fun setStaticStringByName(clazz: Class<*>, name: String, value: String): Boolean

    // Feature 7.6 adopted from HookEngine's PltResolver. Resolves the PLT
    // call-site address for `symbol` as imported by any loaded library whose
    // soname contains `lib`. Returns 0 if not found. Exposed for native-layer
    // diagnostics and any future hook-install path that needs the PLT thunk
    // address rather than the GOT slot address (GetGotAddress already covers
    // the GOT slot case used by HookGotEntry/HookGotEntryEx).
    external fun pltResolve(lib: String, symbol: String): Long

    @JvmStatic external fun nativeGetDeclaredField(obj: Any, name: String): Field?
    @JvmStatic external fun nativeGetDeclaredMethod(obj: Any, name: String, params: Array<Class<*>>?): Method?
    @JvmStatic external fun nativeGetMethod(obj: Any, name: String, params: Array<Class<*>>?): Method?

    fun getMethod(obj: Any, name: String, params: Array<Class<*>>?): Method?
    {
        if(Build.VERSION.SDK_INT >= 33)
            return nativeGetMethod(obj,name,params)
        return try {
            val clazz = when (obj) {
                is Class<*> -> obj
                else -> obj.javaClass
            }
            clazz.getMethod(name, *(params ?: emptyArray()))
        } catch (e: Exception) {
            null
        }
    }

    fun getDeclaredMethod(obj: Any, name: String, params: Array<Class<*>>?): Method?
    {
        if (Build.VERSION.SDK_INT >= 33)
            return nativeGetDeclaredMethod(obj, name, params)
        return try {
            val clazz = when (obj) {
                is Class<*> -> obj
                else -> obj.javaClass
            }
            clazz.getDeclaredMethod(name, *(params ?: emptyArray()))
        } catch (e: Exception) {
            null
        }
    }

    fun getDeclaredField(obj: Any, name: String): Field?
    {
        if (Build.VERSION.SDK_INT >= 33)
            return nativeGetDeclaredField(obj, name)
        return try {
            val clazz = when (obj) {
                is Class<*> -> obj
                else -> obj.javaClass
            }
            clazz.getDeclaredField(name)
        } catch (e: Exception) {
            null
        }
    }

    @JvmStatic
    fun exemptHiddenApis() {
        try {
            val m = getMethod(Class.forName("dalvik.system.VMRuntime"), "getRuntime", arrayOf())
            if (m == null)
            {
                Log.d(TAG, "xxxxxxxxxxxxxx---------------------$TAG::getMethod failed on getRuntime")
            } else
            {
                m.isAccessible = true
                val objInvoke = m.invoke(null)
                if (objInvoke != null)
                {
                    val method = getMethod(objInvoke.javaClass, "setHiddenApiExemptions", arrayOf(Array<String>::class.java))
                    if (method == null)
                    {
                        Log.d(TAG, "xxxxxxxxxxxxxx---------------------$TAG::getMethod failed on setHiddenApiExemptions")
                    } else
                    {
                        method.isAccessible = true
                        method.invoke(objInvoke, arrayOf("L"))
                        Log.d(TAG,"xxxxxxxxxxxxxx---------------------$TAG::setHiddenApiExemptions is done")
                    }
                }
            }
        } catch (e: Throwable) {
            android.util.Log.d(TAG, "xxxxxxxxxxxxxx---------------------$TAG::ex: $e")
        }
    }
}
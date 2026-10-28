package com.ims134.dualhost

import android.util.Log

object NativeBridge {
    private var _CurrentVirtualUid: Int = 10000

    init {
        try {
            System.loadLibrary("dualcore")
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
}
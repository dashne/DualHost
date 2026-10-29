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

    // Feature 7.6 adopted from HookEngine's PltResolver. Resolves the PLT
    // call-site address for `symbol` as imported by any loaded library whose
    // soname contains `lib`. Returns 0 if not found. Exposed for native-layer
    // diagnostics and any future hook-install path that needs the PLT thunk
    // address rather than the GOT slot address (GetGotAddress already covers
    // the GOT slot case used by HookGotEntry/HookGotEntryEx).
    external fun pltResolve(lib: String, symbol: String): Long
}

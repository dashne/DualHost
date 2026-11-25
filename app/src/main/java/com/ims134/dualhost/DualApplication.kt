package com.ims134.dualhost

import android.app.Application
import android.content.Context
import android.util.Log
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

class DualApplication : Application() {
    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)

        try {
            val forName = Class::class.java.getDeclaredMethod("forName", String::class.java)
            val getDeclaredMethod = Class::class.java.getDeclaredMethod(
                "getDeclaredMethod",
                String::class.java,
                arrayOf<Class<*>>().javaClass
            )
            val vmRuntimeClass = forName.invoke(null, "dalvik.system.VMRuntime") as Class<*>
            val getRuntime = getDeclaredMethod.invoke(vmRuntimeClass, "getRuntime", null) as Method

            val stringArrayClass = Class.forName("[Ljava.lang.String;")
            val setHiddenApiExemptions = getDeclaredMethod.invoke(
                vmRuntimeClass,
                "setHiddenApiExemptions",
                arrayOf(stringArrayClass)
            ) as Method

            val vmRuntime = getRuntime.invoke(null)
            setHiddenApiExemptions.invoke(vmRuntime, arrayOf("L") as Any)
            Log.d("DualHost", "Hidden API bypassed successfully!")
        } catch (e: InvocationTargetException) {
            Log.e("DualHost", "Hidden API bypass failed: ${e.cause ?: e}")
        } catch (e: Throwable) {
            Log.e("DualHost", "Hidden API bypass failed: ${e}")
        }

        HookInstaller.install()
    }

    override fun onCreate() {
        super.onCreate()
        try {
            val uiManager = getSystemService(Context.UI_MODE_SERVICE) as? android.app.UiModeManager
            val isDark = (uiManager?.nightMode == android.app.UiModeManager.MODE_NIGHT_YES) ||
                         ((resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES)
            val mode = if (isDark) androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES else androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
            androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(mode)
        } catch (_: Throwable) {}
    }
}
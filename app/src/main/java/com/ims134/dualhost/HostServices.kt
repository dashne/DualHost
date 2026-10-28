package com.ims134.dualhost

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.IBinder
import java.io.File
import java.util.concurrent.ConcurrentHashMap

open class BaseHostService : Service() {

    companion object {
        // The guest service most recently started in this process (one slot per process)
        @Volatile var lastCls: String? = null
    }

    private val live = ConcurrentHashMap<String, Service>()

    private fun copyField(name: String, to: Service, value: Any?) {
        try {
            val f = Service::class.java.getDeclaredField(name)
            f.isAccessible = true
            f.set(to, value ?: f.get(this))
        } catch (e: Throwable) {
            FileLog.d("HOSTSVC field " + name + " FAILED: " + e)
        }
    }

    private fun clean(intent: Intent, cls: String): Intent {
        val i = Intent(intent)
        i.component = ComponentName("com.ims134.dualhost", cls)
        i.removeExtra("dh_class"); i.removeExtra("dh_apk")
        i.removeExtra("dh_pkg"); i.removeExtra("dh_vuid")
        i.removeExtra("dh_clone")
        return i
    }

    // Finds or creates the guest service named in the intent
    private fun guest(intent: Intent?): Service? {
        val cls = intent?.getStringExtra("dh_class") ?: return null
        val apk = intent.getStringExtra("dh_apk") ?: return null
        val pkg = intent.getStringExtra("dh_pkg") ?: return null
        val vuid = intent.getIntExtra("dh_vuid", 0)
        val clone = intent.getIntExtra("dh_clone", vuid)
        FileLog.d("HOSTSVC clone=" + clone + " " + cls)
        if (!CloneStore.exists(applicationContext, clone, pkg)) {
            FileLog.d("HOSTSVC ignored, clone " + clone + " was removed")
            return null
        }
        live[cls]?.let { return it }
        var env = GuestEnvs.map[apk]
        if (env == null && File(apk).exists()) env = HostInstrumentation.current?.ensureEnv(apk, vuid, pkg, clone)
        if (env == null) { FileLog.d("HOSTSVC no guest environment"); return null }
        val svc = env.loader.loadClass(cls).getDeclaredConstructor().newInstance() as Service
        val attach = ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
        attach.isAccessible = true
        attach.invoke(svc, env.application)
        copyField("mThread", svc, null)
        copyField("mClassName", svc, null)
        copyField("mToken", svc, null)
        copyField("mActivityManager", svc, null)
        copyField("mApplication", svc, env.application)
        try {
            svc.onCreate()
        } catch (e: Throwable) {
            FileLog.d("HOSTSVC guest " + cls + " onCreate FAILED: " + e + " cause=" + e.cause)
            throw e
        }
        live[cls] = svc
        FileLog.d("HOSTSVC guest " + cls + " onCreate done")
        return svc
    }

    override fun onBind(intent: Intent?): IBinder? {
        val cls = intent?.getStringExtra("dh_class") ?: return null
        return try {
            val svc = guest(intent) ?: return null
            val b = svc.onBind(clean(intent, cls))
            FileLog.d("HOSTSVC guest " + cls + " onBind returned " + (b?.javaClass?.simpleName ?: "null"))
            b
        } catch (e: Throwable) {
            FileLog.d("HOSTSVC onBind failed for " + cls + ": " + e + " cause=" + e.cause)
            null
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        val cls = intent?.getStringExtra("dh_class") ?: return false
        return try {
            val r = live[cls]?.onUnbind(clean(intent, cls)) ?: false
            FileLog.d("HOSTSVC guest " + cls + " onUnbind")
            r
        } catch (e: Throwable) { false }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val cls = intent?.getStringExtra("dh_class")
        if (intent == null || cls == null) { stopSelf(startId); return START_NOT_STICKY }
        FileLog.d("HOSTSVC start " + cls)
        lastCls = cls
        try {
            val svc = guest(intent)
            if (svc == null) { stopSelf(startId); return START_NOT_STICKY }
            val r = svc.onStartCommand(clean(intent, cls), flags, startId)
            FileLog.d("HOSTSVC guest " + cls + " onStartCommand returned " + r)
            return r
        } catch (e: Throwable) {
            FileLog.d("HOSTSVC failed for " + cls + ": " + e + " cause=" + e.cause)
            stopSelf(startId)
            return START_NOT_STICKY
        }
    }

    override fun onDestroy() {
        FileLog.d("HOSTSVC host onDestroy, live=" + live.keys)
        for ((k, s) in live) {
            try {
                s.onDestroy()
                FileLog.d("HOSTSVC guest " + k + " onDestroy")
            } catch (e: Throwable) {
                FileLog.d("HOSTSVC guest " + k + " onDestroy FAILED: " + e + " cause=" + e.cause + " AT " +
                        e.stackTrace.take(8).joinToString(" <- ") { it.className + "." + it.methodName + ":" + it.lineNumber })
            }
        }
        live.clear()
        super.onDestroy()
    }
}

class HostService0 : BaseHostService()
class HostService1 : BaseHostService()
class HostService2 : BaseHostService()
class HostService3 : BaseHostService()
class HostService4 : BaseHostService()
class HostService5 : BaseHostService()
class HostService6 : BaseHostService()
class HostService7 : BaseHostService()
class HostService8 : BaseHostService()
class HostService9 : BaseHostService()
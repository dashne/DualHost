package com.ims134.dualhost

import android.content.Context
import android.os.Bundle
import android.os.Process
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier

object EngineProbe {
    @Volatile private var started = false
    @Volatile private var synced = false

    fun start(ctx: Context) {
        if (started) return
        started = true
        val loader = ctx.classLoader
        Thread({
            repeat(18) { i ->
                try { tick(loader, i) } catch (e: Throwable) { FileLog.d("EPROBE tick failed: $e") }
                Thread.sleep(10_000)
            }
        }, "EngineProbe").start()
    }

    private fun tick(loader: ClassLoader, i: Int) {
        val engCls = Class.forName("com.viber.jni.Engine", false, loader)
        val eng = findEngine(loader, engCls)
        if (eng == null) {
            FileLog.d("EPROBE[$i] pid=${Process.myPid()} engine instance NOT FOUND")
            return
        }
        val init = call(eng, "isInitialized")
        val state = call(eng, "getServiceState")
        val conn = call(eng, "getConnectionController")
        val isConn = conn?.let { call(it, "isConnected") }
        val cid = conn?.let { call(it, "getMyCID") }
        FileLog.d("EPROBE[$i] pid=${Process.myPid()} init=$init state=$state " +
                "connCtl=${conn?.javaClass?.name} isConnected=$isConn CID=$cid")

        // Only the connected process can upload contacts. Run the contacts sync once here.
        if (isConn == true && !synced) {
            val acc = AccProxy.fakeAccount
            if (acc != null) {
                synced = true
                FileLog.d("EPROBE[$i] pid=${Process.myPid()} connected -> GuestSync.run")
                try {
                    GuestSync.run(acc, "com.android.contacts", Bundle())
                } catch (e: Throwable) {
                    FileLog.d("EPROBE GuestSync.run failed: $e")
                }
            } else {
                FileLog.d("EPROBE[$i] connected but AccProxy.fakeAccount is null")
            }
        }

        if (i == 0 && conn != null && isConn != true) {
            try {
                val r = conn.javaClass.getMethod("connect").invoke(conn)
                FileLog.d("EPROBE[$i] connect() returned $r")
            } catch (e: Throwable) {
                val t = (e as? InvocationTargetException)?.targetException ?: e
                FileLog.d("EPROBE[$i] connect() THREW $t")
                t.stackTrace.take(12).forEach { FileLog.d("EPROBE   at $it") }
            }
        }
    }

    // Same approach as NetDiag: take the ViberApplication instance, then find a method or
    // field on it (or its superclasses) that returns the Engine.
    private fun findEngine(loader: ClassLoader, engCls: Class<*>): Any? {
        val appCls = Class.forName("com.viber.voip.ViberApplication", false, loader)
        var app: Any? = null
        for (m in appCls.declaredMethods) {
            if (Modifier.isStatic(m.modifiers) && m.parameterCount == 0 && m.returnType == appCls) {
                m.isAccessible = true
                app = m.invoke(null)
                if (app != null) break
            }
        }
        if (app == null) {
            for (f in appCls.declaredFields) {
                if (Modifier.isStatic(f.modifiers) && f.type == appCls) {
                    f.isAccessible = true
                    app = f.get(null)
                    if (app != null) break
                }
            }
        }
        if (app == null) {
            FileLog.d("EPROBE ViberApplication instance not found")
            return null
        }

        var c: Class<*>? = appCls
        while (c != null && c != Any::class.java) {
            for (m in c.declaredMethods) {
                if (m.returnType != engCls) continue
                try {
                    m.isAccessible = true
                    val r = when {
                        m.parameterCount == 0 -> m.invoke(app)
                        m.parameterCount == 1 && m.parameterTypes[0] == java.lang.Boolean.TYPE -> m.invoke(app, false)
                        else -> null
                    }
                    if (r != null) return r
                } catch (e: Throwable) { }
            }
            for (f in c.declaredFields) {
                if (f.type == engCls && !Modifier.isStatic(f.modifiers)) {
                    f.isAccessible = true
                    f.get(app)?.let { return it }
                }
            }
            c = c.superclass
        }
        return null
    }

    private fun call(o: Any, name: String): Any? = try {
        o.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 }?.invoke(o)
    } catch (e: Throwable) {
        "ERR:" + (((e as? InvocationTargetException)?.targetException) ?: e)
    }
}
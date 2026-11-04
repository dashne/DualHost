package com.ims134.dualhost

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dalvik.system.DexFile
import java.lang.reflect.Modifier
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

// Diagnostic only: measures from INSIDE the clone process what Android and plain sockets report.
// Look for "NETDIAG" in the log.
// 2026-11-04: added probes to Viber's signalling endpoint (44.192.201.196:4244), which Polestar's
// Viber holds an ESTABLISHED connection to but this clone never opens.
object NetDiag {
    @Volatile private var started = false

    private fun probe(host: String, port: Int, timeoutMs: Int) {
        val t0 = System.currentTimeMillis()
        try {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), timeoutMs)
                FileLog.d("NETDIAG probe " + host + ":" + port + " CONNECTED in " +
                        (System.currentTimeMillis() - t0) + "ms")
            }
        } catch (e: Throwable) {
            FileLog.d("NETDIAG probe " + host + ":" + port + " FAILED after " +
                    (System.currentTimeMillis() - t0) + "ms: " + e)
        }
    }

    fun start(ctx: Context) {
        if (started) return
        started = true
        Thread {
            try {
                val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val net = cm.activeNetwork
                val caps = if (net != null) cm.getNetworkCapabilities(net) else null
                @Suppress("DEPRECATION")
                val info = cm.activeNetworkInfo
                FileLog.d("NETDIAG activeNetwork=" + net +
                        " info=" + (info?.let { it.typeName + "/" + it.state + " connected=" + it.isConnected } ?: "null") +
                        " metered=" + cm.isActiveNetworkMetered)
                if (caps != null) {
                    FileLog.d("NETDIAG caps INTERNET=" + caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) +
                            " VALIDATED=" + caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) +
                            " NOT_SUSPENDED=" + caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED) +
                            " NOT_RESTRICTED=" + caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED) +
                            " wifi=" + caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) +
                            " cell=" + caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) +
                            " vpn=" + caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
                } else FileLog.d("NETDIAG caps = null")
            } catch (e: Throwable) { FileLog.d("NETDIAG state failed: " + e) }

            for (host in listOf("www.viber.com", "google.com")) {
                val t0 = System.currentTimeMillis()
                try {
                    val addr = InetAddress.getByName(host)
                    val t1 = System.currentTimeMillis()
                    FileLog.d("NETDIAG dns " + host + " -> " + addr.hostAddress + " in " + (t1 - t0) + "ms")
                    Socket().use { s ->
                        s.connect(InetSocketAddress(addr, 443), 6000)
                        FileLog.d("NETDIAG tcp " + host + ":443 connected in " + (System.currentTimeMillis() - t1) + "ms")
                    }
                } catch (e: Throwable) {
                    FileLog.d("NETDIAG " + host + " FAILED after " + (System.currentTimeMillis() - t0) + "ms: " + e)
                }
            }

            // Viber signalling endpoint seen ESTABLISHED in Polestar (port 0x1094 = 4244).
            probe("44.192.201.196", 4244, 6000)
            probe("44.192.201.196", 443, 6000)

            // ---- Data-dir diagnostic: which path source hands the engine the HOST dir? ----
            try {
                fun d(tag: String, f: () -> Any?) {
                    try { FileLog.d("DATADIR " + tag + " = " + f()) } catch (e: Throwable) { FileLog.d("DATADIR " + tag + " threw " + e) }
                }
                val app = ctx.applicationContext
                d("ctx.class") { ctx.javaClass.name }
                d("app.class") { app.javaClass.name }
                d("app.baseContext.class") { (app as? android.content.ContextWrapper)?.baseContext?.javaClass?.name }
                d("app.getDataDir") { app.dataDir }
                d("app.applicationInfo.dataDir") { app.applicationInfo.dataDir }
                d("app.getFilesDir") { app.filesDir }
                d("app.getFilesDir.parent") { app.filesDir.parent }
                d("app.getCacheDir") { app.cacheDir }
                d("app.getPackageName") { app.packageName }
                d("app.getDatabasePath(x)") { app.getDatabasePath("x") }
                d("app.getNoBackupFilesDir") { app.noBackupFilesDir }
                d("Environment.getDataDirectory") { android.os.Environment.getDataDirectory() }
                d("ctx.createDeviceProtectedStorageContext.dataDir") { ctx.createDeviceProtectedStorageContext().dataDir }
                d("ctx.createPackageContext(self).dataDir") { ctx.createPackageContext(ctx.packageName, 0).dataDir }
                d("user.dir") { System.getProperty("user.dir") }
                d("ActivityThread.currentApplication.dataDir") {
                    val at = Class.forName("android.app.ActivityThread")
                    (at.getMethod("currentApplication").invoke(null) as android.content.Context).dataDir
                }
            } catch (e: Throwable) { FileLog.d("DATADIR dump failed: " + e) }

            // ---- Viber engine state (diagnostic) ----
            try {
                val loader = ctx.javaClass.classLoader ?: ctx.classLoader
                val engCls = Class.forName("com.viber.jni.Engine", false, loader)

                fun dumpGetters(tag: String, o: Any) {
                    var k: Class<*>? = o.javaClass
                    while (k != null && k != Any::class.java) {
                        for (m in k.declaredMethods) {
                            if (m.parameterCount != 0 || Modifier.isStatic(m.modifiers)) continue
                            val n = m.name
                            if (!(n.startsWith("is") || n.startsWith("get") || n.startsWith("has"))) continue
                            val rt = m.returnType
                            if (!(rt.isPrimitive || rt == String::class.java || rt.isEnum)) continue
                            try {
                                m.isAccessible = true
                                FileLog.d("VSTATE $tag.$n=" + m.invoke(o))
                            } catch (e: Throwable) {
                                FileLog.d("VSTATE $tag.$n threw " + (e.cause ?: e))
                            }
                        }
                        k = k.superclass
                    }
                }

                fun findEngine(): Any? {
                    for (f in engCls.declaredFields) {
                        if (Modifier.isStatic(f.modifiers) && f.type == engCls) {
                            f.isAccessible = true
                            f.get(null)?.let { return it }
                        }
                    }
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
                    if (app == null) { FileLog.d("VSTATE ViberApplication instance not found"); return null }
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

                for (delaySec in listOf(12L, 25L))
                {
                    Thread.sleep(delaySec * 1000)
                    val eng = findEngine()
                    FileLog.d("VSTATE t+" + delaySec + "s engine=" + (eng?.javaClass?.name ?: "null"))
                    if (eng != null) {
                        dumpGetters("Engine", eng)
                        try {
                            val cc = engCls.getMethod("getConnectionController").invoke(eng)
                            if (cc != null) {
                                FileLog.d("VSTATE ConnCtl class=" + cc.javaClass.name +
                                        " methods=" + cc.javaClass.declaredMethods.joinToString(",") { it.name + "/" + it.parameterCount })
                                dumpGetters("ConnCtl", cc)
                            } else FileLog.d("VSTATE ConnCtl=null")
                        } catch (e: Throwable) { FileLog.d("VSTATE ConnCtl failed: " + (e.cause ?: e)) }
                    }
                }

                // ---- Trigger Viber's contact sync adapter once (diagnostic) ----
                try {
                    Thread.sleep(5000) // let the engine finish connecting
                    val acc = AccProxy.fakeAccount
                    val auth = GuestSync.discover()?.authority
                    FileLog.d("GSYNC trigger account=" + acc + " authority=" + auth)
                    if (acc != null && auth != null) {
                        val extras = android.os.Bundle().apply {
                            putBoolean(android.content.ContentResolver.SYNC_EXTRAS_MANUAL, true)
                            putBoolean(android.content.ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
                        }
                        GuestSync.run(acc, auth, extras)
                    }
                } catch (e: Throwable) { FileLog.d("GSYNC trigger failed: " + e) }

                // ---- Experiment: manually ask the engine to connect ----
                try {
                    val eng = findEngine()
                    if (eng != null)
                    {
                        val cc = engCls.getMethod("getConnectionController").invoke(eng)
                        if (cc != null) {
                            val r = cc.javaClass.getMethod("connect").invoke(cc)
                            FileLog.d("VSTATE manual connect() returned " + r)
                            for (s in listOf(3L, 8L, 15L)) {
                                Thread.sleep(s * 1000 - (if (s == 3L) 0L else if (s == 8L) 3000L else 8000L))
                                FileLog.d("VSTATE after connect +" + s + "s: isConnected=" +
                                        cc.javaClass.getMethod("isConnected").invoke(cc) +
                                        " serviceState=" + engCls.getMethod("getServiceState").invoke(eng) +
                                        " CID=" + cc.javaClass.getMethod("getMyCID").invoke(cc))
                            }
                        }
                    }
                } catch (e: Throwable) { FileLog.d("VSTATE manual connect failed: " + (e.cause ?: e)) }

                // ---- Address-book sync probe (diagnostic) ----
                try {
                    val eng = findEngine()
                    if (eng != null) {
                        val cc = engCls.getMethod("getConnectionController").invoke(eng)
                        FileLog.d("ABOOK isConnected=" + cc?.javaClass?.getMethod("isConnected")?.invoke(cc) +
                                " CID=" + cc?.javaClass?.getMethod("getMyCID")?.invoke(cc) +
                                " state=" + engCls.getMethod("getServiceState").invoke(eng))
                        val re = Regex("(?i)addressbook|contact|sync|share")
                        listOf(engCls, Class.forName("com.viber.jni.NativeEngineBridge", false, ctx.classLoader)).forEach { c ->
                            c.declaredMethods.filter { re.containsMatchIn(it.name) }.forEach { m ->
                                FileLog.d("ABOOK " + c.simpleName + "." + m.name +
                                        "(" + m.parameterTypes.joinToString(",") { it.simpleName } + ")")
                            }
                        }
                    }
                } catch (e: Throwable) { FileLog.d("ABOOK probe failed: " + (e.cause ?: e)) }

            } catch (e: Throwable) { FileLog.d("VSTATE dump failed: " + e) }


        }.also { it.isDaemon = true }.start()
    }
}
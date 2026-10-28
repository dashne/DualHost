package com.ims134.dualhost

import android.app.Application
import android.app.Notification
import android.content.ComponentName
import android.content.Intent
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

object AmProxy {
    private const val HOST = "com.ims134.dualhost"
    private val stubs: List<Class<*>> = Slots.stubs
    private val seen = HashSet<String>()

    private fun logOnce(s: String) {
        if (synchronized(seen) { seen.add(s) }) FileLog.d(s)
    }

    private fun fix(i: Intent): Intent {
        val cn = i.component ?: return i
        if (cn.className.startsWith("com.ims134.dualhost.")) return i
        val vuid = ProcessClone.slot ?: return i
        val apk = PmProxy.guestApk ?: return i
        if (vuid !in stubs.indices) return i
        val pkg = File(apk).parentFile?.parentFile?.name ?: return i
        if (cn.packageName != HOST && cn.packageName != pkg) return i

        val n = Intent(i)
        n.component = ComponentName(HOST, stubs[vuid].name)
        n.putExtra("guestClass", cn.className)
        n.putExtra("guestPackage", pkg)
        n.putExtra("vuid", vuid)
        n.putExtra("cloneId", if (ProcessClone.id >= 0) ProcessClone.id else vuid)
        n.putExtra("apkPath", apk)
        n.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (n.identifier == null) n.identifier = "dh:" + cn.className
        logOnce("AM pending intent redirected " + cn.className + " -> slot " + vuid)
        return n
    }

    private fun fixReceiver(i: Intent): Intent {
        val cn = i.component ?: return i
        if (cn.className.startsWith("com.ims134.dualhost.")) return i
        val vuid = ProcessClone.slot ?: return i
        val apk = PmProxy.guestApk ?: return i
        if (vuid !in Slots.receivers.indices) return i
        val pkg = File(apk).parentFile?.parentFile?.name ?: return i
        if (cn.packageName != HOST && cn.packageName != pkg) return i

        val n = Intent(i)
        n.component = ComponentName(HOST, Slots.receivers[vuid].name)
        if (n.identifier == null) n.identifier = "dh:" + cn.className
        n.putExtra("dh_class", cn.className)
        n.putExtra("dh_apk", apk)
        n.putExtra("dh_vuid", vuid)
        n.putExtra("dh_clone", if (ProcessClone.id >= 0) ProcessClone.id else vuid)
        n.putExtra("dh_pkg", pkg)
        logOnce("AM pending broadcast redirected " + cn.className + " -> HostReceiver" + vuid)
        return n
    }

    private fun fixService(i: Intent): Intent {
        val cn = i.component ?: return i
        if (cn.className.startsWith("com.ims134.dualhost.")) return i
        val vuid = ProcessClone.slot ?: return i
        val apk = PmProxy.guestApk ?: return i
        if (vuid !in Slots.services.indices) return i
        val pkg = File(apk).parentFile?.parentFile?.name ?: return i
        if (cn.packageName != HOST && cn.packageName != pkg) return i

        val n = Intent(i)
        n.component = ComponentName(HOST, Slots.services[vuid].name)
        if (n.identifier == null) n.identifier = "dh:" + cn.className
        n.putExtra("dh_class", cn.className)
        n.putExtra("dh_apk", apk)
        n.putExtra("dh_vuid", vuid)
        n.putExtra("dh_clone", if (ProcessClone.id >= 0) ProcessClone.id else vuid)
        n.putExtra("dh_pkg", pkg)
        logOnce("AM pending service redirected " + cn.className + " -> HostService" + vuid)
        return n
    }

    fun install() {
        try {
            val amc = Class.forName("android.app.ActivityManager")
            val sf = amc.getDeclaredField("IActivityManagerSingleton")
            sf.isAccessible = true
            val singleton = sf.get(null)!!
            val sc = Class.forName("android.util.Singleton")
            val real = sc.getMethod("get").invoke(singleton)
            val iface = Class.forName("android.app.IActivityManager")

            val proxy = Proxy.newProxyInstance(iface.classLoader, arrayOf(iface)) { _, m, args ->
                var a = args

                // Log-only: which calls does the guest make (each name once)
                try {
                    if (!m.name.startsWith("getIntentSender")) logOnce("AMCALL " + m.name)
                } catch (e: Throwable) { }

                // startForeground() sends its notification through here
                if (m.name == "setServiceForeground" && a != null) {
                    try {
                        a.firstOrNull { it is Notification }?.let {
                            NmProxy.fix(it as Notification)
                            logOnce("AM setServiceForeground notification icons fixed")
                        }
                    } catch (e: Throwable) {
                        logOnce("AM setServiceForeground fix failed: $e")
                    }
                    val lastArg = a.lastOrNull()
                    if (a.size >= 6 && lastArg is Int && lastArg == -1) {
                        val cls = BaseHostService.lastCls
                        val own = if (cls != null) PmProxy.guestServiceType(cls) else 0
                        val allowed = own and 0x4000008B
                        logOnce("AM setServiceForeground type -1 for " + cls + ": guest declares 0x" +
                                Integer.toHexString(own) + ", sent 0x" + Integer.toHexString(allowed))
                        if (allowed != 0) {
                            a = a.copyOf()
                            a[a.size - 1] = allowed
                        }
                    }
                }

                // Pending intents: activities, broadcasts and services go to our host classes
                if (m.name.startsWith("getIntentSender") && a != null) {
                    try {
                        val idx = a.indexOfFirst { it is Array<*> && it.isArrayOf<Intent>() }
                        val type = a.getOrNull(0) as? Int
                        if (idx >= 0) {
                            @Suppress("UNCHECKED_CAST")
                            val intents = a[idx] as Array<Intent>
                            if (type != 2) {
                                val f0 = intents.firstOrNull()
                                logOnce("AM pending type=" + type + " comp=" + f0?.component?.className +
                                        " pkg=" + (f0?.component?.packageName ?: f0?.`package`) +
                                        " action=" + f0?.action)
                            }
                            if (type == 1) {
                                a = a.copyOf()
                                a[idx] = Array(intents.size) { fixReceiver(intents[it]) }
                            }
                            if (type == 2) {
                                a = a.copyOf()
                                a[idx] = Array(intents.size) { fix(intents[it]) }
                            }
                            if (type == 4 || type == 5) {
                                a = a.copyOf()
                                a[idx] = Array(intents.size) { fixService(intents[it]) }
                            }
                        }
                    } catch (e: Throwable) {
                        logOnce("AM fix failed: $e")
                    }
                }

                // The guest asks for one of its own provider authorities: use our slot proxy
                var redirectedName: String? = null
                var redirectedSlot = 0
                if (m.name == "getContentProvider" && a != null) {
                    val nm = a.getOrNull(2) as? String
                    val slot = ProcessClone.slot
                    if (nm != null && slot != null && !nm.startsWith("com.ims134.dualhost.fp") &&
                        GuestProviders.find(nm) != null) {
                        a = a.copyOf()
                        a[2] = "com.ims134.dualhost.fp$slot"
                        redirectedName = nm
                        redirectedSlot = slot
                        logOnce("AM getContentProvider redirected $nm -> fp$slot")
                    }
                }

                // Providers we hand out have no system connection: skip reference counting
                if (a != null && a.isNotEmpty() && a[0] == null &&
                    (m.name == "refContentProvider" || m.name == "removeContentProvider" ||
                            m.name == "unstableProviderDied")) {
                    logOnce("AM " + m.name + " skipped (no connection)")
                    return@newProxyInstance if (m.name == "refContentProvider") true else null
                }

                try {
                    val r = if (a == null) m.invoke(real) else m.invoke(real, *a)

                    if (r != null && redirectedName != null) {
                        val icp = DynProxies.get(redirectedName, redirectedSlot)
                        if (icp != null) {
                            try {
                                val f = r.javaClass.getDeclaredField("provider")
                                f.isAccessible = true
                                f.set(r, icp)
                                logOnce("AM getContentProvider: fresh proxy handed out for $redirectedName")
                            } catch (e: Throwable) {
                                logOnce("AM could not set holder.provider: $e")
                            }
                            try {
                                val nf = r.javaClass.getDeclaredField("noReleaseNeeded")
                                nf.isAccessible = true
                                nf.setBoolean(r, true)
                            } catch (e: Throwable) {
                                logOnce("AM could not set noReleaseNeeded: $e")
                            }
                        }
                    }

                    // Log-only: what does the system answer about how this process started
                    if (m.name == "getHistoricalProcessStartReasons") {
                        try {
                            logOnce("AM startReasons args=" + (a?.joinToString { it?.toString() ?: "null" } ?: "none"))
                            logOnce("AM startReasons result=" + (r?.javaClass?.name ?: "null"))
                            try {
                                val p = android.os.Parcel.obtain()
                                (r as android.os.Parcelable).writeToParcel(p, 0)
                                p.setDataPosition(0)
                                val n = p.readInt()
                                logOnce("AM startReasons parcel count=" + n + " bytes=" + p.dataSize())
                                p.recycle()
                            } catch (e3: Throwable) { logOnce("AM startReasons parcel failed: " + e3) }
                        } catch (e2: Throwable) {
                            logOnce("AM startReasons size failed: " + e2)
                        }
                    }

                    // Process name seen by the guest (only with "Process name is the app's own" on)
                    if (m.name == "getRunningAppProcesses" && r is List<*>) {
                        try {
                            val gdir = PmProxy.guestApk?.let { File(it).parentFile?.parentFile }
                            val gpkg = gdir?.name
                            if (gpkg != null && File(gdir, "process_name_guest.txt").exists()) {
                                for (p in r) {
                                    val info = p as? android.app.ActivityManager.RunningAppProcessInfo ?: continue
                                    if (info.pid == android.os.Process.myPid()) {
                                        info.processName = gpkg
                                        logOnce("AM own process reported as " + gpkg)
                                    }
                                }
                            }
                        } catch (e: Throwable) {
                            logOnce("AM getRunningAppProcesses fix failed: " + e)
                        }
                    }
                    r
                } catch (e: InvocationTargetException) {
                    logOnce("AM " + m.name + " threw " + e.targetException)
                    throw e.targetException
                }
            }

            val mf = sc.getDeclaredField("mInstance")
            mf.isAccessible = true
            mf.set(singleton, proxy)
            FileLog.d("AmProxy installed")
        } catch (e: Throwable) {
            FileLog.d("AmProxy failed: $e")
        }
    }
}
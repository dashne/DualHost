package com.ims134.dualhost

import android.app.Application
import android.app.Notification
import android.content.ComponentName
import android.content.ContentProvider
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

    // Fixed (2026-10-30, Viber registration crash part 3): the numbered-slot redirect path
    // (rewrite authority to "com.ims134.dualhost.fp$slot", invoke the REAL AMS, then patch the
    // returned holder's provider field) only works because HostFileProxyN is declared in the
    // manifest with android:process bound to exactly that numbered process, so the real AMS call
    // resolves and serves it locally without ever leaving this process. The dedicated Viber
    // process (ProcessClone.realName == "com.viber.voip" literally, no ":pN" — so it can present
    // as the real package name) has no such binding: ProcessClone.slot is null there, so that
    // whole path (and the isDeclared() safety net) was being skipped entirely, and every provider
    // query — including com.viber.provider.contacts.ViberContactsProvider — fell straight through
    // to the real system and crashed against the real, separately-installed Viber app's own
    // registration under a different UID. For THIS process there's no real numbered authority to
    // route through, so skip the real AMS round trip altogether and hand back a
    // ContentProviderHolder built entirely in-process from our own already-started guest provider.
    private fun buildLocalHolder(authority: String, cp: ContentProvider): Any? {
        return try {
            val gi = ContentProvider::class.java.getDeclaredMethod("getIContentProvider")
            gi.isAccessible = true
            val icp = gi.invoke(cp) ?: return null

            // 2026-10-30 fix: ActivityThread.installProviderAuthoritiesLocked reads
            // holder.info.applicationInfo.uid unconditionally — a bare ProviderInfo() with that
            // field left null crashed with a NullPointerException there (confirmed from device log:
            // "Attempt to read from field 'int android.content.pm.ApplicationInfo.uid' on a null
            // object reference"), one level past where the earlier SecurityException was happening
            // (which this buildLocalHolder path DID already fix — no more real-AMS round trip).
            // DynProxies.get() already does this correctly for the numbered-slot path; mirror it.
            val app = Class.forName("android.app.ActivityThread")
                .getDeclaredMethod("currentApplication").invoke(null) as Application
            val piClass = Class.forName("android.content.pm.ProviderInfo")
            val info = piClass.getDeclaredConstructor().newInstance() as android.content.pm.ProviderInfo
            info.authority = authority
            info.packageName = HOST
            info.name = cp.javaClass.name
            info.applicationInfo = app.applicationInfo
            info.exported = false
            info.enabled = true

            val holderClass = Class.forName("android.app.ContentProviderHolder")
            val ctor = holderClass.getDeclaredConstructor(piClass)
            ctor.isAccessible = true
            val holder = ctor.newInstance(info)

            val provF = holderClass.getDeclaredField("provider")
            provF.isAccessible = true
            provF.set(holder, icp)

            try {
                val nf = holderClass.getDeclaredField("noReleaseNeeded")
                nf.isAccessible = true
                nf.setBoolean(holder, true)
            } catch (e: Throwable) {
                logOnce("AM buildLocalHolder: noReleaseNeeded not set: $e")
            }

            logOnce("AM getContentProvider: local in-process holder built for $authority")
            holder
        } catch (e: Throwable) {
            // Not yet verified against this exact Android build's hidden ContentProviderHolder
            // shape — if the reflection fails, this logs and falls back to null below (no crash,
            // just that one query silently returning nothing, same degraded behavior as before
            // this fix existed).
            logOnce("AM buildLocalHolder failed for $authority: $e")
            null
        }
    }

    // Widened from private (2026-10-31) so TmProxy can reuse the exact same guest-component
    // detection/redirect logic for the IActivityTaskManager.startActivity(...) path — a direct
    // Context.startActivity() call from a Service/Receiver/Application context never goes through
    // Instrumentation at all, so it needs the same fix() this file already applies to PendingIntents.
    fun fix(i: Intent): Intent {
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
                    if (nm != null && !nm.startsWith("com.ims134.dualhost.fp")) {
                        if (slot != null) {
                            // Numbered-slot clone process (:p0.., ProcessClone.slot != null): route
                            // through the real, manifest-declared fp$slot provider bound to this
                            // same process, then patch its holder.
                            if (GuestProviders.find(nm) != null) {
                                a = a.copyOf()
                                a[2] = "com.ims134.dualhost.fp$slot"
                                redirectedName = nm
                                redirectedSlot = slot
                                logOnce("AM getContentProvider redirected $nm -> fp$slot")
                            } else if (GuestProviders.isDeclared(nm)) {
                                // Fixed (2026-10-30, Viber registration crash): this authority belongs
                                // to the guest's own manifest but our in-process instance for it never
                                // started (constructor threw — see "guest provider failed" in the log).
                                // Previously this fell through to the real system, which is only safe
                                // when nothing else on the device happens to own the same authority
                                // string. It isn't always safe: a real, separately-installed copy of
                                // the SAME app (e.g. the real Viber alongside a Viber clone) can already
                                // have that exact authority registered under its own real UID, and the
                                // real ActivityManagerService then correctly refuses our clone's cross-
                                // UID access with a SecurityException that crashes the guest. A guest
                                // authority we can't serve must fail safely (no provider) instead of
                                // risking a real cross-app lookup.
                                logOnce("AM getContentProvider: declared guest authority $nm has no running " +
                                        "instance (provider failed to start) — refusing without a real system call")
                                return@newProxyInstance null
                            }
                        } else {
                            // No numbered slot (the dedicated Viber process, ProcessClone.realName ==
                            // "com.viber.voip" literally — see buildLocalHolder's comment above for why
                            // the fp$slot trick doesn't apply here). Serve directly in-process or fail
                            // safe; never let it reach the real system.
                            val cp = GuestProviders.find(nm)
                            if (cp != null) {
                                logOnce("AM getContentProvider: serving $nm locally (no slot, dedicated process)")
                                return@newProxyInstance buildLocalHolder(nm, cp)
                            } else if (GuestProviders.isDeclared(nm)) {
                                logOnce("AM getContentProvider: declared guest authority $nm has no running " +
                                        "instance (no slot, dedicated process) — refusing without a real system call")
                                return@newProxyInstance null
                            }
                        }
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
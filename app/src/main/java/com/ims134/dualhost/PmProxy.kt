package com.ims134.dualhost

import android.content.ComponentName
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.util.Log
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

object PmProxy {
    private const val HOST = "com.ims134.dualhost"
    @Volatile var guestApk: String? = null
    @Volatile private var proxy: Any? = null
    private var real: Any? = null
    private val seen = HashSet<String>()
    private val busy = ThreadLocal<Boolean>()

    private fun logOnce(s: String) {
        if (synchronized(seen) { seen.add(s) }) FileLog.d(s)
    }

    // Parse the guest manifest in-process (does not go through IPackageManager)
    // 2026-11-04: Viber's Engine.getApplicationDataDir() asks the context for its package name
    // (gets the guest name), then resolves it through PackageManager (getPackageInfo/getApplicationInfo),
    // which we answer with HOST's record, so the native engine was handed HOST's data dir
    // (/data/user/0/com.ims134.dualhost) and could not find Viber's own activation data
    // (getMyCID=-1, never connects). Returns a copy whose dataDir is this clone's own virtual root.
    private fun withGuestDataDir(ai: ApplicationInfo?): ApplicationInfo? {
        if (ai == null) return null
        try {
            val gp = guestApk?.let { File(it).parentFile?.parentFile?.name } ?: return ai
            if (gp !in NATIVE_ENGINE_PACKAGES) return ai
            val id = ProcessClone.id
            if (id < 0) return ai
            val hostData = ai.dataDir ?: return ai
            if (hostData.contains("/virtual/")) return ai
            val out = ApplicationInfo(ai)
            out.dataDir = File(hostData, "virtual/" + gp + "/" + id).absolutePath
            logOnce("PM DATADIR " + hostData + " -> " + out.dataDir)
            return out
        } catch (e: Throwable) { logOnce("PM DATADIR failed: $e"); return ai }
    }

    private fun guestInfo(flags: Int): PackageInfo? {
        val apk = guestApk ?: return null
        busy.set(true)
        try {
            val app = Class.forName("android.app.ActivityThread")
                .getDeclaredMethod("currentApplication").invoke(null) as android.app.Application
            return app.packageManager.getPackageArchiveInfo(apk, flags)
        } catch (e: Throwable) {
            return null
        } finally {
            busy.set(false)
        }
    }

    private var svcActions: Map<String, List<String>>? = null

    // service class -> actions of its intent-filters, read from the guest manifest
    private fun guestServiceActions(): Map<String, List<String>> {
        svcActions?.let { return it }
        val apk = guestApk ?: return emptyMap()
        val map = HashMap<String, MutableList<String>>()
        busy.set(true)
        try {
            val app = Class.forName("android.app.ActivityThread")
                .getDeclaredMethod("currentApplication").invoke(null) as android.app.Application
            val pm = app.packageManager
            val ai = pm.getPackageArchiveInfo(apk, 0)!!.applicationInfo!!
            ai.sourceDir = apk
            ai.publicSourceDir = apk
            val p = pm.getResourcesForApplication(ai).assets.openXmlResourceParser("AndroidManifest.xml")
            val ns = "http://schemas.android.com/apk/res/android"
            var pkg = ""
            var svc: String? = null
            var ev = p.eventType
            while (ev != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (ev == org.xmlpull.v1.XmlPullParser.START_TAG) {
                    when (p.name) {
                        "manifest" -> pkg = p.getAttributeValue(null, "package") ?: ""
                        "service" -> {
                            var n = p.getAttributeValue(ns, "name") ?: ""
                            if (n.startsWith(".")) n = pkg + n
                            else if (!n.contains(".")) n = pkg + "." + n
                            svc = n
                        }
                        "receiver", "activity", "provider", "activity-alias" -> svc = null
                        "action" -> {
                            val a = p.getAttributeValue(ns, "name")
                            if (svc != null && a != null) map.getOrPut(svc) { ArrayList() }.add(a)
                        }
                    }
                } else if (ev == org.xmlpull.v1.XmlPullParser.END_TAG && p.name == "service") {
                    svc = null
                }
                ev = p.next()
            }
        } catch (e: Throwable) {
            logOnce("PM guest service actions failed: " + e)
        } finally {
            busy.set(false)
        }
        svcActions = map
        return map
    }

    private fun handle(m: java.lang.reflect.Method, args: Array<Any?>?): Any?
    {
//        if (args != null && args.isNotEmpty() && args[0] is android.content.Intent)
//        {
//            FileLog.d( "PMQ " + m.name + " action=" + (args[0] as android.content.Intent).action)
//        }

        // Added 2026-10-31: PackageManager.checkPermission(permission, packageName) — the
        // check-ANY-named-package overload, as opposed to the pid/uid overload CloneContext
        // already virtualizes correctly via checkPermission/checkSelfPermission — never went
        // through this proxy at all. A guest (or a shared library inside it, e.g. a
        // contacts-sync helper) that calls packageManager.checkPermission(perm,
        // context.getPackageName()) passes OUR SPOOFED guest package name ("com.viber.voip"),
        // and unredirected that asks the REAL system about the real, separately-installed
        // Viber app's grant state — not ours. Confirmed from the exact symptom this was added
        // to fix: Viber's Contacts screen kept showing "Allow access" even after Contacts was
        // genuinely granted to com.ims134.dualhost, and tapping it opened the REAL Viber's
        // app-info settings page (Settings.ACTION_APPLICATION_DETAILS_SETTINGS has no explicit
        // component, just action+data, so nothing else in this project could have redirected
        // it either — fixing the check here removes the guest's own reason to show that button
        // at all). Rewritten to ask about HOST instead, then re-applies the same ClonePerms
        // narrowing CloneContext.gate() already does for the Context-level path, so both paths
        // agree.
        if ((m.name == "checkPermission" || m.name == "checkUidPermission") &&
            args != null && args.size >= 2 && args[0] is String && args[1] is String) {
            val permName = args[0] as String
            val askedPkg = args[1] as String
            val gp = guestApk?.let { File(it).parentFile?.parentFile?.name }
            if (gp != null && askedPkg == gp) {
                try {
                    val fixedArgs = args.copyOf()
                    fixedArgs[1] = HOST
                    var r = (m.invoke(real, *fixedArgs) as? Int) ?: PackageManager.PERMISSION_DENIED
                    if (r == PackageManager.PERMISSION_GRANTED && permName in ClonePerms.GATED) {
                        val app = Class.forName("android.app.ActivityThread")
                            .getDeclaredMethod("currentApplication").invoke(null) as android.app.Application
                        val cloneId = (ProcessClone.id.takeIf { it >= 0 } ?: ProcessClone.slot)
                        if (cloneId != null && !ClonePerms.isAllowed(app, gp, cloneId, permName)) {
                            r = PackageManager.PERMISSION_DENIED
                        }
                    }
                    logOnce("PM " + m.name + " redirected pkg=" + askedPkg + " -> " + HOST +
                            " perm=" + permName + " result=" + r)
                    return r
                } catch (e: Throwable) {
                    logOnce("PM " + m.name + " redirect failed: " + e)
                    // fall through to the normal (pre-existing, unredirected) path below
                }
            }
        }

        // Added 2026-10-31 (Contacts "Allow access" persisted despite real READ_CONTACTS grant,
        // courtesy of a third-party analysis the user supplied — §5): getPackageInfo(packageName,
        // GET_PERMISSIONS) is a SEPARATE path from checkPermission/checkUidPermission above. An
        // app can read its own grant state either by calling checkSelfPermission (virtualized by
        // CloneContext.gate() + the checkPermission redirect just above) OR by calling
        // pm.getPackageInfo(getPackageName(), GET_PERMISSIONS) and reading
        // requestedPermissionsFlags directly off the result — a totally different code path that
        // nothing here was redirecting. That's almost certainly why Contacts stayed stuck even
        // after the checkPermission/PermProxy fixes: unredirected, this call asked the REAL
        // system about the REAL, separately-installed Viber app (own UID, own — likely ungranted
        // — Contacts permission), never about this clone's genuinely-granted state at all.
        // Fix: ask HOST for the real flags, but keep the GUEST's own identity (package name,
        // requestedPermissions list, uid) in what's handed back — only borrow HOST's grant BITS
        // for whichever permissions the guest's own manifest actually declares, so the result
        // still looks like "my package, but with my real OS grants" to the caller.
        if (m.name == "getPackageInfo" && args != null && args.isNotEmpty() && args[0] is String) {
            val asked = args[0] as String
            val gp = guestApk?.let { File(it).parentFile?.parentFile?.name }
            if (gp != null && asked == gp) {
                try {
                    val fixedArgs = args.copyOf()
                    fixedArgs[0] = HOST
                    val r = if (fixedArgs.size >= 2) m.invoke(real, *fixedArgs) else m.invoke(real, fixedArgs[0])
                    if (r is PackageInfo) {
                        // 2026-11-04: this branch RETURNS before the SIGSPOOF block further down
                        // runs, so a guest asking for its OWN name (com.viber.voip) with signature
                        // flags still got the host's certificate. Apply the same swap here.
                        try {
                            val sf = (args.getOrNull(1) as? Number)?.toLong() ?: 0L
                            if ((sf and (PackageManager.GET_SIGNATURES or 0x08000000).toLong()) != 0L) {
                                val sg = guestInfo(PackageManager.GET_SIGNATURES or 0x08000000)
                                if (sg != null && (sg.signatures != null || sg.signingInfo != null)) {
                                    r.signatures = sg.signatures
                                    r.signingInfo = sg.signingInfo
                                    logOnce("PM SIGSPOOF pkg=" + asked + " (guest-name path) -> guest apk signature")
                                } else logOnce("PM SIGSPOOF (guest-name path) guest signature unavailable")
                            }
                        } catch (e: Throwable) { logOnce("PM SIGSPOOF (guest-name path) failed: $e") }
                        val flags = (args.getOrNull(1) as? Number)?.toInt() ?: 0
                        val guestPi = guestInfo(flags)
                        val guestReq = guestPi?.requestedPermissions
                        if (guestPi != null && guestReq != null) {
                            // android.content.pm.PackageInfo has no copy constructor (unlike
                            // ApplicationInfo, which does) — "PackageInfo(r)" does not compile.
                            // `r` is a fresh object this call just got back from m.invoke(), not
                            // shared or cached anywhere else, so it's safe to mutate in place
                            // instead of copying.
                            val hostFlags = r.requestedPermissionsFlags
                            val hostMap = HashMap<String, Int>()
                            r.requestedPermissions?.forEachIndexed { i, p ->
                                hostMap[p] = hostFlags?.getOrNull(i) ?: 0
                            }
                            r.packageName = gp
                            r.applicationInfo = r.applicationInfo?.also {
                                it.packageName = gp
                                it.uid = android.os.Process.myUid()
                            }
                            r.applicationInfo = withGuestDataDir(r.applicationInfo)
                            r.requestedPermissions = guestReq
                            r.requestedPermissionsFlags = IntArray(guestReq.size) { i ->
                                (hostMap[guestReq[i]] ?: 0) and PackageInfo.REQUESTED_PERMISSION_GRANTED
                            }
                            logOnce("PM getPackageInfo guest-permissions redirected for " + gp +
                                    " (hostGrantedCount=" +
                                    (hostFlags?.count { it and PackageInfo.REQUESTED_PERMISSION_GRANTED != 0 } ?: 0) +
                                    ", guestAskedCount=" + guestReq.size + ")")
                            return r
                        }
                    }
                    return r
                } catch (e: Throwable) {
                    logOnce("PM getPackageInfo guest-permissions redirect failed: " + e)
                    // fall through to the normal (pre-existing, unredirected) path below
                }
            }
        }

        var result: Any?
        try {
            result = if (args == null) m.invoke(real) else m.invoke(real, *args)
        } catch (e: InvocationTargetException) {
            val cn = args?.getOrNull(0) as? ComponentName
            val gp = guestApk?.let { java.io.File(it).parentFile?.parentFile?.name }
            val te = e.targetException
            if (m.name == "setComponentEnabledSetting" && cn != null && gp != null &&
                (cn.packageName == HOST || cn.packageName == gp) &&
                (te is IllegalArgumentException || te is SecurityException)) {
                logOnce("PM ignored setComponentEnabledSetting for guest " + cn.className + " (" + te.javaClass.simpleName + ")")
                return null
            }
            throw e.targetException
        }

        if (m.name == "getPackageInfo" && args != null && args.isNotEmpty() && args[0] is String) {
            val asked = args[0] as String
            if (result == null) FileLog.d("DIAG getPackageInfo('$asked') = NULL")
        }

        if (m.name == "getNameForUid" && guestApk != null && args != null && args.isNotEmpty()) {
            try {
                val uid = (args[0] as? Number)?.toInt()
                val gpkg = File(guestApk!!).parentFile?.parentFile?.name
                if (uid != null && gpkg != null && uid == NativeBridge.getVirtualUid()) {
                    result = gpkg
                    logOnce("PM getNameForUid intercepted for $uid -> $gpkg")
                }
            } catch (e: Throwable) {
                logOnce("PM getNameForUid failed: $e")
            }
        }

        if (m.name == "queryIntentServices" && busy.get() != true && guestApk != null &&
            args != null && args.isNotEmpty() && args[0] is android.content.Intent) {
            val q = args[0] as android.content.Intent
            val target = q.`package` ?: q.component?.packageName
            val action = q.action
            if (target == HOST && action != null) {
                try {
                    val acts = guestServiceActions()
                    val g = guestInfo(PackageManager.GET_SERVICES or PackageManager.GET_META_DATA)
                    val found = ArrayList<android.content.pm.ResolveInfo>()
                    for (s in g?.services ?: emptyArray()) {
                        if (acts[s.name]?.contains(action) == true) {
                            s.applicationInfo = g!!.applicationInfo
                            val ri = android.content.pm.ResolveInfo()
                            ri.serviceInfo = s
                            found.add(ri)
                        }
                    }
                    if (found.isNotEmpty()) {
                        result = Class.forName("android.content.pm.ParceledListSlice")
                            .getConstructor(java.util.List::class.java).newInstance(found)
                        logOnce("PM guest service query answered: " + action + " -> " +
                                found.joinToString { it.serviceInfo.name })
                    }
                } catch (e: Throwable) {
                    logOnce("PM guest service query failed: " + e)
                }
            }
        }

        if ((m.name == "resolveIntent" || m.name == "queryIntentActivities") && args != null &&
            args.isNotEmpty() && args[0] is android.content.Intent && busy.get() != true)
        {
            val q = args[0] as android.content.Intent
            var count = ""
            if (m.name == "queryIntentActivities")
            {
                busy.set(true)
                try {
                    val app = Class.forName("android.app.ActivityThread")
                        .getDeclaredMethod("currentApplication").invoke(null) as android.app.Application
                    count = " count=" + app.packageManager.queryIntentActivities(android.content.Intent(q), 0).size
                } catch (e: Throwable) {
                    count = " count=error " + e
                } finally {
                    busy.set(false)
                }
            }
            logOnce("PM " + m.name + " action=" + q.action + " type=" + q.type +
                    " pkg=" + (q.`package` ?: q.component?.packageName) + count +
                    (if (m.name == "resolveIntent") " found=" + (result != null) else ""))
        }
        if (m.name == "resolveContentProvider" && args != null && args.isNotEmpty()) {
            logOnce("PM resolveContentProvider authority=" + args[0] + " found=" + (result != null))
        }

        if (m.name == "resolveContentProvider" && result == null && busy.get() != true &&
            guestApk != null && args != null && args.isNotEmpty()) {
            val authority = args[0] as? String
            if (authority != null && authority.startsWith(HOST + ".")) {
                try {
                    val g = guestInfo(PackageManager.GET_PROVIDERS or PackageManager.GET_META_DATA)
                    val gp = g?.packageName
                    if (g != null && gp != null) {
                        val wanted = gp + authority.substring(HOST.length)
                        val hit = g.providers?.firstOrNull { it.authority?.split(";")?.contains(wanted) == true }
                        if (hit != null) {
                            hit.applicationInfo = g.applicationInfo
                            result = hit
                            logOnce("PM provider authority " + authority + " mapped to " + wanted)
                        }
                    }
                } catch (e: Throwable) {
                    logOnce("PM provider map failed: " + e)
                }
            }
        }

        // Step 206c: Android does not know the guest's manifest receivers, so answer "none"
// when the guest asks about receivers in its own package
        if (m.name == "queryIntentReceivers" && busy.get() != true && guestApk != null &&
            args != null && args.isNotEmpty()) {
            try {
                val q = args[0] as? android.content.Intent
                val gpkg = java.io.File(guestApk!!).parentFile?.parentFile?.name
                val asked = q?.`package` ?: q?.component?.packageName
                if (q != null && gpkg != null && asked == gpkg) {
                    result = Class.forName("android.content.pm.ParceledListSlice")
                        .getConstructor(java.util.List::class.java)
                        .newInstance(ArrayList<android.content.pm.ResolveInfo>())
                    logOnce("PM guest receivers answered empty: " + q.action)
                }
            } catch (e: Throwable) {
                logOnce("PM receiver answer failed: " + e)
            }
        }

        if (m.name == "getPackagesForUid" && result is Array<*> && busy.get() != true &&
            guestApk != null && args != null && args.isNotEmpty()) {
            try {
                val gpkg = java.io.File(guestApk!!).parentFile?.parentFile?.name
                val uid = (args[0] as? Number)?.toInt()
                @Suppress("UNCHECKED_CAST")
                val arr = result as Array<String>
                if (gpkg != null && uid == android.os.Process.myUid() && !arr.contains(gpkg)) {
                    result = arr + gpkg
                    logOnce("PM packagesForUid added guest name " + gpkg)
                }
            } catch (e: Throwable) { logOnce("PM packagesForUid failed: " + e) }
        }

        // 2026-11-04: guest data dir for HOST-/guest-name lookups (see withGuestDataDir).
        if (guestApk != null && busy.get() != true && args != null && args.isNotEmpty() &&
            args[0] is String) {
            try {
                val askedN = args[0] as String
                val gpN = File(guestApk!!).parentFile?.parentFile?.name
                if (askedN == HOST || askedN == gpN) {
                    if (m.name == "getPackageInfo" && result is PackageInfo) {
                        val pi = result as PackageInfo
                        pi.applicationInfo = withGuestDataDir(pi.applicationInfo)
                    } else if (m.name == "getApplicationInfo" && result is ApplicationInfo) {
                        result = withGuestDataDir(result as ApplicationInfo)
                    }
                }
            } catch (e: Throwable) { logOnce("PM DATADIR block failed: $e") }
        }

        // Firebase/GMS cert fix: guest process must present the GUEST apk's signing cert,
        // otherwise API keys restricted to Viber's package+cert answer 403 API_KEY_ANDROID_APP_BLOCKED.
        if (m.name == "getPackageInfo" && result is PackageInfo && guestApk != null &&
            busy.get() != true && args != null && args.size >= 2) {
            try {
                val askedPkg = args[0] as? String
                val flags = (args[1] as? Number)?.toLong() ?: 0L
                val sigFlags = (PackageManager.GET_SIGNATURES or 0x08000000).toLong() // 0x08000000 = GET_SIGNING_CERTIFICATES
                val gp = File(guestApk!!).parentFile?.parentFile?.name
                if ((flags and sigFlags) != 0L && (askedPkg == HOST || askedPkg == gp)) {
                    val g = guestInfo(PackageManager.GET_SIGNATURES or 0x08000000)
                    if (g != null && (g.signatures != null || g.signingInfo != null)) {
                        result.signatures = g.signatures
                        result.signingInfo = g.signingInfo
                        logOnce("PM SIGSPOOF pkg=" + askedPkg + " -> guest apk signature")
                    } else {
                        logOnce("PM SIGSPOOF guest signature unavailable")
                    }
                }
            } catch (e: Throwable) {
                logOnce("PM SIGSPOOF failed: $e")
            }
        }

        // Step 270b (log only): what do guests ask for, and which certificate do they get?
        if (guestApk != null && busy.get() != true && args != null && args.isNotEmpty() &&
            (m.name == "getPackageInfo" || m.name == "getInstallerPackageName" ||
                    m.name == "getInstallSourceInfo")) {
            try {
                var sig = ""
                if (m.name == "getPackageInfo" && result is PackageInfo) {
                    val c = result.signingInfo?.apkContentsSigners?.firstOrNull()
                        ?: result.signatures?.firstOrNull()
                    if (c != null) sig = " sha1=" + java.security.MessageDigest.getInstance("SHA-1")
                        .digest(c.toByteArray()).joinToString("") { "%02x".format(it) }.take(12)
                }
                logOnce("PMSIG " + m.name + " pkg=" + args[0] + " flags=0x" +
                        java.lang.Long.toHexString((args.getOrNull(1) as? Number)?.toLong() ?: 0L) +
                        " result=" + (if (result == null) "null" else result.javaClass.simpleName) + sig)
            } catch (e: Throwable) { }
        }

        if (busy.get() == true || guestApk == null || args == null || args.isEmpty()) return result

        try {
            val first = args[0]
            val name = m.name
            if (name == "getApplicationInfo" && first == HOST) {
                val flags = (args[1] as? Number)?.toLong() ?: 0L
                val wantsMeta = (flags and PackageManager.GET_META_DATA.toLong()) != 0L
                logOnce("PM ask getApplicationInfo meta=" + wantsMeta + " resultNull=" + (result == null))
                if (wantsMeta && result is ApplicationInfo) {
                    val gm = guestInfo(PackageManager.GET_META_DATA)?.applicationInfo?.metaData
                    if (gm != null) {
                        val ai = ApplicationInfo(result)
                        ai.metaData = gm
                        result = ai
                        logOnce("PM guest meta-data served: " + gm.size() + " keys")
                    }
                }
            } else if (first is ComponentName && first.packageName == HOST &&
                (name == "getServiceInfo" || name == "getProviderInfo" || name == "getReceiverInfo" ||
                        (name == "getActivityInfo" && result == null))) {
                val g = guestInfo(PackageManager.GET_SERVICES or PackageManager.GET_PROVIDERS or
                        PackageManager.GET_RECEIVERS or PackageManager.GET_ACTIVITIES or
                        PackageManager.GET_META_DATA)
                val list: Array<out android.content.pm.ComponentInfo>? = when (name) {
                    "getServiceInfo" -> g?.services
                    "getProviderInfo" -> g?.providers
                    "getReceiverInfo" -> g?.receivers
                    else -> g?.activities
                }
                val hit = list?.firstOrNull { it.name == first.className }
                if (hit != null) {
                    hit.applicationInfo = g!!.applicationInfo
                    result = hit
                    logOnce("PM guest " + name + " served: " + first.className)
                } else if (result == null) {
                    logOnce("PM guest " + name + " NOT found: " + first.className)
                }
//                } else {
//                    logOnce("PM guest " + name + " NOT found: " + first.className)
//                }
            }
        } catch (e: Throwable) {
            logOnce("PM proxy error: " + e)
        }
        return result
    }

    // The foreground service type a guest service declares in its own manifest (0 = none)
    fun guestServiceType(cls: String): Int {
        val g = guestInfo(PackageManager.GET_SERVICES) ?: return 0
        return g.services?.firstOrNull { it.name == cls }?.foregroundServiceType ?: 0
    }

    fun install() {
        try {
            val at = Class.forName("android.app.ActivityThread")
            real = at.getDeclaredMethod("getPackageManager").invoke(null)
            val iface = Class.forName("android.content.pm.IPackageManager")
            proxy = Proxy.newProxyInstance(iface.classLoader, arrayOf(iface)) { _, m, args ->
                handle(m, args)
            }
            val f = at.getDeclaredField("sPackageManager")
            f.isAccessible = true
            f.set(null, proxy)
            FileLog.d( "PmProxy installed")
        } catch (e: Throwable) {
            FileLog.d( "PmProxy failed: " + e)
        }
    }

    // PackageManager objects created before install() still hold the real IPackageManager
    fun patch(pm: PackageManager) {
        val p = proxy ?: return
        try {
            val f = pm.javaClass.getDeclaredField("mPM")
            f.isAccessible = true
            if (f.get(pm) !== p) {
                f.set(pm, p)
                logOnce("PM patched existing PackageManager")
            }
        } catch (e: Throwable) {
            logOnce("PM patch failed: " + e)
        }
    }
}
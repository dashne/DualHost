package com.ims134.dualhost

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Resources
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import dalvik.system.DexClassLoader
import java.io.File
import android.content.ContentProvider
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.ComponentName
import android.os.IBinder
import android.app.Instrumentation.ActivityResult
import java.lang.reflect.InvocationTargetException
import android.content.ClipData
import android.graphics.Bitmap.createBitmap
import android.net.Uri
import android.provider.MediaStore

class GuestEnv(
    val loader: DexClassLoader,
    val resources: Resources,
    val application: Application,
    // Clone data dir this env was built for. prepareGuest needs it again when an activity arrives
    // WITHOUT our stub extras (see the relaunch fallback there) and there is no intent to derive it
    // from — a fresh root would quietly point the activity at another clone's prefs/databases.
    val root: File
)

class HostInstrumentation(private val base: Instrumentation) : Instrumentation() {

    companion object {
        @Volatile var current: HostInstrumentation? = null

        // Viber's dark mode is a PARALLEL style family prefixed "Darknight.", not a -night variant:
        // every light style has a twin (Theme.Viber.Home -> Darknight.Theme.Viber.Home,
        // Base.Theme.Viber.Light -> Base.Darknight.Theme.Viber.Light, 104 of them in the apk) and the
        // twins mirror the NoActionBar shape, so Viber's setSupportActionBar() keeps working. Viber
        // selects the family in code from current_theme="darknight"; inside the clone that selection
        // never happens, so we resolve the twin by name and apply it ourselves.
        private const val VIBER_PKG = "com.viber.voip"
        private const val VIBER_DARK_PREFIX = "Darknight."
    }

    init { current = this }

    // Viber hacks run only in the old slot processes. The dedicated process named com.viber.voip runs without them.
    private fun viberHacksOn(pkg: String) = pkg == "com.viber.voip" && ProcessClone.realName != "com.viber.voip"

    fun slotBusyWith(apkPath: String, clone: Int): Boolean =
        guestEnvs.isNotEmpty() && (!guestEnvs.containsKey(apkPath) || ProcessClone.id != clone)

    fun ensureEnv(apkPath: String, vuid: Int, pkg: String, clone: Int = vuid): GuestEnv? {
        if (guestEnvs.isNotEmpty() && (!guestEnvs.containsKey(apkPath) || ProcessClone.id != clone)) {
            FileLog.d("ensureEnv refused: slot holds clone " + ProcessClone.id + ", asked for clone " + clone)
            return null
        }
        return try {
            val app = hostApp()
            val root = File(app.dataDir, "virtual/" + pkg + "/" + clone)
            root.mkdirs()
            getGuestEnv(app, apkPath, vuid, root)
        } catch (e: Throwable) {
            FileLog.d("ensureEnv failed: " + e)
            null
        }
    }

    private val guestLoaders = HashMap<String, DexClassLoader>()
    private val guestEnvs = HashMap<String, GuestEnv>()
    private var hostApplication: Application? = null

    private fun dumpEnvState(loader: ClassLoader, where: String) {
        try {
            val hai0 = loader.loadClass("hai0")
            val c = hai0.getDeclaredField("c").apply { isAccessible = true }
            FileLog.d("VIBERSTATE[$where] hai0.c=${c.get(null)}")
        } catch (e: Throwable) {
            FileLog.d("VIBERSTATE[$where] hai0 read failed: $e")
        }
        try {
            val ve = loader.loadClass("com.viber.voip.ViberEnv")
            for (f in ve.declaredFields) {
                if (java.lang.reflect.Modifier.isStatic(f.modifiers)) {
                    val t = f.type.name
                    // print only the interesting ones to keep log small
                    if (t.contains("Factory") || t.contains("Env") || t.contains("Pixie")) {
                        f.isAccessible = true
                        val v = try { f.get(null) } catch (e: Throwable) { null }
                        FileLog.d("VIBERSTATE[$where] ViberEnv.${f.name} ($t) = ${v?.javaClass?.simpleName ?: "null"}")
                    }
                }
            }
        } catch (e: Throwable) {
            FileLog.d("VIBERSTATE[$where] ViberEnv read failed: $e")
        }
    }

    private fun hostApp(): Application {
        var a = hostApplication
        if (a == null) {
            a = Class.forName("android.app.ActivityThread")
                .getDeclaredMethod("currentApplication").invoke(null) as Application
            hostApplication = a
        }
        return a
    }

    private fun guestApks(apkPath: String): List<String> {
        val dir = File(apkPath).parentFile
        val others = dir?.listFiles { f -> f.name.endsWith(".apk") && f.name != "base.apk" }
            ?.map { it.absolutePath }?.sorted() ?: emptyList()
        return listOf(apkPath) + others
    }

    private fun exposeToHostLoader(host: ClassLoader?, guest: ClassLoader) {
        try {
            val pf = ClassLoader::class.java.getDeclaredField("parent")
            pf.isAccessible = true
            val old = pf.get(host) as ClassLoader?
            if (old is GuestFallbackLoader) return
            pf.set(host, GuestFallbackLoader(old, host!!, guest))
            FileLog.d("SYSLOADER fallback parent installed")
        } catch (e: Throwable) {
            FileLog.d("SYSLOADER failed: " + e)
        }
    }

    private fun getGuestLoader(parent: ClassLoader?, apkPath: String): DexClassLoader {
        var loader = guestLoaders[apkPath]
        if (loader == null) {
            val dir = File(apkPath).parentFile
            val optDir = File(dir, "odex")
            optDir.mkdirs()
            val libDir = File(dir, "lib")
            loader = DexClassLoader(
                guestApks(apkPath).joinToString(":"),
                optDir.absolutePath,
                if (libDir.exists()) libDir.absolutePath else null,
                parent?.parent ?: parent
            )
            guestLoaders[apkPath] = loader
            exposeToHostLoader(parent, loader)
        }
        return loader
    }

    private fun setField(owner: Class<*>, name: String, target: Any, value: Any?) {
        val f = owner.getDeclaredField(name)
        f.isAccessible = true
        f.set(target, value)
    }

    private fun startGuestProviders(apkPath: String, ai: ApplicationInfo, loader: ClassLoader, ctx: Context): Int {
        var failed = 0
        // Fixed (2026-10-30, Viber registration crash part 2): getPackageArchiveInfo(apkPath, ...)
        // only parses the ONE apk file passed in — for a split/multi-APK guest (Viber ships this
        // way) any <provider> declared in a split manifest rather than base.apk was silently
        // missing from both GuestProviders.map AND GuestProviders.declared, so AmProxy's isDeclared()
        // guard (added earlier the same day) never recognized it as "ours" either and still let the
        // getContentProvider call fall through to the real system, reproducing the exact same
        // cross-UID SecurityException on com.viber.provider.contacts.ViberContactsProvider. Fix:
        // scan every APK in this guest's install directory (guestApks() — same list already used
        // for the classloader and for ai.splitSourceDirs), not just the one passed in, and merge
        // their provider lists.
        val list = ArrayList<android.content.pm.ProviderInfo>()
        val seenProvider = HashSet<String>()
        for (path in guestApks(apkPath)) {
            try {
                // Fixed 2026-11-01 (Viber Contacts sync investigation, root cause): this used to
                // request GET_PROVIDERS alone, with no GET_META_DATA — so every ProviderInfo built
                // from it got a NULL metaData bundle. Harmless for most providers, but fatal for
                // androidx.startup.InitializationProvider specifically: that provider's entire job
                // at onCreate() is to read its OWN <meta-data> entries to discover which
                // androidx.startup.Initializer classes to auto-run (WorkManager's initializer among
                // them) and run them. With metaData null, it finds nothing to initialize and does
                // nothing — no crash, no log, "guest provider started" fires clean either way.
                // Confirmed on-device: InitializationProvider "starts" successfully every launch,
                // but WorkManager's own job-result logging (WM-WorkerWrapper "Worker result
                // SUCCESS/FAILURE") never once fires inside the Viber clone process across a full
                // capture, even while its generic SystemJobService dispatch keeps returning true —
                // i.e. the OS-level job plumbing runs, but nothing is actually initialized to do the
                // work. This is very likely the real reason Viber's Contacts import (which almost
                // certainly runs as a WorkManager-scheduled background worker, not a live
                // AccountManager/SyncAdapter check — see AccProxy's file comment for why that
                // theory was ruled out) never completes. Adding GET_META_DATA so
                // InitializationProvider — and anything else that depends on its own provider
                // meta-data to self-configure — actually sees what it's supposed to.
                val info = ctx.packageManager.getPackageArchiveInfo(
                    path, PackageManager.GET_PROVIDERS or PackageManager.GET_META_DATA
                )
                for (pi in info?.providers ?: emptyArray()) {
                    // A provider could in principle show up in more than one archive scan; keep the first.
                    if (seenProvider.add(pi.name + "|" + pi.authority)) list.add(pi)
                }
            } catch (e: Throwable) {
                FileLog.d("startGuestProviders: archive scan failed for $path: $e")
            }
        }
        if (list.isEmpty()) return 0
        for (pi in list.sortedByDescending { it.initOrder }) {
            // Record every authority this guest manifest declares BEFORE trying to construct it,
            // success or failure — AmProxy needs this (GuestProviders.isDeclared) to tell "ours,
            // but failed to start" apart from a genuinely third-party authority, so a failed
            // guest provider never falls through to a real getContentProvider call that could
            // collide with a different, real app owning the same authority string on this device
            // (confirmed 2026-10-30: real Viber installed alongside a Viber clone collided on
            // com.viber.provider.contacts.ViberContactsProvider and crashed with a cross-UID
            // SecurityException once the query reached the real ActivityManagerService).
            pi.authority?.split(";")?.forEach { GuestProviders.declared.add(it) }
            try {
                pi.applicationInfo = ai
                val cp = loader.loadClass(pi.name).getDeclaredConstructor().newInstance() as ContentProvider
                AmProxy.setCallingPkg(cp, "com.ims134.dualhost")
                cp.attachInfo(ctx, pi)
                AmProxy.setCallingPkg(cp, "com.ims134.dualhost")
                pi.authority?.split(";")?.forEach { GuestProviders.map[it] = cp }
                FileLog.d("guest provider started: " + pi.name + " authority=" + pi.authority)
            } catch (e: Throwable) {
                FileLog.d("guest provider failed: " + pi.name + " " + e)
                failed++
            }
        }
        return failed
    }

    private fun getGuestEnv(hostApp: Application, apkPath: String, vuid: Int, root: File): GuestEnv {
        val existing = guestEnvs[apkPath]
        if (existing != null) return existing

        val pm = hostApp.packageManager
        val ai = pm.getPackageArchiveInfo(apkPath, 0)!!.applicationInfo!!
        ai.sourceDir = apkPath
        PmProxy.guestApk = apkPath
        // Added 2026-11-01 (Contacts Account/SyncAdapter investigation, re-ordering fix): this is
        // the EARLIEST point in this process's lifetime that the guest's own apk path is known —
        // strictly before CloneContext is even constructed below, and long before
        // app.attachBaseContext(ctx) ever runs, which is the earliest point any guest code (or
        // the framework's own SystemServiceRegistry, on this guest's behalf) could call
        // getSystemService(ACCOUNT_SERVICE) and cache a real AccountManager/IAccountManager
        // reference. The previous call site (CloneContext.getSystemService, still kept below as a
        // harmless redundant no-op via patchedOnce) only fired reactively and could run AFTER
        // that caching already happened — confirmed by log: the only transaction AccProxy's
        // PassthroughBinder ever saw was an unrelated tx=46 with a UUID argument, never a
        // getAccounts/getAccountsByType call, meaning whatever AccountManager instance Viber's
        // Contacts code actually used was never routed through our ServiceManager.sCache
        // replacement at all. Calling it here, before any of that can happen, is required for the
        // ServiceManager-cache approach to actually intercept the call it's meant to.
        AccProxy.patch(apkPath)
        ProcessClone.id = root.name.toIntOrNull() ?: -1
        ProcessClone.hostName = try {
            val tagDir = File(apkPath).parentFile
            val override = File(tagDir?.parentFile, "name_override.txt").takeIf { it.exists() }?.readText()?.trim()
            val mode = override ?: File(tagDir, "names.txt").readText().trim()
            FileLog.d("NAMEMODE " + tagDir?.parentFile?.name + " mode=" + mode + (if (override != null) " (override)" else " (scan)"))
            mode == "host"
        } catch (e: Throwable) { false }

        try {
            val gpkg = File(apkPath).parentFile?.parentFile?.name
            val wantsGuestProc = File(File(apkPath).parentFile?.parentFile, "process_name_guest.txt").exists()
            if (gpkg != null && wantsGuestProc) {
                val atClass = Class.forName("android.app.ActivityThread")
                val thread = atClass.getDeclaredMethod("currentActivityThread").invoke(null)
                val bf = atClass.getDeclaredField("mBoundApplication")
                bf.isAccessible = true
                val bound = bf.get(thread)!!
                val pf = bound.javaClass.getDeclaredField("processName")
                pf.isAccessible = true
                pf.set(bound, gpkg)

                try {
                    val af = bound.javaClass.getDeclaredField("appInfo")
                    af.isAccessible = true
                    val old = af.get(bound) as ApplicationInfo
                    val copy = ApplicationInfo(old)
                    copy.packageName = gpkg
                    af.set(bound, copy)
                } catch (e: Throwable) { FileLog.d("APPINFO failed: $e") }
                FileLog.d("PROCNAME set to " + gpkg + ", now reads " + Application.getProcessName())
            }
        } catch (e: Throwable) { FileLog.d("PROCNAME set failed: $e") }

        val splitPaths = guestApks(apkPath).drop(1).toTypedArray()
        ai.splitSourceDirs = splitPaths
        ai.splitPublicSourceDirs = splitPaths
        val raw = pm.getResourcesForApplication(ai)
        val initialCfg = android.content.res.Configuration(raw.configuration)
        initialCfg.updateFrom(hostApp.resources.configuration)
        // Effective darkness for this guest: Viber's own Appearance choice when Auto mode is off,
        // otherwise the system theme (see viberWantsDark).
        val wantDark = guestDarkChoice(ai.packageName, root)
        applyNight(initialCfg, wantDark)
        val resources = GuestResources(raw.assets, raw.displayMetrics, initialCfg, ai.packageName)
        val loader = getGuestLoader(hostApp.classLoader, apkPath)

        val appClassName = ai.className
        val app: Application = if (appClassName != null) {
            loader.loadClass(appClassName).getDeclaredConstructor().newInstance() as Application
        } else { Application() }

        val ctx = CloneContext(hostApp, root, vuid)
        ctx.guestResources = resources
        ctx.guestLoader = loader
        ctx.guestApplication = app
        val th = resources.newTheme()
        if (ai.theme != 0) {
            // Viber dark (env-side): contexts built from this theme (dialogs, popups, and the copy
            // ContextThemeWrapper.initializeTheme() pulls in) must use the Darknight family too.
            var appStyle = ai.theme
            if (ai.packageName == VIBER_PKG && wantDark) {
                val darkId = viberDarkStyle(resources, ai.packageName, appStyle)
                if (darkId != 0) {
                    appStyle = darkId
                    FileLog.d("VIBERDARK env theme 0x" + Integer.toHexString(darkId) + " " + resName(resources, darkId))
                } else {
                    FileLog.d("VIBERDARK env: no Darknight twin for 0x" + Integer.toHexString(ai.theme))
                }
            }
            th.applyStyle(appStyle, true)
        }
        ctx.guestTheme = th

        //added by another Claude account
        GuestSync.ctx = ctx

        // --- NATIVE ENGINE START ---
        if (ai.packageName in NATIVE_ENGINE_PACKAGES) {
            try {
                val guestPkg = ai.packageName
                val cmdlineFile = File(root, "cmdline")
                cmdlineFile.writeText(guestPkg)

                val statusFile = File(root, "proc_status.txt")
                statusFile.writeText(
                    "Name:\t$guestPkg\n" +
                            "Umask:\t0077\n" +
                            "State:\tS (sleeping)\n" +
                            "Tgid:\t10000\n" +
                            "Ngid:\t0\n" +
                            "Pid:\t10000\n" +
                            "PPid:\t1\n" +
                            "TracerPid:\t0\n" +
                            "Uid:\t10000\t10000\t10000\t10000\n" +
                            "Gid:\t10000\t10000\t10000\t10000\n" +
                            "FDSize:\t256\n" +
                            "Groups:\t10000\n"
                )

                // Fixed (2026-10-31, WebView SIGABRT): the old hand-written fake stat line below
                // had only 24 fields; a real /proc/pid/stat has 50+, and bionic's
                // pthread_getattr_np() -> __find_main_stack_limits() (called during WebView's
                // native init, confirmed from a device tombstone: "Abort message: 'couldn't parse
                // /proc/self/stat'") reads through to field 28 (startstack) to find the main
                // thread's stack bounds. Missing that field made bionic treat the parse failure as
                // fatal and abort the whole process. Fix: read the REAL /proc/self/stat (valid,
                // correctly field-complete, read here before NativeBridge.InstallHooks below
                // redirects that path) and only swap the "comm" field (the process name in
                // parens) for the guest package name; every other field — including startstack —
                // stays genuine, so any native code parsing it keeps working.
                val statFile = File(root, "proc_stat.txt")
                val realStat = try { File("/proc/self/stat").readText() } catch (e: Throwable) { null }
                if (realStat != null) {
                    val open = realStat.indexOf('(')
                    val close = realStat.lastIndexOf(')')
                    val fixed = if (open >= 0 && close > open)
                        realStat.substring(0, open) + "(" + guestPkg + ")" + realStat.substring(close + 1)
                    else realStat
                    statFile.writeText(fixed)
                } else {
                    FileLog.d("PROCSTAT: real /proc/self/stat unreadable, using minimal fallback (may abort native stack-limit parsers)")
                    statFile.writeText("10000 ($guestPkg) S 1 10000 10000 0 -1 4194560 0 0 0 0 0 0 0 0 20 0 1 0 100 0 0\n")
                }

                NativeBridge.setVirtualUid(10000 + vuid)
                NativeBridge.AddRule("/data/data/$guestPkg/", "${root.absolutePath}/")
                NativeBridge.AddRule("/proc/self/cmdline", cmdlineFile.absolutePath)
                NativeBridge.AddRule("/proc/self/status", statusFile.absolutePath)
                NativeBridge.AddRule("/proc/self/stat", statFile.absolutePath)
                NativeBridge.AddRule("/proc/${android.os.Process.myPid()}/status", statusFile.absolutePath)
                NativeBridge.AddRule("/proc/${android.os.Process.myPid()}/stat", statFile.absolutePath)
                NativeBridge.AddRule("/proc/${android.os.Process.myPid()}/cmdline", cmdlineFile.absolutePath)

                NativeBridge.InstallHooks(guestPkg)
                FileLog.d("Native engine configured for $guestPkg (UID: ${10000 + vuid})")
            } catch (e: Throwable) {
                FileLog.d("Native init failed: ${e.message}")
            }
        } else {
            FileLog.d("Native engine skipped for ${ai.packageName} (pure Java mode)")
        }
        // --- NATIVE ENGINE END ---

        // Set Viber's process-type enum to MAIN. Idempotent — safe to call twice.
        val setHai0Main = {
            try {
                val h = loader.loadClass("hai0")
                val fc = h.getDeclaredField("c").apply { isAccessible = true }
                FileLog.d("HAI0 before=${fc.get(null)}")
                val valueOf = h.getMethod("valueOf", String::class.java)
                val mainEnum = valueOf.invoke(null, "MAIN")
                fc.set(null, mainEnum)
                FileLog.d("HAI0 after=${fc.get(null)}")
            } catch (e: Throwable) {
                FileLog.d("HAI0 failed: $e")
            }
        }

        // CRITICAL: set MAIN BEFORE attachBaseContext so ViberApplication.attachBaseContext sees it
        if (viberHacksOn(ai.packageName)) setHai0Main()
        //if (viberHacksOn(ai.packageName)) ViberHacks.setArgV0("com.viber.voip")
        if (viberHacksOn(ai.packageName)) Helper.setArgV0("com.viber.voip")

        val attach = ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
        attach.isAccessible = true
        try { attach.invoke(app, ctx) } catch (e: InvocationTargetException) {
            var t: Throwable? = e.cause
            while (t != null) { FileLog.d("ATTACHFAIL " + t); t = t.cause }
            throw e
        }

        dumpEnvState(loader, "post-attachBaseContext")

        if (viberHacksOn(ai.packageName)) {
            try {
                val k = loader.loadClass("okd")
                val inst = k.getDeclaredConstructor().newInstance()
                val m = k.declaredMethods.first { it.name == "a" && it.parameterCount == 3 }
                m.isAccessible = true
                val contCls = loader.loadClass("kotlin.coroutines.Continuation")
                val emptyCtx = loader.loadClass("kotlin.coroutines.EmptyCoroutineContext").getField("INSTANCE").get(null)
                val cont = java.lang.reflect.Proxy.newProxyInstance(loader, arrayOf(contCls)) { _, mm, _ ->
                    if (mm.name == "getContext") emptyCtx else null
                }
                val r = m.invoke(inst, app, java.util.HashSet<Any>(), cont)
                FileLog.d("OKD returned " + r?.javaClass?.name)
            } catch (e: Throwable) {
                FileLog.d("OKD failed: " + (e.cause ?: e))
            }

            // Re-set in case okd reset it — idempotent
            setHai0Main()

            dumpEnvState(loader, "post-hai0")

            try {
                val k = loader.loadClass("dai0")
                val f = k.getDeclaredField("f").apply { isAccessible = true }.get(null)
                for (n in listOf("a", "b", "c", "d", "e")) {
                    val fld = k.getDeclaredField(n).apply { isAccessible = true }
                    FileLog.d("DAI0 f." + n + "=" + fld.get(f))
                }
            } catch (e: Throwable) { FileLog.d("DAI0 failed: " + e) }

            try {
                val va = loader.loadClass("com.viber.voip.ViberApplication")
                val prefs = va.getMethod("preferences", Context::class.java).invoke(null, app)
                val k = loader.loadClass("dai0")
                val f = k.getDeclaredField("f").apply { isAccessible = true }.get(null)
                val d = k.getDeclaredField("d").apply { isAccessible = true }
                d.set(f, prefs)
                val bField = k.getDeclaredField("b").apply { isAccessible = true }
                val sp = app.getSharedPreferences(ai.packageName + "_preferences", 0)
                bField.set(f, sp)
                FileLog.d("DAI0 after: f.d=" + d.get(f)?.javaClass?.name)
            } catch (e: Throwable) { FileLog.d("VA prefs failed: " + (e.cause ?: e)) }
        }
        if (viberHacksOn(ai.packageName)) ViberHacks.setFactory(loader, app)
        var failed = startGuestProviders(apkPath, ai, loader, ctx)
        if (failed > 0) {
            FileLog.d("PROVORDER " + failed + " provider(s) failed, retrying after Application.onCreate")
            try {
                val at = Class.forName("android.app.ActivityThread")
                val thread = at.getDeclaredMethod("currentActivityThread").invoke(null)
                val f = at.getDeclaredField("mInitialApplication")
                f.isAccessible = true
                f.set(thread, app)
            } catch (e: Throwable) { FileLog.d("INITAPP failed: " + e) }

            base.callApplicationOnCreate(app)
            try {
                val plc = loader.loadClass("androidx.lifecycle.ProcessLifecycleOwner")
                val get = plc.getMethod("get")
                get.invoke(null)
                FileLog.d("Pre-init ProcessLifecycleOwner succeeded")
            } catch (e: Throwable) {
                FileLog.d("Pre-init ProcessLifecycleOwner failed: $e")
            }
            dumpEnvState(loader, "post-onCreate")
            startGuestProviders(apkPath, ai, loader, ctx)
        } else {
            try {
                val at = Class.forName("android.app.ActivityThread")
                val thread = at.getDeclaredMethod("currentActivityThread").invoke(null)
                val f = at.getDeclaredField("mInitialApplication")
                f.isAccessible = true
                f.set(thread, app)
            } catch (e: Throwable) { FileLog.d("INITAPP failed: " + e) }

            base.callApplicationOnCreate(app)
            try {
                val plc = loader.loadClass("androidx.lifecycle.ProcessLifecycleOwner")
                val get = plc.getMethod("get")
                get.invoke(null)
                FileLog.d("Pre-init ProcessLifecycleOwner succeeded")
            } catch (e: Throwable) {
                FileLog.d("Pre-init ProcessLifecycleOwner failed: $e")
            }
            try {
                val id2 = loader.loadClass("id2")
                val bMethod = id2.getMethod("b")
                if (java.lang.reflect.Modifier.isStatic(bMethod.modifiers)) {
                    val result = bMethod.invoke(null)
                    FileLog.d("Pre-warmed id2.b() on main thread: $result")
                } else {
                    FileLog.d("id2.b() is not static - need alternate warmup")
                }
            } catch (e: Throwable) {
                FileLog.d("Pre-warm id2.b() failed: $e")
            }
            dumpEnvState(loader, "post-onCreate")
        }

        GuestReceivers.register(hostApp, apkPath, loader)
        FileLog.d("guest application ready: " + app.javaClass.name)
        try { NetDiag.start(app) } catch (e: Throwable) { FileLog.d("NetDiag start failed: " + e) }
        try { GuestSync.start(ctx) } catch (e: Throwable) { FileLog.d("GuestSync start failed: " + e) }


        val env = GuestEnv(loader, resources, app, root)
        guestEnvs[apkPath] = env
        GuestEnvs.map[apkPath] = env
        return env
    }

    private fun isSystemDark(context: Context): Boolean {
        try {
            val uiManager = context.getSystemService(Context.UI_MODE_SERVICE) as? android.app.UiModeManager
            val nm = uiManager?.nightMode
            val sysMode = context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
            val setting = try { android.provider.Settings.Secure.getInt(context.contentResolver, "ui_night_mode", 1) } catch (e: Throwable) { -1 }
            val result = nm == android.app.UiModeManager.MODE_NIGHT_YES ||
                    sysMode == android.content.res.Configuration.UI_MODE_NIGHT_YES ||
                    setting == 2
            FileLog.d("ISDARK nightMode=$nm cfgUiMode=0x${Integer.toHexString(context.resources.configuration.uiMode)} sysMode=0x${Integer.toHexString(sysMode)} setting=$setting -> $result")
            return result
        } catch (e: Throwable) { FileLog.d("ISDARK failed: $e") }
        return false
    }

    // Overrides the night bit OUTRIGHT instead of only setting it: the guest config starts as a copy
    // of the host's, so when Viber picks Light on a dark system the night bit has to be actively
    // cleared, or AppCompat/Material views inside a light Viber would still inflate their night
    // variants while Viber's own chrome uses the light family.
    private fun applyNight(cfg: android.content.res.Configuration, dark: Boolean) {
        val mask = android.content.res.Configuration.UI_MODE_NIGHT_MASK
        cfg.uiMode = (cfg.uiMode and mask.inv()) or
                (if (dark) android.content.res.Configuration.UI_MODE_NIGHT_YES
                 else android.content.res.Configuration.UI_MODE_NIGHT_NO)
    }

    /**
     * The guest's own appearance prefs file. CloneContext hands the guest
     * "clone_<pkg>_<clone>_<name>", so the same file is reachable from the host context by that
     * exact name — no XML parsing, and it is the same SharedPreferences instance the guest writes to.
     */
    private fun viberPrefsFor(root: File): SharedPreferences? {
        return try {
            val pkg = root.parentFile?.name ?: return null
            hostApp().getSharedPreferences("clone_" + pkg + "_" + root.name + "_com.viber.voip.ViberPrefs", 0)
        } catch (e: Throwable) {
            FileLog.d("VIBERPREFS failed: $e")
            null
        }
    }

    /**
     * Viber's own appearance choice. "pref_auto_theme_key" is Viber's Auto mode switch (its UI text:
     * "Viber matches your device's light or dark mode"); with Auto OFF, "current_theme" names the
     * family the user picked — "darknight" is the Darknight family. Only when the choice is unknown
     * (older build, no keys yet) do we fall back to the system theme.
     */
    private fun viberWantsDark(root: File): Boolean {
        val sys = isSystemDark(hostApp())
        val p = viberPrefsFor(root) ?: return sys
        val auto = p.getBoolean("pref_auto_theme_key", true)
        val theme = p.getString("current_theme", null)
        val want = if (auto) sys
        else theme != null && (theme.contains("dark", true) || theme.contains("night", true))
        FileLog.d("VIBERTHEME auto=" + auto + " current_theme=" + theme +
                " systemDark=" + sys + " -> dark=" + want)
        return want
    }

    // Effective darkness for one guest: Viber follows its own Appearance setting, every other guest
    // follows the system.
    private fun guestDarkChoice(pkg: String, root: File): Boolean =
        if (pkg == VIBER_PKG) viberWantsDark(root) else isSystemDark(hostApp())

    // --- live Appearance switches ---------------------------------------------------------------
    // Viber's Appearance screen only writes pref_auto_theme_key / current_theme and then asks
    // AppCompat to re-evaluate. AppCompat reads the ACTIVITY's Configuration, which in the clone is a
    // snapshot decided at env/activity creation — and deliberately night-CLEARED when Viber picked
    // Light on a dark system. So turning Auto back ON did nothing: the config still claimed the
    // system was light, and it only caught up on the next real system uiMode change. Watching the
    // guest's own prefs (same SharedPreferences instance the guest writes) lets us re-decide at once,
    // rewrite the config and relaunch the visible activity so the Darknight mapping re-applies.
    private val appearanceWatch = HashMap<String, SharedPreferences.OnSharedPreferenceChangeListener>()

    fun watchGuestAppearance(sp: SharedPreferences, root: File) {
        if (root.parentFile?.name != VIBER_PKG) return
        val key = root.absolutePath
        if (appearanceWatch.containsKey(key)) return
        val l = SharedPreferences.OnSharedPreferenceChangeListener { _, pref ->
            if (pref == "pref_auto_theme_key" || pref == "current_theme") onAppearanceChanged(root)
        }
        appearanceWatch[key] = l   // SharedPreferencesImpl holds listeners weakly: keep a strong ref
        sp.registerOnSharedPreferenceChangeListener(l)
        FileLog.d("VIBERWATCH registered for $key")
    }

    private fun onAppearanceChanged(root: File) {
        try {
            val env = guestEnvs.values.firstOrNull { it.root.absolutePath == root.absolutePath } ?: return
            val want = viberWantsDark(root)
            val cur = env.resources.configuration
            val curDark = (cur.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                    android.content.res.Configuration.UI_MODE_NIGHT_YES
            if (curDark == want) {
                FileLog.d("VIBERWATCH config already dark=" + want)
                return
            }
            val cfg = android.content.res.Configuration(cur)
            applyNight(cfg, want)
            env.resources.updateConfiguration(cfg, env.resources.displayMetrics)
            FileLog.d("VIBERWATCH config night -> " + want + ", relaunching guest activity")
            Handler(Looper.getMainLooper()).post {
                // Viber itself may already have rebuilt its activity by the time this runs; only
                // relaunch when the live activity still carries the stale configuration.
                try {
                    val act = topGuestActivity(env.loader)
                    if (act == null) {
                        FileLog.d("VIBERWATCH no live guest activity to relaunch")
                    } else {
                        val liveDark = (act.resources.configuration.uiMode and
                                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                                android.content.res.Configuration.UI_MODE_NIGHT_YES
                        if (liveDark == want) {
                            FileLog.d("VIBERWATCH " + act.javaClass.name + " already rebuilt dark=" + want)
                        } else {
                            FileLog.d("VIBERWATCH recreate " + act.javaClass.name)
                            act.recreate()
                        }
                    }
                } catch (t: Throwable) {
                    FileLog.d("VIBERWATCH relaunch failed: $t")
                }
            }
        } catch (t: Throwable) {
            FileLog.d("VIBERWATCH failed: $t")
        }
    }

    private fun topGuestActivity(loader: ClassLoader): Activity? = try {
        val at = Class.forName("android.app.ActivityThread")
        val thread = at.getDeclaredMethod("currentActivityThread").invoke(null)
        val f = at.getDeclaredField("mActivities").apply { isAccessible = true }
        val records = f.get(thread) as Map<*, *>
        records.values.asSequence().mapNotNull { rec ->
            if (rec == null) null else
                rec.javaClass.getDeclaredField("activity").apply { isAccessible = true }.get(rec) as? Activity
        }.lastOrNull { it.javaClass.classLoader === loader && !it.isFinishing }
    } catch (t: Throwable) {
        FileLog.d("VIBERWATCH activity scan failed: $t")
        null
    }

    private fun resName(r: Resources, id: Int): String =
        try { r.getResourceName(id) } catch (t: Throwable) { "0x" + Integer.toHexString(id) }

    /**
     * Maps a Viber light-chain style to its Darknight twin, or 0 when the style has no twin:
     * "Theme.Viber.Home" -> "Darknight.Theme.Viber.Home",
     * "Base.Theme.Viber.Light" -> "Base.Darknight.Theme.Viber.Light".
     * Names are resolved through the guest resource table at runtime, so Viber renumbering its ids in
     * an update does not break this.
     */
    private fun viberDarkStyle(res: Resources, gp: String, styleId: Int): Int {
        if (styleId == 0) return 0
        val full = try { res.getResourceName(styleId) } catch (t: Throwable) { return 0 }
        val bare = full.substringAfter("/")
        val dark = when {
            bare.startsWith("Base.Theme.") -> "Base." + VIBER_DARK_PREFIX + bare.removePrefix("Base.")
            bare.startsWith("Theme.") -> VIBER_DARK_PREFIX + bare
            else -> return 0
        }
        return res.getIdentifier(dark, "style", gp)
    }

    private fun freshResources(apkPath: String, dark: Boolean): Resources {
        val pm = hostApp().packageManager
        val ai = pm.getPackageArchiveInfo(apkPath, 0)!!.applicationInfo!!
        ai.sourceDir = apkPath
        val splits = guestApks(apkPath).drop(1).toTypedArray()
        ai.splitSourceDirs = splits
        ai.splitPublicSourceDirs = splits
        val raw = pm.getResourcesForApplication(ai)
        val sysCfg = hostApp().resources.configuration
        val cfg = android.content.res.Configuration(raw.configuration)
        cfg.updateFrom(sysCfg)
        applyNight(cfg, dark)
        return GuestResources(raw.assets, raw.displayMetrics, cfg, ai.packageName)
    }

    private fun prepareGuest(activity: Activity?, icicle: Bundle?) {
        if (activity == null) return
        val launchIntent = activity.intent
        var apkExtra = launchIntent?.getStringExtra("apkPath")
        val guestClassExtra = launchIntent?.getStringExtra("guestClass")
        var vuid = launchIntent?.getIntExtra("vuid", 0) ?: 0
        var rootOverride: File? = null

        // 2026-11-27 (Viber "switch theme" crash): Viber's own relaunch (recreate() after the user
        // changes Appearance) reached callActivityOnCreate WITHOUT our stub extras. These guards used
        // to bail out silently, so the activity kept the HOST DualApplication and Viber's first
        // onCreate statement — a cast of getApplication() to its own interface — died with
        // "com.ims134.dualhost.DualApplication does not implement hus". The guest ClassLoader is proof
        // that this activity is ours, so resolve the cached env through it and continue with that
        // env's clone root instead of leaving the instance half-initialised. The root MUST come from
        // the env (not a fresh rootFor()): a wrong root silently points the activity at another
        // clone's prefs/databases.
        if (apkExtra == null || guestClassExtra != activity.javaClass.name) {
            val byLoader = guestEnvs.entries.firstOrNull { it.value.loader === activity.javaClass.classLoader }
            FileLog.d("PREP fallback: hasIntent=" + (launchIntent != null) +
                    " keys=" + launchIntent?.extras?.keySet()?.joinToString() +
                    " apkPath=" + apkExtra + " guestClass=" + guestClassExtra +
                    " act=" + activity.javaClass.name + " envByLoader=" + (byLoader != null))
            if (byLoader == null) return
            apkExtra = byLoader.key
            rootOverride = byLoader.value.root
            // vuid selects which HostService stub receives redirected service intents; recover the
            // slot from the process name (com.ims134.dualhost:p3 -> 3) since the intent is gone.
            val slotFromProc = ProcessClone.realName.substringAfterLast(":", "")
                .removePrefix("p").toIntOrNull()
            if (slotFromProc != null) vuid = slotFromProc
        }
        val apkPath = apkExtra ?: return

        try {
            val root = rootOverride ?: rootFor(activity.dataDir, launchIntent, vuid)
            root.mkdirs()
            val env = getGuestEnv(hostApp(), apkPath, vuid, root)
            // Effective darkness for THIS activity: Viber's own Appearance choice when Auto mode is
            // off, otherwise the system theme (see viberWantsDark).
            val gpChoice = File(apkPath).parentFile?.parentFile?.name ?: ""
            val dark = guestDarkChoice(gpChoice, root)
            val res = freshResources(apkPath, dark)
            val sysCfg = android.content.res.Configuration(hostApp().resources.configuration)
            applyNight(sysCfg, dark)
            res.updateConfiguration(sysCfg, res.displayMetrics)
            env.resources.updateConfiguration(sysCfg, res.displayMetrics)

            icicle?.classLoader = env.loader
            setField(android.view.ContextThemeWrapper::class.java, "mResources", activity, res)
            setField(Activity::class.java, "mApplication", activity, env.application)

            val f = ContextWrapper::class.java.getDeclaredField("mBase")
            f.isAccessible = true
            val origBase = f.get(activity) as Context
            val ctx = CloneContext(origBase, root, vuid)
            ctx.guestResources = res
            ctx.guestLoader = env.loader
            ctx.guestApplication = env.application

            // Build a fresh guest theme bound to the fresh GuestResources for the base context.
            // Without this, ContextThemeWrapper.initializeTheme() copies the *host* theme below
            // our guest theme and the AppCompat marker disappears.
            try {
                val aiForTheme = hostApp().packageManager.getPackageArchiveInfo(
                    apkPath, PackageManager.GET_ACTIVITIES or PackageManager.GET_META_DATA
                )?.applicationInfo
                val t = res.newTheme()
                if (aiForTheme != null && aiForTheme.theme != 0) t.applyStyle(aiForTheme.theme, true)
                ctx.guestTheme = t
            } catch (e: Throwable) {
                FileLog.d("THEME base init failed: $e")
            }

            f.set(activity, ctx)

            // ===== THEME FIX =====
            try {
                val guestActName = activity.javaClass.name
                val pmi = hostApp().packageManager.getPackageArchiveInfo(
                    apkPath, PackageManager.GET_ACTIVITIES or PackageManager.GET_META_DATA
                )
                if (pmi != null) {
                    val actInfo = pmi.activities?.firstOrNull { it.name == guestActName }
                    val actTheme = actInfo?.theme ?: 0
                    val appTheme = pmi.applicationInfo?.theme ?: 0
                    var effective = if (actTheme != 0) actTheme else appTheme
                    FileLog.d("THEME guest=$guestActName actTheme=0x${Integer.toHexString(actTheme)} " +
                            "appTheme=0x${Integer.toHexString(appTheme)} effective=0x${Integer.toHexString(effective)}")
                    // Dark mode: swap the manifest style for its Darknight twin. Viber's dark theme is
                    // a parallel style family, not a -night variant of this one (the apk has no night
                    // resources for any Viber style), so the only way to get Viber's real dark colours
                    // inside the clone is to select that family ourselves.
                    val gpTheme = gpChoice
                    if (gpTheme == VIBER_PKG && dark) {
                        val darkId = viberDarkStyle(res, gpTheme, effective)
                        if (darkId != 0) {
                            FileLog.d("VIBERDARK theme 0x" + Integer.toHexString(darkId) + " " +
                                    resName(res, darkId) + " instead of 0x" + Integer.toHexString(effective))
                            effective = darkId
                        } else {
                            FileLog.d("VIBERDARK no Darknight twin for 0x" + Integer.toHexString(effective))
                        }
                    }
                    if (effective != 0) {
                        // The Activity's cached theme object can survive from before we replaced
                        // mResources, in which case setTheme() applies a guest style id onto a theme
                        // bound to the HOST table. Drop it first (ContextThemeWrapper.mTheme holds it
                        // on this API level), then let setTheme() rebuild it from the guest resources.
                        var cleared = false
                        var k: Class<*>? = activity.javaClass
                        while (k != null && !cleared) {
                            for (f in k.declaredFields) {
                                if (!java.lang.reflect.Modifier.isStatic(f.modifiers) &&
                                    f.type == android.content.res.Resources.Theme::class.java) {
                                    try {
                                        f.isAccessible = true
                                        f.set(activity, null)
                                        FileLog.d("THEME cleared ${k.name}.${f.name}")
                                        cleared = true
                                        break
                                    } catch (t: Throwable) { /* hidden api denied */ }
                                }
                            }
                            k = k.superclass
                        }
                        activity.setTheme(effective)
                        FileLog.d("THEME applied 0x${Integer.toHexString(effective)} to $guestActName (cleared=$cleared)")

                        // Belt and braces: even with the cached theme dropped, apply the effective
                        // style straight onto the live theme so the activity never renders with the
                        // throwaway theme the framework created during attach(). THEMEDIAG below
                        // verifies the result: live theme must match a freshly built guest theme.
                        try {
                            activity.theme.applyStyle(effective, true)
                            FileLog.d("THEME force-applied 0x${Integer.toHexString(effective)} onto live theme")
                        } catch (t: Throwable) {
                            FileLog.d("THEME force-apply failed: $t")
                        }

                        // Diagnostic (log only): compare the live activity theme against a theme
                        // freshly built from guest resources for the same style id.
                        try {
                            var k2: Class<*>? = activity.javaClass
                            while (k2 != null) {
                                for (f in k2.declaredFields) {
                                    if (f.type.name.contains("Theme", ignoreCase = true)) {
                                        try {
                                            f.isAccessible = true
                                            val v = f.get(activity)
                                            FileLog.d("FIELDSCAN ${k2.name}.${f.name} type=${f.type.name} value=${v?.javaClass?.name ?: "null"} static=${java.lang.reflect.Modifier.isStatic(f.modifiers)}")
                                        } catch (t: Throwable) {
                                            FileLog.d("FIELDSCAN ${k2.name}.${f.name} type=${f.type.name} read failed: $t")
                                        }
                                    }
                                }
                                k2 = k2.superclass
                            }
                        } catch (t: Throwable) { FileLog.d("FIELDSCAN failed: $t") }

                        // Step 323 diagnostic (log only): is the activity's theme object the right one?
                        try {
                            val rr = activity.resources
                            val nm = try { rr.getResourceName(effective) } catch (e: Throwable) { "?" }
                            val gp = File(apkPath).parentFile?.parentFile?.name ?: ""
                            val attrId = rr.getIdentifier("windowActionBar", "attr", gp)
                            val fresh = res.newTheme()
                            fresh.applyStyle(effective, true)
                            val tv1 = android.util.TypedValue()
                            val tv2 = android.util.TypedValue()
                            val a1 = activity.theme.resolveAttribute(android.R.attr.windowBackground, tv1, true)
                            val a2 = fresh.resolveAttribute(android.R.attr.windowBackground, tv2, true)
                            val w1 = attrId != 0 && activity.theme.resolveAttribute(attrId, android.util.TypedValue(), true)
                            val w2 = attrId != 0 && fresh.resolveAttribute(attrId, android.util.TypedValue(), true)
                            FileLog.d("THEMEDIAG name=" + nm + " attr=0x" + Integer.toHexString(attrId) +
                                    " activityTheme: bg=" + a1 + "/0x" + Integer.toHexString(tv1.resourceId) + " abar=" + w1 +
                                    " freshGuestTheme: bg=" + a2 + "/0x" + Integer.toHexString(tv2.resourceId) + " abar=" + w2)
                        } catch (e: Throwable) { FileLog.d("THEMEDIAG failed: " + e) }
                    }
                }
            } catch (e: Throwable) {
                FileLog.d("THEME failed: $e")
            }
            // ===== END THEME FIX =====

            // Diagnostic (Viber clone stays light while the system is dark): shows whether the
            // guest even SEES the night bit in its Configuration, and what the live theme's
            // windowBackground actually resolves to. If resUiMode carries night but the colour is
            // still a light one, the resource path is healthy and the app is just picking its
            // light style in code — see ViberThemeProbe.
            try {
                val uiMode = res.configuration.uiMode
                val tv = android.util.TypedValue()
                val theme = activity.theme
                theme.resolveAttribute(android.R.attr.windowBackground, tv, true)
                val color = try {
                    when (tv.type) {
                        android.util.TypedValue.TYPE_INT_COLOR_ARGB8,
                        android.util.TypedValue.TYPE_INT_COLOR_RGB8,
                        android.util.TypedValue.TYPE_INT_COLOR_ARGB4,
                        android.util.TypedValue.TYPE_INT_COLOR_RGB4 ->
                            "0x" + Integer.toHexString(tv.data)
                        else -> if (tv.resourceId != 0)
                            "0x" + Integer.toHexString(res.getColor(tv.resourceId, theme))
                        else "unresolved(type=0x" + Integer.toHexString(tv.type) + ")"
                    }
                } catch (t: Throwable) { "error($t)" }
                FileLog.d("THEMECHECK guest=" + activity.javaClass.name +
                        " systemDark=" + isSystemDark(hostApp()) +
                        " resUiMode=0x" + Integer.toHexString(uiMode) +
                        " night=" + ((uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                                android.content.res.Configuration.UI_MODE_NIGHT_YES) +
                        " windowBackground=" + color)
            } catch (t: Throwable) { FileLog.d("THEMECHECK failed: " + t) }

            FileLog.d("guest ready: " + activity.javaClass.name)
        } catch (e: Throwable) {
            FileLog.d("prepareGuest failed: $e")
        }
    }

    private val slotClasses: List<Class<*>> = Slots.stubs
    private val seenLaunch = HashSet<String>()

    private fun logLaunch(i: Intent?) {
        if (i == null) return
        val s = "LAUNCH action=" + i.action + " comp=" + i.component?.flattenToShortString() + " pkg=" + i.`package`
        if (synchronized(seenLaunch) { seenLaunch.add(s) }) FileLog.d(s)
    }

    private val seenSettingsFix = HashSet<String>()

    // Added 2026-11-01 (Contacts "Allow access"/"syncing incomplete" investigation): confirmed
    // via device log that Viber's own in-process checkSelfPermission(READ_CONTACTS/WRITE_CONTACTS)
    // already returns GRANTED for this clone ("PERM android.permission.READ_CONTACTS -> 0") — so
    // this was never a permission problem. A side-by-side comparison against a competing
    // app-cloning app showed the real difference: tapping "Allow access" there opens THAT app's
    // own settings screen and contacts sync immediately; in DualHost it opens the REAL,
    // separately-installed Viber's system App Info page instead, which does nothing for this
    // clone. The reason: that tap launches Settings.ACTION_APPLICATION_DETAILS_SETTINGS with
    // data="package:com.viber.voip" — an IMPLICIT intent (action+data, no component), so
    // redirect() below (which only acts on intent.component) never touches it, and it reaches
    // the real system unmodified, resolving to the real, separately-installed app. Fix: rewrite
    // just this one intent's data to point at HOST's own real, actually-granted package instead,
    // so the user lands on a settings page that's real and already correctly permissioned.
    private fun fixSettingsIntent(i: Intent?): Intent? {
        if (i == null) return i
        if (i.action != android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS) return i
        val data = i.data ?: return i
        if (data.scheme != "package") return i
        val ssp = data.schemeSpecificPart ?: return i
        val gp = PmProxy.guestApk?.let { File(it).parentFile?.parentFile?.name } ?: return i
        if (ssp != gp && ssp != "com.ims134.dualhost") return i
        if (ssp == "com.ims134.dualhost") return i
        val n = Intent(i)
        n.data = Uri.fromParts("package", "com.ims134.dualhost", null)
        val key = "settingsfix|" + ssp
        if (synchronized(seenSettingsFix) { seenSettingsFix.add(key) })
            FileLog.d("SETTINGS redirected ACTION_APPLICATION_DETAILS_SETTINGS pkg=" + ssp + " -> com.ims134.dualhost")
        return n
    }

    // Added 2026-11-03: implicit intents the guest addresses to its OWN package (action + package,
    // no component — e.g. Viber's SYSTEM_DIALOG from "Contacts syncing incomplete / Retry") are
    // resolved against the guest's own manifest (GuestIntentResolver) so redirect() below sees a
    // component and routes them through the stub swap instead of letting them hit the real,
    // separately-installed app (cross-uid SecurityException crash).
    private fun resolveGuestImplicit(target: Activity?, intent: Intent?): Intent? {
        if (intent == null || intent.component != null) return intent
        val pkgName = intent.`package` ?: return intent
        val action = intent.action ?: return intent
        val apk = target?.intent?.getStringExtra("apkPath") ?: PmProxy.guestApk ?: return intent
        val guestPkg = if (ProcessClone.realName == "com.viber.voip") "com.viber.voip"
        else File(apk).parentFile?.parentFile?.name
        if (pkgName != guestPkg) return intent
        val cls = GuestIntentResolver.resolveActivity(hostApp(), apk, action) ?: return intent
        val n = Intent(intent)
        n.component = ComponentName(pkgName, cls)
        FileLog.d("IMPLICIT resolved action=" + action + " pkg=" + pkgName + " -> " + cls)

        FileLog.d("IMPLICIT extras=" + intent.extras?.keySet()?.joinToString { k -> k + "=" + intent.extras?.get(k) })
        return n
    }

    private fun redirect(target: Activity?, intent0: Intent?): Intent? {
        val intent = try { resolveGuestImplicit(target, intent0) } catch (e: Throwable) { intent0 }
        val cn = intent?.component ?: return intent
        if (cn.className.startsWith("com.ims134.dualhost.")) return intent

        // Step 321: dedicated Viber process (named com.viber.voip, no slot number)
        /*
        if (ProcessClone.realName == "com.viber.voip") {
            if (cn.packageName != "com.ims134.dualhost" && cn.packageName != "com.viber.voip") return intent
            val apkV = target?.intent?.getStringExtra("apkPath") ?: PmProxy.guestApk ?: return intent
            val iv = Intent(intent)
            iv.component = ComponentName("com.ims134.dualhost", StubViber::class.java.name)
            iv.putExtra("guestClass", cn.className)
            iv.putExtra("guestPackage", "com.viber.voip")
            iv.putExtra("vuid", 0)
            iv.putExtra("cloneId", ProcessClone.id)
            iv.putExtra("apkPath", apkV)
            if (iv.identifier == null) iv.identifier = "dh:" + cn.className
            FileLog.d("REDIRECT viber " + cn.className)
            return iv
        }
        */

        val from = target?.intent
        var apk = from?.getStringExtra("apkPath")
        var pkg = from?.getStringExtra("guestPackage")
        var vuid = from?.getIntExtra("vuid", -1) ?: -1

        if (apk == null) {
            apk = PmProxy.guestApk ?: return intent
            vuid = ProcessClone.slot ?: return intent
            pkg = File(apk).parentFile?.parentFile?.name
        }
        if (cn.packageName != "com.ims134.dualhost" && cn.packageName != pkg) return intent
        if (vuid !in slotClasses.indices) return intent

        val i = Intent(intent)
        i.component = ComponentName(cn.packageName, slotClasses[vuid].name)
        i.putExtra("guestClass", cn.className)
        i.putExtra("guestPackage", pkg)
        i.putExtra("vuid", vuid)
        i.putExtra("cloneId", if (ProcessClone.id >= 0) ProcessClone.id else vuid)
        i.putExtra("cloneNumber", from?.getIntExtra("cloneNumber", 0) ?: 0)
        i.putExtra("apkPath", apk)
        if (i.identifier == null) i.identifier = "dh:" + cn.className

        // Restored (was missing per Handoff 13/14): a guest relaunching its OWN current activity
        // (e.g. CLEAR_TOP self-relaunch) must reuse that instance rather than stack a duplicate on
        // top of itself, or the Back button no longer returns out of the app (Spotify regression).
        val fromGuestClass = from?.getStringExtra("guestClass")
        if (fromGuestClass != null && fromGuestClass == cn.className) {
            i.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            FileLog.d("SINGLETOP added for self-relaunch of " + cn.className)
        }

        FileLog.d("REDIRECT " + cn.className + " -> slot " + vuid)
        return i
    }

    private fun proxyUri(u: Uri, vuid: Int): Uri {
        if (u.scheme != "content") return u
        val auth = u.authority ?: return u
        if (auth.startsWith("com.ims134.dualhost.fp")) return u
        if (GuestProviders.find(auth) == null) return u
        val b = Uri.Builder().scheme("content").authority("com.ims134.dualhost.fp$vuid").appendPath(auth)
        for (s in u.pathSegments) b.appendPath(s)
        return b.build()
    }

    private fun rewriteUris(intent: Intent?): Intent? {
        if (intent == null) return null
        val vuid = ProcessClone.slot ?: return intent
        if (vuid !in slotClasses.indices) return intent
        if (GuestProviders.map.isEmpty()) return intent
        val i = Intent(intent)

        val d = i.data
        if (d != null) {
            val nd = proxyUri(d, vuid)
            if (nd != d) i.setDataAndType(nd, i.type)
        }
        for (key in listOf(MediaStore.EXTRA_OUTPUT, Intent.EXTRA_STREAM)) {
            val v = i.extras?.get(key)
            if (v is Uri) {
                val nv = proxyUri(v, vuid)
                if (nv != v) i.putExtra(key, nv)
            }
        }

        // Restored (was missing per Handoff 13/14): a guest sharing/returning multiple files via
        // ClipData (not just EXTRA_STREAM) needs each item's content:// URI proxied the same way,
        // or the receiving provider lookup fails for anything but the single-URI case above.
        val cd = i.clipData
        if (cd != null) {
            var changed = false
            val items = ArrayList<ClipData.Item>()
            for (idx in 0 until cd.itemCount) {
                val item = cd.getItemAt(idx)
                val u = item.uri
                if (u != null) {
                    val nu = proxyUri(u, vuid)
                    if (nu != u) {
                        items.add(ClipData.Item(nu))
                        changed = true
                        continue
                    }
                }
                items.add(item)
            }
            if (changed) {
                val newCd = ClipData(cd.description, items[0])
                for (idx in 1 until items.size) newCd.addItem(items[idx])
                i.clipData = newCd
                FileLog.d("CLIPDATA rewrote " + items.size + " item(s)")
            }
        }

        return i
    }

    fun execStartActivity(
        who: Context?, contextThread: IBinder?, token: IBinder?, target: Activity?,
        intent: Intent?, requestCode: Int, options: Bundle?
    ): ActivityResult? {
        val redirected = try { redirect(target, intent) } catch (e: Throwable) { intent }
        val settingsFixed = try { fixSettingsIntent(redirected) } catch (e: Throwable) { redirected }
        val real = try { rewriteUris(settingsFixed) } catch (e: Throwable) { settingsFixed }
        logLaunch(real)

        // Restored (was missing per Handoff 13/14): when a guest launches an external sign-in
        // page (e.g. an https:// OAuth flow for Element X) with no component of its own, remember
        // which slot/apk is waiting so ReturnLinkActivity (reads ret_slot/ret_apk from the
        // "dualhost" prefs) can forward the callback link back into the right clone. Unproven:
        // not yet exercised end-to-end against a real sign-in flow — verify with a log line
        // (RETSAVE ... / ReturnLink: ...) before relying on it.
        try {
            if (real?.component == null && real?.data?.scheme?.startsWith("http", ignoreCase = true) == true) {
                val retVuid = ProcessClone.slot
                val retApk = PmProxy.guestApk
                if (retVuid != null && retApk != null) {
                    hostApp().getSharedPreferences("dualhost", Context.MODE_PRIVATE).edit()
                        .putInt("ret_slot", retVuid).putString("ret_apk", retApk).apply()
                    FileLog.d("RETSAVE slot=" + retVuid + " apk=" + retApk)
                }
            }
        } catch (e: Throwable) { FileLog.d("RETSAVE failed: " + e) }

        val m = Instrumentation::class.java.getDeclaredMethod(
            "execStartActivity", Context::class.java, IBinder::class.java, IBinder::class.java,
            Activity::class.java, Intent::class.java, Int::class.javaPrimitiveType, Bundle::class.java
        )
        m.isAccessible = true
        try {
            return m.invoke(base, who, contextThread, token, target, real, requestCode, options) as ActivityResult?
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    // Added (2026-10-30, Viber registration crash part 4): Context.startActivities(Intent[]) —
    // used to launch an activity with a synthetic back stack (Viber's
    // ActivationController.continueWelcomeFlowAfterRegistration does this to land on HomeActivity
    // after registration finishes) — goes through this PLURAL Instrumentation method and
    // IActivityTaskManager, a completely different path from the singular execStartActivity above
    // that was never intercepted. The un-redirected explicit component (cmp=com.viber.voip/
    // .HomeActivity) reached the real system and crashed with a SecurityException, cross-UID
    // against the real, separately-installed Viber app's own HomeActivity — same family of bug as
    // the earlier ContentProvider crashes, just through a launch path we hadn't covered yet. This
    // directly answers the handoff's "does execStartActivity/redirects handle every kind of
    // launch" question for this specific call: previously no, now routed through the exact same
    // redirect()/rewriteUris() logic already proven for the singular path, per intent in the array.
    fun execStartActivities(
        who: Context?, contextThread: IBinder?, token: IBinder?, target: Activity?,
        intents: Array<Intent>?, options: Bundle?
    ) {
        val fixed = intents?.map { i ->
            val redirected = try { redirect(target, i) } catch (e: Throwable) { i }
            val settingsFixed = try { fixSettingsIntent(redirected) } catch (e: Throwable) { redirected }
            val real = try { rewriteUris(settingsFixed) } catch (e: Throwable) { settingsFixed }
            logLaunch(real)
            real
        }?.toTypedArray()

        val m = Instrumentation::class.java.getDeclaredMethod(
            "execStartActivities", Context::class.java, IBinder::class.java, IBinder::class.java,
            Activity::class.java, Array<Intent>::class.java, Bundle::class.java
        )
        m.isAccessible = true
        try {
            m.invoke(base, who, contextThread, token, target, fixed ?: intents, options)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    private fun rootFor(dataDir: File, intent: Intent?, vuid: Int): File {
        val pkg = intent?.getStringExtra("guestPackage") ?: "unknown"
        val clone = intent?.getIntExtra("cloneId", vuid) ?: vuid
        return File(dataDir, "virtual/" + pkg + "/" + clone)
    }

    override fun newActivity(cl: ClassLoader?, className: String?, intent: Intent?): Activity {
        FileLog.d("ENTER newActivity className=" + className + " hasIntent=" + (intent != null) +
                " guestClass=" + intent?.getStringExtra("guestClass") +
                " apkPath=" + intent?.getStringExtra("apkPath") +
                " extrasKeys=" + intent?.extras?.keySet()?.joinToString())
        val guestClass = intent?.getStringExtra("guestClass")
        val apkPath = intent?.getStringExtra("apkPath")
        if (guestClass != null && apkPath != null) {
            val wantClone = intent?.getIntExtra("cloneId", -1) ?: -1
            val otherApp = guestEnvs.keys.any { it != apkPath }
            val otherClone = guestEnvs.isNotEmpty() && wantClone >= 0 && ProcessClone.id >= 0 && wantClone != ProcessClone.id
            if (otherApp || otherClone) {
                // Restored (was missing per Handoff 13/14): log why a launch was refused instead of
                // silently returning StaleLaunch() — otherwise a wrong-slot launch looks identical
                // to any other failure in the log.
                FileLog.d("SLOT CONFLICT: wanted apk=" + apkPath + " clone=" + wantClone +
                        " but slot holds apk=" + guestEnvs.keys.joinToString() + " clone=" + ProcessClone.id)
                return StaleLaunch()
            }
            FileLog.d("HOOK newActivity: swapping $className -> $guestClass")
            val loader = getGuestLoader(cl, apkPath)
            try {
                val app0 = hostApp()
                val vuid0 = intent!!.getIntExtra("vuid", 0)
                val root0 = rootFor(app0.dataDir, intent, vuid0)
                root0.mkdirs()
                getGuestEnv(app0, apkPath, vuid0, root0)
            } catch (e: Throwable) { }
            val activity = loader.loadClass(guestClass).getDeclaredConstructor().newInstance() as Activity
            // Step 324: Android calls activity.setTheme(stub theme) right after attach and builds the theme from
            // HOST resources. If mThemeResource already holds that id, that call does nothing, so prepareGuest
            // can build the theme later from GUEST resources. mResources is not touched (Viber's
            // attachBaseContext needs it to be null).
            //
            // Step 328 fix (Calculator Theme.AppCompat crash): this preset was gated to the Viber
            // dedicated-process branch only. Every other (slot-based) guest never got it, so Android's
            // own automatic setTheme(stub) call ran unopposed, baked the live theme object around HOST
            // resources before prepareGuest ever touched it, and no later setTheme()/applyStyle() call
            // could undo that (confirmed by log: THEMEDIAG showed activityTheme stuck on a plain system
            // resource for Calculator's MainActivity even after both were tried). Generalized to all
            // guests, not just Viber, since nothing about the preset itself is Viber-specific.
            run {
                try {
                    val stubTheme = hostApp().applicationInfo.theme
                    setField(android.view.ContextThemeWrapper::class.java, "mThemeResource", activity, stubTheme)
                    FileLog.d("THEMERES preset 0x" + Integer.toHexString(stubTheme) + " for " + guestClass)
                } catch (e: Throwable) { FileLog.d("THEMERES failed: " + e) }
            }
            return activity
        }
        FileLog.d("newActivity FALLTHROUGH to base (no guestClass/apkPath) for " + className)
        return base.newActivity(cl, className, intent)
    }

    override fun callApplicationOnCreate(app: Application?) {
        base.callApplicationOnCreate(app)
    }

    override fun callActivityOnCreate(activity: Activity?, icicle: Bundle?) {
        FileLog.d("HOOK callActivityOnCreate: " + activity?.javaClass?.name)
        prepareGuest(activity, icicle)
        base.callActivityOnCreate(activity, icicle)
    }

    override fun callActivityOnCreate(activity: Activity?, icicle: Bundle?, persistentState: PersistableBundle?) {
        FileLog.d("HOOK callActivityOnCreate(persistable): " + activity?.javaClass?.name)
        prepareGuest(activity, icicle)
        base.callActivityOnCreate(activity, icicle, persistentState)
    }
}

private class GuestFallbackLoader(
    parent: ClassLoader?, private val host: ClassLoader, private val guest: ClassLoader
) : ClassLoader(parent) {
    private val hostFind = try {
        dalvik.system.BaseDexClassLoader::class.java.getDeclaredMethod("findClass", String::class.java).apply { isAccessible = true }
    } catch (e: Throwable) { null }

    override fun findClass(name: String): Class<*> {
        try {
            if (hostFind != null) return hostFind.invoke(host, name) as Class<*>
        } catch (e: Throwable) { }
        return guest.loadClass(name)
    }
}
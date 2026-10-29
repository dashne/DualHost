package com.ims134.dualhost

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.res.Resources
import android.os.Bundle
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
    val application: Application
)

class HostInstrumentation(private val base: Instrumentation) : Instrumentation() {

    companion object {
        @Volatile var current: HostInstrumentation? = null
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
        val info = ctx.packageManager.getPackageArchiveInfo(apkPath, PackageManager.GET_PROVIDERS) ?: return 0
        val list = info.providers ?: return 0
        for (pi in list.sortedByDescending { it.initOrder }) {
            try {
                pi.applicationInfo = ai
                val cp = loader.loadClass(pi.name).getDeclaredConstructor().newInstance() as ContentProvider
                cp.attachInfo(ctx, pi)
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
                } catch (e: Throwable) { FileLog.d("APPINFO failed: " + e) }
                FileLog.d("PROCNAME set to " + gpkg + ", now reads " + Application.getProcessName())
            }
        } catch (e: Throwable) { FileLog.d("PROCNAME set failed: " + e) }

        val splitPaths = guestApks(apkPath).drop(1).toTypedArray()
        ai.splitSourceDirs = splitPaths
        ai.splitPublicSourceDirs = splitPaths
        val raw = pm.getResourcesForApplication(ai)
        val resources = GuestResources(raw.assets, raw.displayMetrics, raw.configuration, ai.packageName)
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
        if (ai.theme != 0) th.applyStyle(ai.theme, true)
        ctx.guestTheme = th

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

                val statFile = File(root, "proc_stat.txt")
                statFile.writeText("10000 ($guestPkg) S 1 10000 10000 0 -1 4194560 0 0 0 0 0 0 0 0 20 0 1 0 100 0 0\n")

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
        if (viberHacksOn(ai.packageName)) ViberHacks.setArgV0("com.viber.voip")

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
        val env = GuestEnv(loader, resources, app)
        guestEnvs[apkPath] = env
        GuestEnvs.map[apkPath] = env
        return env
    }

    private fun freshResources(apkPath: String): Resources {
        val pm = hostApp().packageManager
        val ai = pm.getPackageArchiveInfo(apkPath, 0)!!.applicationInfo!!
        ai.sourceDir = apkPath
        val splits = guestApks(apkPath).drop(1).toTypedArray()
        ai.splitSourceDirs = splits
        ai.splitPublicSourceDirs = splits
        val raw = pm.getResourcesForApplication(ai)
        return GuestResources(raw.assets, raw.displayMetrics, raw.configuration, ai.packageName)
    }

    private fun prepareGuest(activity: Activity?, icicle: Bundle?) {
        if (activity == null) return
        val intent = activity.intent ?: return
        val apkPath = intent.getStringExtra("apkPath") ?: return
        if (intent.getStringExtra("guestClass") != activity.javaClass.name) return
        val vuid = intent.getIntExtra("vuid", 0)

        try {
            val root = rootFor(activity.dataDir, intent, vuid)
            root.mkdirs()
            val env = getGuestEnv(hostApp(), apkPath, vuid, root)
            val res = freshResources(apkPath)
            env.resources.updateConfiguration(res.configuration, res.displayMetrics)

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
                    val effective = if (actTheme != 0) actTheme else appTheme
                    FileLog.d("THEME guest=$guestActName actTheme=0x${Integer.toHexString(actTheme)} " +
                            "appTheme=0x${Integer.toHexString(appTheme)} effective=0x${Integer.toHexString(effective)}")
                    if (effective != 0) {
                        // API 37 removed ContextThemeWrapper.mTheme — walk hierarchy for any Resources.Theme field
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

                        // Step 328 fix (Calculator crash, Theme.AppCompat InflateException): on this API
                        // level the Resources.Theme field-walk above never finds anything to clear
                        // (cleared=false even when nothing crashes), so setTheme() alone leaves the
                        // activity's live theme object built from the wrong chain. THEMEDIAG already
                        // proves a theme freshly built from guest resources resolves abar=true for the
                        // same style id, so force-apply that same style directly onto the live theme
                        // instead of relying on setTheme() to rebuild it.
                        try {
                            activity.theme.applyStyle(effective, true)
                            FileLog.d("THEME force-applied 0x${Integer.toHexString(effective)} onto live theme")
                        } catch (t: Throwable) {
                            FileLog.d("THEME force-apply failed: $t")
                        }

                        // Unproven, diagnostic only: force-apply above ran without throwing but THEMEDIAG
                        // below still shows abar=false afterward, meaning applyStyle() had no real effect —
                        // most likely activity.theme is still bound to the HOST AssetManager, so a
                        // guest-numbered style id resolves against the wrong resource table. The exact-type
                        // field walk above also never finds anything to clear (cleared=false even here),
                        // so the cached theme does not live where that walk looks. Log every field anywhere
                        // in the hierarchy whose type NAME mentions Theme (not exact-type match) so the next
                        // fix targets the real field instead of guessing again.
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

            FileLog.d("guest ready: " + activity.javaClass.name)
        } catch (e: Throwable) {
            FileLog.d("prepareGuest failed: " + e)
        }
    }

    private val slotClasses: List<Class<*>> = Slots.stubs
    private val seenLaunch = HashSet<String>()

    private fun logLaunch(i: Intent?) {
        if (i == null) return
        val s = "LAUNCH action=" + i.action + " comp=" + i.component?.flattenToShortString() + " pkg=" + i.`package`
        if (synchronized(seenLaunch) { seenLaunch.add(s) }) FileLog.d(s)
    }

    private fun redirect(target: Activity?, intent: Intent?): Intent? {
        val cn = intent?.component ?: return intent
        if (cn.className.startsWith("com.ims134.dualhost.")) return intent

        // Step 321: dedicated Viber process (named com.viber.voip, no slot number)
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
        val b = Uri.Builder().scheme("content").authority("com.ims134.dualhost.fp" + vuid).appendPath(auth)
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
        val real = try { rewriteUris(redirected) } catch (e: Throwable) { redirected }
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

    private fun rootFor(dataDir: File, intent: Intent?, vuid: Int): File {
        val pkg = intent?.getStringExtra("guestPackage") ?: "unknown"
        val clone = intent?.getIntExtra("cloneId", vuid) ?: vuid
        return File(dataDir, "virtual/" + pkg + "/" + clone)
    }

    override fun newActivity(cl: ClassLoader?, className: String?, intent: Intent?): Activity {
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
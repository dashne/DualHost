package com.ims134.dualhost

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import java.io.File

// Apps whose native engine (open64/openat64/... hooks) is actually installed by
// HostInstrumentation.getGuestEnv's "NATIVE ENGINE" block. Shared with
// HostInstrumentation so the two lists can't drift apart. Only these packages'
// literal /data/data/<pkg>/... paths get redirected underneath by the native
// hooks — every other ("pure Java mode") guest must get a real path directly,
// since nothing will ever redirect a fake one for it.
val NATIVE_ENGINE_PACKAGES = setOf("com.viber.voip", "org.telegram.messenger")

class CloneContext(
    base: Context,
    private val root: File,
    private val vuid: Int
) : ContextWrapper(base) {

    // Filled in by HostInstrumentation for guest apps
    var guestApplication: Application? = null
    var guestResources: Resources? = null
    var guestLoader: ClassLoader? = null

    var guestTheme: Resources.Theme? = null

    private val seenAsk = HashSet<String>()
    private fun ask(s: String) {
        if (synchronized(seenAsk) { seenAsk.add(s) }) FileLog.d("ASK " + s)
    }

    private fun dir(name: String): File {
        val d = File(root, name)
        d.mkdirs()
        return d
    }


    override fun getSystemService(name: String): Any? {
        val s = super.getSystemService(name)
        if (s == null) {
            FileLog.d("DIAG getSystemService('$name') = NULL")
        }
        if (name == JOB_SCHEDULER_SERVICE && s is android.app.job.JobScheduler &&
            s !is LoggingJobScheduler) {
            return LoggingJobScheduler(s, vuid, root.parentFile?.name ?: "")
        }
        // Added 2026-11-01, rewritten same day: AccProxy no longer touches the AccountManager
        // instance this returns at all (its old mService-field reflection is a confirmed dead
        // end on this device — see AccProxy's file comment). It patches ServiceManager's own
        // in-process service cache instead, so it only needs the guest's apk path, not this
        // service object. Kept here (rather than only in HookInstaller) so it's also applied the
        // first time anything asks for ACCOUNT_SERVICE through this context, same as before.
        if (name == ACCOUNT_SERVICE && s != null) {
            val apk = PmProxy.guestApk
            if (apk != null) AccProxy.patch(apk)
        }
        return s
    }

    private val seenAppInfoCaller = HashSet<String>()

    override fun getApplicationInfo(): ApplicationInfo {
        val guestPkg = root.parentFile?.name
        val apk = PmProxy.guestApk
        if (guestPkg == null || apk == null) return super.getApplicationInfo()

        // Diagnostic (2026-10-29, camera ANR): log the immediate caller the first time only,
        // so a runaway per-frame polling loop (suspected after org.fossify.camera logged
        // thousands of back-to-back getApplicationInfo() calls ~16ms apart with no other
        // activity, consistent with a Choreographer-paced loop that never exits) shows up
        // once in the log instead of being drowned by its own flood.
        run {
            val caller = Throwable().stackTrace.getOrNull(1)
            val key = guestPkg + "|" + (caller?.className ?: "?") + "." + (caller?.methodName ?: "?")
            if (synchronized(seenAppInfoCaller) { seenAppInfoCaller.add(key) }) {
                FileLog.d("APPINFOCALLER guest=$guestPkg caller=${caller?.className}.${caller?.methodName}")
            }
        }

        val cached = appInfoCache
        if (cached != null && cached.first == apk) {
            // No log here on purpose: if this really is a per-frame polling loop, logging every
            // cache hit would flood files/dhlog just as badly as the uncached version did (and
            // the disk I/O from FileLog itself would still help starve the main thread).
            // APPINFOCALLER above already fires once and tells us who's calling.
            return cached.second
        }

        return try {
            val base = super.getApplicationInfo()
            val guestAi = baseContext.packageManager
                .getPackageArchiveInfo(apk, PackageManager.GET_META_DATA)?.applicationInfo
            if (guestAi == null) return base

            val out = ApplicationInfo(base)
            out.packageName = guestPkg
            out.sourceDir = apk
            out.publicSourceDir = apk
            out.dataDir = root.absolutePath
            out.nativeLibraryDir = File(apk).parentFile?.resolve("lib")?.absolutePath ?: ""
            out.metaData = guestAi.metaData
            out.className = guestAi.className ?: out.className
            out.theme = guestAi.theme
            out.labelRes = guestAi.labelRes
            out.icon = guestAi.icon
            out.flags = guestAi.flags
            out.targetSdkVersion = guestAi.targetSdkVersion
            out.minSdkVersion = guestAi.minSdkVersion
            // Perf fix (2026-10-29): getPackageArchiveInfo() re-parses the whole APK manifest
            // from disk every call. Cached because it's immutable per apkPath and this method
            // can be hit at a high rate (see APPINFOCALLER diagnostic above) — parsing the
            // manifest on every single call is expensive enough on its own to starve the main
            // thread if anything calls this in a loop.
            appInfoCache = Pair(apk, out)
            FileLog.d("DIAG getApplicationInfo() -> guest $guestPkg, meta=${out.metaData?.size() ?: 0} keys")
            out
        } catch (e: Throwable) {
            FileLog.d("DIAG getApplicationInfo() override failed: $e")
            super.getApplicationInfo()
        }
    }

    private var appInfoCache: Pair<String, ApplicationInfo>? = null

    private fun logSvc(what: String, i: Intent) {
        val cn = i.component
        val s = what + " comp=" + (cn?.className ?: "null") +
                " pkg=" + (cn?.packageName ?: i.`package`) + " action=" + i.action
        //if (synchronized(seenSvc) { seenSvc.add(s) }) FileLog.d( "SVC " + s)
    }
    private fun fixService(i: Intent): Intent {
        val cn = i.component ?: return i
        if (cn.className.startsWith("com.ims134.dualhost.")) return i
        if (cn.packageName != "com.ims134.dualhost" && cn.packageName != (root.parentFile?.name ?: "")) return i
        val apk = PmProxy.guestApk ?: return i
        if (vuid !in Slots.services.indices) return i
        val n = Intent(i)
        if (n.identifier == null) n.identifier = "dh:" + cn.className
        n.component = ComponentName("com.ims134.dualhost", Slots.services[vuid].name)
        n.putExtra("dh_class", cn.className)
        n.putExtra("dh_apk", apk)
        n.putExtra("dh_vuid", vuid)
        n.putExtra("dh_clone", root.name.toIntOrNull() ?: vuid)
        n.putExtra("dh_pkg", root.parentFile?.name ?: "")
        //FileLog.d( "SVC redirected " + cn.className + " -> HostService" + vuid)
        return n
    }

    override fun startService(service: Intent): ComponentName? {
        logSvc("startService", service)
        //return super.startService(service)
        return super.startService(fixService(service))
    }

    override fun startForegroundService(service: Intent): ComponentName? {
        logSvc("startForegroundService", service)
        //return super.startForegroundService(service)
        return super.startForegroundService(fixService(service))
    }

    override fun stopService(name: Intent): Boolean {
        logSvc("stopService", name)
        return super.stopService(fixService(name))
    }

    override fun bindService(service: Intent, conn: ServiceConnection, flags: Int): Boolean {
        logSvc("bindService", service)
        return super.bindService(fixService(service), conn, flags)
    }

    override fun bindService(
        service: Intent, flags: Int, executor: java.util.concurrent.Executor, conn: ServiceConnection
    ): Boolean {
        logSvc("bindService(executor)", service)
        return super.bindService(fixService(service), flags, executor, conn)
    }

    private fun logBc(what: String, i: Intent) {
        val cn = i.component
        val s = what + " comp=" + (cn?.className ?: "null") +
                " pkg=" + (cn?.packageName ?: i.`package`) + " action=" + i.action
        //if (synchronized(seenSvc) { seenSvc.add("BC " + s) }) FileLog.d( "BC " + s)
    }

    override fun sendBroadcast(intent: Intent) {
        logBc("sendBroadcast", intent)
        super.sendBroadcast(intent)
    }

    override fun sendBroadcast(intent: Intent, receiverPermission: String?) {
        logBc("sendBroadcast(perm)", intent)
        super.sendBroadcast(intent, receiverPermission)
    }

    private val seenPerm = HashSet<String>()

    private fun logPerm(p: String, r: Int) {
        val s = p + " -> " + r
        // Turned on 2026-11-01 (Contacts "Allow access"/"syncing incomplete" investigation):
        // checkPermission, getPackageInfo, shouldShowRequestPermissionRationale and AppOps all
        // now independently confirm READ_CONTACTS/WRITE_CONTACTS granted for HOST, yet Viber's
        // own Contacts screen still shows the card — meaning whatever Viber is actually seeing
        // in-process has never actually been observed. This was the one missing data point.
        if (synchronized(seenPerm) { seenPerm.add(s) }) FileLog.d( "PERM " + s)
    }

    // Per-clone permission gate. The real OS grant (super.checkPermission/checkSelfPermission)
    // is never touched or misrepresented to anyone outside this process — this only changes
    // what CloneContext itself, which the guest already runs entirely inside of, answers when
    // asked. Only takes effect if the real OS grant is already true (a clone can never appear
    // MORE permitted than the real app actually is) and only for permissions in ClonePerms.GATED.
    private fun gate(permission: String, osResult: Int): Int {
        if (osResult != PackageManager.PERMISSION_GRANTED) return osResult
        if (permission !in ClonePerms.GATED) return osResult
        val pkg = root.parentFile?.name ?: return osResult
        val cloneId = root.name.toIntOrNull() ?: return osResult
        return if (ClonePerms.isAllowed(baseContext, pkg, cloneId, permission))
            PackageManager.PERMISSION_GRANTED
        else
            PackageManager.PERMISSION_DENIED
    }

    override fun checkPermission(permission: String, pid: Int, uid: Int): Int {
        val r = gate(permission, super.checkPermission(permission, pid, uid))
        logPerm(permission, r)
        return r
    }

    override fun checkSelfPermission(permission: String): Int {
        val r = gate(permission, super.checkSelfPermission(permission))
        logPerm(permission, r)
        return r
    }

    // Apps that must see their own package name, and who may see it:
    //  OWN_CLASSES_ONLY: only the app's own classes get the guest name
    //  NOT_FRAMEWORK: everyone gets the guest name, except the framework and DualHost
    // Every other app keeps the host name, because obfuscated libraries
    // (Media3, ...) can't be told apart from the app's own code by class name.
    private enum class NameRule { OWN_CLASSES_ONLY, NOT_FRAMEWORK }


    private val seenPkgAsk = HashSet<String>()

    override fun getPackageName(): String {
        val host = super.getPackageName()
        val guest = root.parentFile?.name ?: return host
        //if (guest == "com.anthropic.claude") return host
        if (ProcessClone.hostName) return host
        val rule = NameRule.NOT_FRAMEWORK

        // The first caller that is not a wrapper or plain Java decides
        var caller = "?"
        var result = host
        for (f in Throwable().stackTrace) {
            val c = f.className
            if (c.startsWith("com.ims134.dualhost.CloneContext")) continue
            if (c == "android.content.ContextWrapper" || c == "android.view.ContextThemeWrapper") continue
            if (c.startsWith("java.") || c.startsWith("dalvik.") || c.startsWith("kotlin.")) continue
            caller = c + "." + f.methodName
            // Step 267: Play services modules check "does this uid own this package?" in another process
            if (f.fileName?.startsWith(":com.google.android.gms") == true) {
                if (synchronized(seenPkgAsk) { seenPkgAsk.add("gms") })
                    FileLog.d( "PKGNAME gms module caller gets host name: " + c)
                return host
            }


            // Step 272: a caller whose stack is inside the Play services client asks about our uid
            if (Throwable().stackTrace.take(6).any { it.className.startsWith("com.google.android.gms.common.") }) {
                if (synchronized(seenPkgAsk) { seenPkgAsk.add("gmscommon|" + c) })
                    FileLog.d( "PKGNAME gms.common stack, host name for " + c + "." + f.methodName)
                return host
            }
            // Reverted 2026-10-31 (§3 of the Contacts "Allow access" investigation): this branch
            // briefly returned `guest` here on the theory that the permission-framework classes
            // (PermissionManager, etc.) ask on behalf of the current context and so should see
            // the guest's own identity. That theory turned out to be only half right and risks
            // being actively wrong: it's PmProxy's checkPermission/getPackageInfo redirects (not
            // this name-spoofing) that are responsible for making any package-name-keyed lookup
            // that reaches the real system resolve to HOST's real grant state; this branch
            // returning `guest` instead just reintroduces the original cross-UID problem for any
            // permission-framework call this file's own redirects don't yet cover. Back to `host`,
            // which is also consistent with every other non-"own app code" caller below.
            if (c.startsWith("android.permission.")) {
                if (synchronized(seenPkgAsk) { seenPkgAsk.add("permfw|" + c) })
                    FileLog.d( "PKGNAME permission-framework caller gets host name: " + c)
                return host
            }

            if (rule != null) {
                result = when (rule) {
                    NameRule.OWN_CLASSES_ONLY -> if (c.startsWith("$guest.")) guest else host
                    NameRule.NOT_FRAMEWORK ->
                        if (c.startsWith("android.") || c.startsWith("com.android.") ||
                            c.startsWith("com.ims134.dualhost.")) host else guest
                }
            }
            break
        }

        // Step 271 (log only): who asks for the package name in own-name mode?
        val key = guest + "|" + caller
        if (synchronized(seenPkgAsk) { seenPkgAsk.add(key) }) {
            FileLog.d( "PKGSURVEY guest=" + guest + " caller=" + caller +
                    " returned=" + (if (result == host) "host" else "guest"))
        }
        return result
    }


    override fun getPackageManager(): PackageManager {
        val pm = super.getPackageManager()
        PmProxy.patch(pm)
        // Added 2026-10-31: see PermProxy's file comment — a PackageManager created before
        // PermProxy.install() ran (or whose PermissionManager was constructed independently of
        // mPM) still holds the real IPermissionManager, same reason PmProxy.patch(pm) exists.
        PermProxy.patch(this)
        return pm
    }

    // The guest's own Application, Resources and ClassLoader
    override fun getApplicationContext(): Context {
        return guestApplication ?: super.getApplicationContext()
    }

    override fun getResources(): Resources {
        return guestResources ?: super.getResources()
    }

    override fun getTheme(): Resources.Theme {
        return guestTheme ?: super.getTheme()
    }

    override fun getAssets(): AssetManager {
        return guestResources?.assets ?: super.getAssets()
    }

    override fun getClassLoader(): ClassLoader {
        return guestLoader ?: super.getClassLoader()
    }

    override fun createConfigurationContext(overrideConfiguration: Configuration): Context {
        val c = CloneContext(super.createConfigurationContext(overrideConfiguration), root, vuid)
        val gr = guestResources
        if (gr != null) {
            val cfg = Configuration(gr.configuration)
            cfg.updateFrom(overrideConfiguration)
            @Suppress("DEPRECATION")
            c.guestResources = GuestResources(gr.assets, gr.displayMetrics, cfg, root.parentFile?.name ?: "")
        }
        c.guestLoader = guestLoader
        c.guestApplication = guestApplication
        //FileLog.d( "CloneContext.createConfigurationContext called")
        return c
    }

    // External folders are per clone, so one clone's downloads are not shared and can be deleted
    private fun extRoot(type: String?, cache: Boolean): File? {
        val base = (if (cache) super.getExternalCacheDir() else super.getExternalFilesDir(null)) ?: return null
        val d = File(base, "virtual/" + (root.parentFile?.name ?: "unknown") + "/" + root.name +
                (if (type != null) "/" + type else ""))
        d.mkdirs()
        return d
    }

    override fun getExternalFilesDir(type: String?): File? = extRoot(type, false)

    override fun getExternalCacheDir(): File? = extRoot(null, true)

    override fun getExternalFilesDirs(type: String?): Array<File?> = arrayOf(extRoot(type, false))

    override fun getExternalCacheDirs(): Array<File?> = arrayOf(extRoot(null, true))

    // Data separation (Steps 25 and 28)
    override fun getDataDir(): File = root

    override fun getFilesDir(): File = dir("files")

    override fun getCacheDir(): File = dir("cache")

    override fun getDir(name: String?, mode: Int): File = dir("app_" + name)

    override fun getNoBackupFilesDir(): File = dir("no_backup")

    override fun getCodeCacheDir(): File = dir("code_cache")

    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences
    {
        ask("prefs " + name)
        val pkg = root.parentFile?.name ?: "unknown"
        val result = super.getSharedPreferences("clone_" + pkg + "_" + root.name + "_" + name, mode)
        FileLog.d("DIAG getSharedPreferences('$name') -> ${result.all.size} keys")
        return super.getSharedPreferences("clone_" + pkg + "_" + root.name + "_" + name, mode)
    }

    override fun getDatabasePath(name: String): File {
        if (name.startsWith("/")) return File(name)
        val dbDir = dir("databases")
        val guestPkg = root.parentFile?.name ?: "unknown"
        // Only apps with the native engine actually installed (Viber/Telegram, see
        // HostInstrumentation's NATIVE ENGINE block) get the short fake path that matches
        // their real on-device layout (needed for Viber's fixed-offset native parsing) —
        // our native open hook redirects that literal path to the virtual one underneath.
        // Fixed (Step: Calculator SQLITE_CANTOPEN): every other, "pure Java mode" guest has
        // no native hook to redirect it, so handing it a fake host-literal path meant SQLite
        // tried to open a directory that genuinely does not exist on disk. Those guests now
        // get the real virtual directory directly.
        if (guestPkg in NATIVE_ENGINE_PACKAGES) {
            return File("/data/data/$guestPkg/databases/$name")
        }
        return File(dbDir, name)
    }

    override fun openOrCreateDatabase(
        name: String,
        mode: Int,
        factory: SQLiteDatabase.CursorFactory?
    ): SQLiteDatabase {
        return openOrCreateDatabase(name, mode, factory, null)
    }

    override fun openOrCreateDatabase(
        name: String,
        mode: Int,
        factory: SQLiteDatabase.CursorFactory?,
        errorHandler: DatabaseErrorHandler?
    ): SQLiteDatabase {
        val f = getDatabasePath(name)
        // <-- removed the mkdirs line; dir("databases") in getDatabasePath handles it
        var flags = SQLiteDatabase.CREATE_IF_NECESSARY
        if (mode and MODE_ENABLE_WRITE_AHEAD_LOGGING != 0)
        {
            flags = flags or SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING
        }
        return SQLiteDatabase.openDatabase(f.path, factory, flags, errorHandler)
    }

    override fun deleteDatabase(name: String): Boolean {
        return SQLiteDatabase.deleteDatabase(getDatabasePath(name))
    }

    override fun databaseList(): Array<String> {
        return dir("databases").list() ?: emptyArray()
    }
}
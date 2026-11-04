package com.ims134.dualhost

import android.accounts.Account
import android.app.Service
import android.content.AbstractThreadedSyncAdapter
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SyncResult
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.content.res.Resources
import android.database.ContentObserver
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import org.xmlpull.v1.XmlPullParser
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

// Runs the guest's own SyncAdapter in-process. The real SyncManager can never do it: the clone
// is not an installed package and its fake account does not exist in the real AccountManager,
// so requestSync() reaches nothing and Viber reports "NO Service".
object GuestSync {
    private const val NS = "http://schemas.android.com/apk/res/android"

    class Info(
        val serviceClass: String,
        val authority: String?,
        val accountType: String?,
        val userVisible: Boolean,
        val uploading: Boolean
    )

    // Set once from HostInstrumentation (see edit below).
    @Volatile var ctx: Context? = null

    @Volatile private var info: Info? = null
    @Volatile private var triedDiscover = false
    @Volatile private var adapter: AbstractThreadedSyncAdapter? = null
    private val running = AtomicBoolean(false)
    private val exec = Executors.newSingleThreadExecutor()

    private val seen = HashSet<String>()
    private fun logOnce(s: String) { if (synchronized(seen) { seen.add(s) }) FileLog.d(s) }

    private fun attr(p: android.content.res.XmlResourceParser, res: Resources, n: String): String? {
        val rid = p.getAttributeResourceValue(NS, n, 0)
        if (rid != 0) {
            try { return res.getString(rid) } catch (e: Throwable) { }
        }
        return p.getAttributeValue(NS, n)
    }

    @Synchronized
    fun discover(): Info? {
        info?.let { return it }
        if (triedDiscover) return null
        val apk = PmProxy.guestApk ?: return null
        triedDiscover = true
        try {
            val app = Class.forName("android.app.ActivityThread")
                .getDeclaredMethod("currentApplication").invoke(null) as android.app.Application
            val pi = app.packageManager.getPackageArchiveInfo(
                apk, PackageManager.GET_SERVICES or PackageManager.GET_META_DATA
            ) ?: return null
            val svc = pi.services?.firstOrNull {
                it.metaData?.containsKey("android.content.SyncAdapter") == true
            } ?: run { logOnce("GSYNC no SyncAdapter service in guest manifest"); return null }
            val resId = svc.metaData.getInt("android.content.SyncAdapter")

            val am = AssetManager::class.java.getDeclaredConstructor().newInstance()
            val cookie = AssetManager::class.java.getMethod("addAssetPath", String::class.java)
                .invoke(am, apk) as Int
            if (cookie == 0) return null
            @Suppress("DEPRECATION")
            val res = Resources(am, app.resources.displayMetrics, app.resources.configuration)
            val p = res.getXml(resId)
            var authority: String? = null
            var type: String? = null
            var visible = true
            var upload = true
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG && p.name == "sync-adapter") {
                    authority = attr(p, res, "contentAuthority")
                    type = attr(p, res, "accountType")
                    visible = attr(p, res, "userVisible") != "false"
                    upload = attr(p, res, "supportsUploading") != "false"
                    break
                }
                ev = p.next()
            }
            val i = Info(svc.name, authority, type, visible, upload)
            info = i
            logOnce("GSYNC discovered service=" + i.serviceClass + " authority=" + authority + " accountType=" + type)
            return i
        } catch (e: Throwable) {
            logOnce("GSYNC discover failed: $e")
            return null
        }
    }

    // Looks for the guest's AbstractThreadedSyncAdapter on the service object and on the binder
    // onBind() returned. Checks instance AND static fields (the usual Android sample keeps the
    // adapter in a static field), and goes one level into non-framework objects.
    private fun findAdapter(o: Any?, depth: Int = 0): AbstractThreadedSyncAdapter? {
        if (o == null) return null
        if (o is AbstractThreadedSyncAdapter) return o
        if (depth > 2) return null
        var k: Class<*>? = o.javaClass
        while (k != null && k != Any::class.java) {
            for (f in k.declaredFields) {
                try {
                    if (f.type.isPrimitive) continue
                    f.isAccessible = true
                    val v = f.get(if (java.lang.reflect.Modifier.isStatic(f.modifiers)) null else o) ?: continue
                    if (v is AbstractThreadedSyncAdapter) return v
                    val n = v.javaClass.name
                    if (depth < 2 && !n.startsWith("java.") && !n.startsWith("android.os.") &&
                        !n.startsWith("kotlin.") && !n.startsWith("android.content.Context")) {
                        findAdapter(v, depth + 1)?.let { return it }
                    }
                } catch (e: Throwable) { }
            }
            k = k.superclass
        }
        return null
    }

    private fun describe(o: Any?): String {
        if (o == null) return "null"
        val sb = StringBuilder(o.javaClass.name + " {")
        var k: Class<*>? = o.javaClass
        while (k != null && k != Any::class.java) {
            for (f in k.declaredFields) sb.append(' ').append(if (java.lang.reflect.Modifier.isStatic(f.modifiers)) "static " else "")
                .append(f.type.name).append(' ').append(f.name).append(';')
            k = k.superclass
        }
        return sb.append(" }").toString()
    }

    @Synchronized
    private fun getAdapter(c: Context, i: Info): AbstractThreadedSyncAdapter? {
        adapter?.let { return it }
        try {
            val cls = c.classLoader.loadClass(i.serviceClass)
            val ctor = cls.getDeclaredConstructor().apply { isAccessible = true }
            val svc = ctor.newInstance() as Service
            val ab = ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
            ab.isAccessible = true
            ab.invoke(svc, c)
            svc.onCreate()
            val binder = svc.onBind(Intent("android.content.SyncAdapter"))
            val a = findAdapter(svc) ?: findAdapter(binder)
            if (a == null) {
                logOnce("GSYNC could not locate AbstractThreadedSyncAdapter in " + i.serviceClass)
                logOnce("GSYNC service=" + describe(svc))
                logOnce("GSYNC binder=" + describe(binder))
            }
            adapter = a
            return a
        } catch (e: Throwable) {
            logOnce("GSYNC adapter create failed: " + (e.cause ?: e))
            return null
        }
    }

    fun run(account: Account, authority: String, extras: Bundle?) {
        val c = ctx ?: run { logOnce("GSYNC ctx not set"); return }
        val i = discover() ?: return
        if (!running.compareAndSet(false, true)) {
            FileLog.d("GSYNC sync already running, ignoring request")
            return
        }
        exec.execute {
            var client: android.content.ContentProviderClient? = null
            try {
                val a = getAdapter(c, i) ?: return@execute
                client = c.contentResolver.acquireContentProviderClient(authority)
                if (client == null) {
                    FileLog.d("GSYNC no provider for authority=$authority")
                    return@execute
                }
                val result = SyncResult()
                FileLog.d("GSYNC adapter=" + a.javaClass.name + " threadsAtStart=" + Thread.getAllStackTraces().keys.count { !it.name.startsWith("binder") })
                FileLog.d("GSYNC onPerformSync START account=" + account.name + "/" + account.type + " authority=" + authority)
                a.onPerformSync(account, extras ?: Bundle(), authority, client, result)
                FileLog.d("GSYNC threadsAfter=" + Thread.getAllStackTraces().keys.count { !it.name.startsWith("binder") } +
                        " newThreads=" + Thread.getAllStackTraces().keys.map { it.name }.filter { n -> n.contains("sync", true) || n.contains("contact", true) || n.contains("phonebook", true) }.joinToString())
                FileLog.d("GSYNC onPerformSync DONE hasError=" + result.hasError() + " stats=" + result.stats)
            } catch (e: Throwable) {
                FileLog.d("GSYNC onPerformSync failed: " + (e.cause ?: e))
            } finally {
                try { client?.close() } catch (e: Throwable) { }
                lastDone = System.currentTimeMillis()
                running.set(false)
            }
        }
    }

    // ---- Automatic triggering (2026-11-04) ----
    // Nothing in the real system will ever run the guest's sync adapter (the clone is not an
    // installed package and its account is virtual), and Viber itself never calls requestSync
    // (the log shows only getIsSyncable/isSyncPending). So DualHost triggers the guest's OWN
    // adapter: once shortly after the engine is up, and whenever the device contacts change.
    // Viber's adapter reads the real contacts and builds Viber's own contact list; no data is
    // generated or injected here.
    @Volatile private var started = false
    @Volatile private var lastDone = 0L
    private val main = Handler(Looper.getMainLooper())

    private fun trigger(reason: String) {
        val i = discover() ?: return
        val type = i.accountType ?: return
        val authority = i.authority ?: return
        if (System.currentTimeMillis() - lastDone < 20_000L) return   // ignore echoes of our own sync
        FileLog.d("GSYNC trigger: " + reason)
        run(Account("Me", type), authority, Bundle().apply {
            putBoolean("force", true)
            putBoolean("expedited", true)
            putBoolean("ignore_settings", true)
        })
    }

    fun start(c: Context) {
        if (started) return
        if (ProcessClone.realName != "com.viber.voip") return   // main Viber process only
        started = true
        if (ctx == null) ctx = c
        try {
            val obs = object : ContentObserver(main) {
                private val pending = Runnable { trigger("contacts changed") }
                override fun onChange(selfChange: Boolean) {
                    main.removeCallbacks(pending)
                    main.postDelayed(pending, 3_000L)
                }
            }
            c.contentResolver.registerContentObserver(
                android.provider.ContactsContract.Contacts.CONTENT_URI, true, obs)
            FileLog.d("GSYNC contacts observer registered")
        } catch (e: Throwable) { FileLog.d("GSYNC observer failed: $e") }
        // First run once the engine has had time to start, with one retry.
        main.postDelayed({ trigger("startup +20s") }, 20_000L)
        main.postDelayed({ trigger("startup +60s") }, 60_000L)
    }
}
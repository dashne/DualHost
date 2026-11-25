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
import android.os.HandlerThread
import android.os.Looper
import org.xmlpull.v1.XmlPullParser
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

// Runs the guest's own SyncAdapter in-process. The real SyncManager can never do it: the clone
// is not an installed package and its fake account does not exist in the real AccountManager,
// so requestSync() reaches nothing and Viber reports "NO Service".
//
// Adapter work runs on a dedicated Looper-backed HandlerThread. A request arriving while a sync
// is still running is retried once after 15 s, and a MAIN-looper watchdog unblocks the run gate
// if onPerformSync never returns (thread dump + interrupt).
// The account handed to onPerformSync prefers AccProxy.fakeAccount, falling back to the caller's
// account and only then to the synthetic Account("Me", type).
object GuestSync {
    private const val NS = "http://schemas.android.com/apk/res/android"
    private const val WATCHDOG_MS = 180_000L
    private const val RETRY_DELAY_MS = 15_000L

    class Info(
        val serviceClass: String,
        val authority: String?,
        val accountType: String?,
        val userVisible: Boolean,
        val uploading: Boolean
    )

    // Set from HostInstrumentation via start(ctx), and by start() itself.
    @Volatile var ctx: Context? = null

    @Volatile private var info: Info? = null
    @Volatile private var triedDiscover = false
    @Volatile private var adapter: AbstractThreadedSyncAdapter? = null
    private val running = AtomicBoolean(false)

    private val syncThread = HandlerThread("DualHost-GuestSync").apply { start() }
    private val syncHandler = Handler(syncThread.looper)

    // watchdog bookkeeping: which run is active, and how to describe it in the dump
    private val runSeq = AtomicLong(0)
    @Volatile private var runAccountDesc = ""

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
    // onBind() returned. Checks instance AND static fields, and goes one level into non-framework objects.
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

    // Prefer the fake account AccProxy registered for the guest; fall back to the caller's
    // account, then to "Me" with the adapter's own type.
    private fun accountFor(i: Info, caller: Account?): Account {
        AccProxy.fakeAccount?.let { f ->
            if (i.accountType == null || f.type == i.accountType) return f
        }
        caller?.let { return it }
        return Account("Me", i.accountType ?: "")
    }

    fun run(account: Account, authority: String, extras: Bundle?) {
        val c = ctx ?: run { logOnce("GSYNC ctx not set"); return }
        probeContacts(c)
        val i = discover() ?: return
        val acc = accountFor(i, account)
        if (!running.compareAndSet(false, true)) {
            FileLog.d("GSYNC sync already running, retry scheduled (+" + RETRY_DELAY_MS / 1000 + "s)")
            syncHandler.postDelayed({
                if (running.compareAndSet(false, true)) {
                    doSync(c, i, acc, authority, extras)
                } else {
                    logOnce("GSYNC retry skipped, previous run still busy")
                }
            }, RETRY_DELAY_MS)
            return
        }
        doSync(c, i, acc, authority, extras)
    }

    private fun doSync(c: Context, i: Info, account: Account, authority: String, extras: Bundle?) {
        syncHandler.post {
            val seq = runSeq.incrementAndGet()
            val started = System.currentTimeMillis()
            runAccountDesc = account.name + "/" + account.type
            // Watchdog on the MAIN looper: the sync looper is the thread that may be stuck.
            main.postDelayed({
                if (running.get() && runSeq.get() == seq && System.currentTimeMillis() - started > WATCHDOG_MS) {
                    FileLog.d("GSYNC WATCHDOG run#$seq ($runAccountDesc) exceeded " + WATCHDOG_MS / 1000 +
                            "s, dumping threads and unblocking the sync gate")
                    dumpThreads("GSYNC WATCHDOG")
                    try { syncThread.interrupt() } catch (e: Throwable) { }
                    running.set(false)
                }
            }, WATCHDOG_MS + 5_000L)
            var client: android.content.ContentProviderClient? = null
            try {
                val a = getAdapter(c, i) ?: return@post
                client = c.contentResolver.acquireContentProviderClient(authority)
                if (client == null) {
                    FileLog.d("GSYNC no provider for authority=$authority")
                    return@post
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

    // Compact thread dump: sync/contacts/viber-related threads get up to 12 frames, everything
    // else one frame, hard-capped so the log cannot flood.
    private fun dumpThreads(tag: String) {
        try {
            var lines = 0
            for ((t, st) in Thread.getAllStackTraces()) {
                if (t.name.startsWith("binder")) continue
                val key = t.name.contains("GuestSync") || t.name.contains("sync", true) ||
                        t.name.contains("contact", true) || t.name.contains("viber", true)
                if (!key && lines > 20) continue
                if (lines > 45) { FileLog.d("$tag thread dump truncated"); return }
                val n = if (key) 12 else 1
                val frames = st.take(n).joinToString(" | ") {
                    it.className.substringAfterLast('.') + "." + it.methodName + ":" + it.lineNumber
                }
                FileLog.d("$tag t=" + t.name + " state=" + t.state + " [" + frames + "]")
                lines++
            }
        } catch (e: Throwable) { FileLog.d("$tag dump failed: $e") }
    }

    // Diagnostic: thread stacks at fixed delays after start, tagged GSYNC STACK, so a capture
    // shows what Viber is doing once the adapter has returned.
    private fun dumpGuestThreads(delayMs: Long) {
        main.postDelayed({ dumpThreads("GSYNC STACK") }, delayMs)
    }

    // ---- Automatic triggering ----
    // Nothing in the real system runs the guest's sync adapter (the clone is not an installed
    // package and its account is virtual), and Viber itself never calls requestSync. So DualHost
    // triggers the guest's OWN adapter: once shortly after the engine is up, and whenever the
    // device contacts change. No data is generated or injected here.
    @Volatile private var started = false
    @Volatile private var lastDone = 0L
    private val main = Handler(Looper.getMainLooper())

    private val probed = AtomicBoolean(false)

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
        FileLog.d("GSYNC start called realName=${ProcessClone.realName} started=$started")
        if (started) return
        // ProcessClone.realName is the PROCESS name ("com.ims134.dualhost:p0"), never the package
        // name, so the old check against "com.viber.voip" returned every time and nothing ran.
        if (PmProxy.guestPkg() != "com.viber.voip") return
        if (!ProcessClone.realName.endsWith(":p0")) return   // Viber's main process only
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
        main.postDelayed({ probeContacts(c) }, 25_000L)
        dumpGuestThreads(1_000L)
        dumpGuestThreads(4_000L)
        dumpGuestThreads(12_000L)
    }
    private fun probeContacts(c: Context) {
        FileLog.d("PROBE where pid=" + android.os.Process.myPid() + " proc=" + ProcessClone.realName)
        if (ProcessClone.realName != "com.viber.voip" && ProcessClone.slot == null) return
        if (!probed.compareAndSet(false, true)) return
        Thread {
            try {
                val r = c.contentResolver
                val p = r.query(android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI, arrayOf("_id"), null, null, null)
                val n = p?.count ?: -1
                p?.close()
                val q = r.query(android.provider.ContactsContract.RawContacts.CONTENT_URI, arrayOf("_id"), null, null, null)
                val m = q?.count ?: -1
                q?.close()
                try {
                    val va = c.classLoader.loadClass("com.viber.voip.ViberApplication")
                    val m = va.getDeclaredMethod("isActivated"); m.isAccessible = true
                    FileLog.d("PROBE isActivated=" + m.invoke(null))
                } catch (e: Throwable) { FileLog.d("PROBE isActivated failed: " + (e.cause ?: e)) }
                for (t in listOf("phonebookcontact", "phonebookrawcontact", "phonebookdata")) {
                    try {
                        val cur = c.contentResolver.query(android.net.Uri.parse("content://com.viber.voip.provider.vibercontacts/" + t), arrayOf("_id"), null, null, null)
                        FileLog.d("PROBE viberProvider " + t + " rows=" + (cur?.count ?: -1))
                        cur?.close()
                    } catch (e: Throwable) { FileLog.d("PROBE viberProvider " + t + " failed: " + (e.cause ?: e)) }
                }
                try {
                    FileLog.d("PROBE readContactsPerm=" + c.checkSelfPermission("android.permission.READ_CONTACTS"))
                } catch (e: Throwable) { FileLog.d("PROBE perm failed: $e") }
                FileLog.d("PROBE contacts phoneRows=$n rawContacts=$m pkg=" + c.packageName)
            } catch (e: Throwable) { FileLog.d("PROBE contacts failed: $e") }
        }.start()
    }
}
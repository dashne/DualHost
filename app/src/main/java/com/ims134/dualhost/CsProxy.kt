package com.ims134.dualhost

import android.accounts.Account
import android.os.Bundle
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.Properties

// Proxies ContentResolver's cached IContentService (sContentService).
//  - isSyncActive/isSyncPending: answered locally as false (READ_SYNC_STATS-safe).
//  - For Viber's FAKE account (AccProxy.fakeAccount) on the authorities we serve:
//      setIsSyncable / setSyncAutomatically: remembered per authority, ONLY the values the guest
//        itself sets (positive syncable values; any boolean for auto-sync). Non-positive
//        syncable values go to the real service, as they would without DualHost.
//      getIsSyncable / getSyncAutomatically: replay a remembered value. If the guest never set
//        one, the real service answers, exactly as it would without DualHost. Nothing is invented.
//      requestSync -> GuestSync.run(...).
//  - Remembered values are persisted to virtual_sync_state.properties under the guest's filesDir,
//    so they survive a restart of the clone.
//  - Everything else goes to the real service untouched, logged once per distinct call.
object CsProxy {
    private val seen = HashSet<String>()
    private fun logOnce(s: String) { if (synchronized(seen) { seen.add(s) }) FileLog.d(s) }

    private val interesting = Regex("(?i)sync|periodic|account|syncable")
    private const val CONTACTS = "com.android.contacts"

    private const val STATE_FILE = "virtual_sync_state.properties"
    private const val KEY_SYNCABLE = "syncable."
    private const val KEY_AUTO = "auto."

    // values the guest itself set, per authority
    private val setIsSyncable = HashMap<String, Int>()
    private val setSyncAuto = HashMap<String, Boolean>()
    private var stateLoaded = false

    // real sContentService, captured at install time (also used by callReal below)
    @Volatile private var real: Any? = null

    private fun isMine(a: Any?): Boolean {
        val acc = a as? Account ?: return false
        val f = AccProxy.fakeAccount ?: return false
        return acc.name == f.name && acc.type == f.type
    }

    // Authorities we answer for: the contacts authority plus whatever the guest's own
    // sync-adapter manifest declares (normally the same; discover() caches after first try).
    private fun isOurAuthority(auth: String): Boolean {
        if (auth == CONTACTS) return true
        val ga = try { GuestSync.discover()?.authority } catch (e: Throwable) { null }
        return ga != null && ga == auth
    }

    private fun stateFile(): File? = GuestSync.ctx?.filesDir?.let { File(it, STATE_FILE) }

    // Loads the persisted values once the guest context is known.
    @Synchronized
    private fun ensureLoaded() {
        if (stateLoaded) return
        val f = stateFile() ?: return
        stateLoaded = true
        try {
            if (!f.exists()) return
            val p = Properties()
            f.inputStream().use { p.load(it) }
            synchronized(setIsSyncable) {
                for (k in p.stringPropertyNames()) {
                    if (k.startsWith(KEY_SYNCABLE)) {
                        p.getProperty(k)?.toIntOrNull()?.let { setIsSyncable[k.removePrefix(KEY_SYNCABLE)] = it }
                    }
                }
            }
            synchronized(setSyncAuto) {
                for (k in p.stringPropertyNames()) {
                    if (k.startsWith(KEY_AUTO)) {
                        p.getProperty(k)?.toBooleanStrictOrNull()?.let { setSyncAuto[k.removePrefix(KEY_AUTO)] = it }
                    }
                }
            }
        } catch (e: Throwable) {
            FileLog.d("CS state load failed: $e")
        }
    }

    private fun persist() {
        try {
            val f = stateFile() ?: return
            val p = Properties()
            synchronized(setIsSyncable) { for ((a, v) in setIsSyncable) p.setProperty(KEY_SYNCABLE + a, v.toString()) }
            synchronized(setSyncAuto) { for ((a, v) in setSyncAuto) p.setProperty(KEY_AUTO + a, v.toString()) }
            f.outputStream().use { p.store(it, "DualHost guest sync state (values set by the guest only)") }
        } catch (e: Throwable) {
            FileLog.d("CS state save failed: $e")
        }
    }

    // Returns (answered, value). answered=false means: fall through to the real service.
    // Value is already boxed in the type the interface method declares (Int -> Integer,
    // Boolean -> Boolean, null for void), so the Proxy can never trip a ClassCastException
    // or an NPE on a primitive return type.
    private fun answerMine(name: String, args: Array<Any?>): Pair<Boolean, Any?> {
        val auth = args.getOrNull(1) as? String ?: return false to null
        if (!isOurAuthority(auth)) return false to null
        ensureLoaded()
        return when (name) {
            "getIsSyncable", "getIsSyncableAsUser" -> {
                val v = synchronized(setIsSyncable) { setIsSyncable[auth] } ?: return false to null
                logOnce("CS MINE $name($auth) -> $v")
                true to v
            }
            "getSyncAutomatically", "getSyncAutomaticallyAsUser" -> {
                val v = synchronized(setSyncAuto) { setSyncAuto[auth] } ?: return false to null
                logOnce("CS MINE $name($auth) -> $v")
                true to v
            }
            "setIsSyncable", "setIsSyncableAsUser" -> {
                val v = (args.getOrNull(2) as? Number)?.toInt()
                if (v == null || v <= 0) {
                    logOnce("CS MINE $name($auth, $v) not remembered, real service answers")
                    return false to null
                }
                synchronized(setIsSyncable) { setIsSyncable[auth] = v }
                persist()
                logOnce("CS MINE $name($auth, $v) remembered")
                true to null
            }
            "setSyncAutomatically", "setSyncAutomaticallyAsUser" -> {
                val v = args.getOrNull(2) as? Boolean ?: return false to null
                synchronized(setSyncAuto) { setSyncAuto[auth] = v }
                persist()
                logOnce("CS MINE $name($auth, $v) remembered")
                true to null
            }
            "requestSync", "requestSyncAsUser" -> {
                val extras = args.firstOrNull { it is Bundle } as? Bundle
                logOnce("CS MINE $name($auth) -> GuestSync.run")
                try {
                    GuestSync.run(args[0] as Account, auth, extras)
                } catch (e: Throwable) {
                    FileLog.d("CS MINE $name failed: $e")
                }
                true to null
            }
            else -> false to null
        }
    }

    private fun describe(a: Any?): String = try {
        when (a) {
            null -> "null"
            is String, is Number, is Boolean -> a.toString()
            is Account -> "Account(" + a.name + "," + a.type + ")"
            is Bundle -> "Bundle" + a.keySet().toString()
            else -> a.javaClass.simpleName
        }.take(120)
    } catch (e: Throwable) { "?" }

    private fun callReal(m: Method, args: Array<Any?>?): Any? =
        if (args == null) m.invoke(real) else m.invoke(real, *args)

    fun install() {
        try {
            val crClass = Class.forName("android.content.ContentResolver")
            val sf = crClass.getDeclaredField("sContentService")
            sf.isAccessible = true
            var realSvc = sf.get(null)
            if (realSvc == null) {
                try {
                    val m = crClass.getDeclaredMethod("getContentService")
                    m.isAccessible = true
                    m.invoke(null)
                    realSvc = sf.get(null)
                } catch (e: Throwable) {
                    FileLog.d("CsProxy: could not force-resolve sContentService: $e")
                }
            }
            if (realSvc == null) {
                FileLog.d("CsProxy: sContentService still null, skipping install")
                return
            }
            real = realSvc

            val iface = Class.forName("android.content.IContentService")
            val proxy = Proxy.newProxyInstance(iface.classLoader, arrayOf(iface)) { _, m, args ->
                // Our interception layer is isolated from the real-call path: a bug in our own
                // code is logged and falls through to the real service, while the real service's
                // own exceptions propagate to the caller untouched.
                var shortCircuit = false
                var handled: Any? = null
                try {
                    if (m.name == "isSyncActive" || m.name == "isSyncPending") {
                        logOnce("CS " + m.name + " answered false locally (no real call made)")
                        handled = false
                        shortCircuit = true
                    } else if (args != null && args.isNotEmpty() && isMine(args[0])) {
                        val (answered, value) = answerMine(m.name, args)
                        if (answered) {
                            handled = value
                            shortCircuit = true
                        }
                    }
                    if (!shortCircuit && interesting.containsMatchIn(m.name)) {
                        logOnce("CS CALL " + m.name + "(" + (args?.joinToString(", ") { describe(it) } ?: "") + ")")
                    }
                } catch (e: Throwable) {
                    logOnce("CS handler bug in " + m.name + ": " + e)
                }
                if (shortCircuit) return@newProxyInstance handled

                try {
                    val r = callReal(m, args)
                    if (interesting.containsMatchIn(m.name) && r != null && r !is Unit) {
                        logOnce("CS RESULT " + m.name + " -> " + describe(r))
                    }
                    r
                } catch (e: InvocationTargetException) {
                    logOnce("CS THREW " + m.name + ": " + e.targetException)
                    throw e.targetException
                }
            }
            sf.set(null, proxy)
            FileLog.d("CsProxy installed")
        } catch (e: Throwable) {
            FileLog.d("CsProxy failed: $e")
        }
    }
}
package com.ims134.dualhost

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import android.util.Log
import java.io.FileDescriptor
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ACC fix, single file. Replaces the old AccProxy mechanism entirely.
 *
 * - Installs a passthrough binder for the "account" system service (ServiceManager.sCache patch,
 *   same trick as your old AccProxy — confirmed working in the log).
 * - Learns tx-code -> method WITHOUT hidden-API reflection: calls public AccountManager APIs with
 *   a probe type and records which tx code arrives.
 * - Forwards every transaction untouched unless REWRITE is on AND the code was learned
 *   AND the reply is an Account[] — then it inserts the real guest account only if the
 *   framework's visibility filter dropped it (the delivery gap). Any failure falls back
 *   to a verbatim copy of the real reply. No layout guessing anywhere.
 */
object AccFix {
    const val GUEST_TYPE = "com.ims134.dualhost.viber"
    const val REWRITE = true // set to false for pure forwarding / observation only

    private const val PROBE_TYPE = "com.dualhost.probe.acc"
    private const val PROBE_PKG = "com.dualhost.probe"
    private const val TAG = "DualHost"

    fun log(msg: String) { Log.d(TAG, "ACC $msg") }

    private val installed = AtomicBoolean(false)

    /** Call ONCE from your proxy-init spot, before Viber's Application code runs. */
    fun install(ctx: Context) {
        if (!installed.compareAndSet(false, true)) return
        val app = ctx.applicationContext
        try {
            Passthrough.install()
        } catch (t: Throwable) {
            log("install: passthrough failed: $t")
            return
        }
        try {
            Table.learn(app) // must run AFTER the passthrough is in place
        } catch (t: Throwable) {
            log("install: learn failed: $t")
        }


    }

    /** No-context entry point: patches the cache immediately, learns once an app context appears. */
    fun install() {
        if (!installed.compareAndSet(false, true)) return
        try {
            Passthrough.install()
        } catch (t: Throwable) {
            log("install: passthrough failed: $t")
            return
        }
        Thread {
            var app: Context? = null
            var tries = 0
            while (app == null && tries < 100) { // wait up to ~5 s for Application attach
                app = currentAppOrNull()
                if (app == null) { Thread.sleep(50); tries++ }
            }
            val ctx = app
            if (ctx == null) { log("install: learn skipped — no app context after 5 s"); return@Thread }
            try {
                Table.learn(ctx)
            } catch (t: Throwable) {
                log("install: learn failed: $t")
            }
        }.apply { name = "DualHost-AccInstall"; isDaemon = true }.start()
    }

    private fun currentAppOrNull(): Context? {
        val viaThread = runCatching {
            Class.forName("android.app.ActivityThread")
                .getDeclaredMethod("currentApplication").invoke(null) as? Context
        }.getOrNull()
        if (viaThread != null) return viaThread
        return runCatching {
            Class.forName("android.app.AppGlobals")
                .getDeclaredMethod("getInitialApplication").invoke(null) as? Context
        }.getOrNull()
    }

    // ------------------------------------------------------------- passthrough

    private object Passthrough {
        fun install() {
            val sm = Class.forName("android.os.ServiceManager")
            val getService = sm.getDeclaredMethod("getService", String::class.java)
            getService.isAccessible = true
            val real = getService.invoke(null, "account") as? IBinder
                ?: return log("install: no real \"account\" binder")
            val cacheField = sm.getDeclaredField("sCache")
            cacheField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val cache = cacheField.get(null) as? MutableMap<String, IBinder>
                ?: return log("install: ServiceManager.sCache not a MutableMap")
            cache["account"] = AccBinder(real)
            log("installed passthrough for \"account\" (real=${real.javaClass.simpleName})")
        }

    }

    private class AccBinder(val real: IBinder) : IBinder by real {
        // Kotlin delegation generates every other IBinder member (asBinder, queryLocalInterface,
        // dump, dumpAsync, linkToDeath, unlinkToDeath, pingBinder, isBinderAlive,
        // getInterfaceDescriptor) with the exact SDK signatures — forwarding to `real`.
        // Only transact is hand-written, and your compiler already accepted this signature.
        override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (reply == null) return real.transact(code, data, reply, flags)
            if (Table.intercept(code, data, reply, real, flags)) return true
            return real.transact(code, data, reply, flags)
        }
    }

    // ------------------------------------------------------------- code table

    private object Table {
        private val probing = object : ThreadLocal<String?>() { override fun initialValue(): String? = null }
        private val codeToMethod = ConcurrentHashMap<Int, String>()
        private val started = AtomicBoolean(false)

        @Volatile private var guestAccount: Account? = null
        private val inRewrite = ThreadLocal.withInitial { false }

        @Volatile private var appCtx: Context? = null

        fun learn(ctx: Context) 
        {
            if (!started.compareAndSet(false, true)) return
            appCtx = ctx.applicationContext
            Thread {
                val am = try {
                    AccountManager.get(ctx)
                } catch (t: Throwable) {
                    log("learn: AccountManager.get failed: $t"); return@Thread
                }
                probe("getAccountsByType") { am.getAccountsByType(PROBE_TYPE) }
                probe("getAccountsByTypeForPackage") { am.getAccountsByTypeForPackage(PROBE_TYPE, PROBE_PKG) }
                probe("getAccounts") { am.accounts }
                log("LEARN done: " + codeToMethod.entries.joinToString { "${it.value}=tx${it.key}" })
            }.apply { name = "DualHost-AccLearn"; isDaemon = true }.start()

            try {
                val ok = GuestAccounts.ensureRealAccount(ctx)
                log("auto-create account: ok=$ok")
                if (ok) CsSync.armAfterRegistration(ctx)
            } catch (t: Throwable) {
                log("auto-create failed: ${t.javaClass.simpleName}: ${t.message}")
            }
        }


        private inline fun probe(name: String, call: () -> Any?) {
            try {
                probing.set(name)
                call()
            } catch (t: Throwable) {
                log("probe $name: ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                probing.set(null)
            }
        }

        fun intercept(code: Int, data: Parcel, reply: Parcel, real: IBinder, flags: Int): Boolean {
            // learning: record the tx code only for calls we made ourselves with a marker set
            probing.get()?.let { name ->
                if (codeToMethod.putIfAbsent(code, name) == null) log("LEARNED tx=$code -> $name")
            }
            if (inRewrite.get()) return false // nested traffic from our own fetch: forward untouched
            val method = codeToMethod[code] ?: return false // unknown code: untouched forward
            if (!REWRITE) return false
            if (method != "getAccountsByType" &&
                method != "getAccountsByTypeForPackage" &&
                method != "getAccounts"
            ) return false
            return rewriteAccountList(code, method, data, reply, real, flags)
        }

        /**
         * Runs the REAL call into a scratch reply, then inserts the guest account only if the
         * framework filtered it out (visibility for the virtual package). Uses only public
         * Parcel/Account marshalling — the layout is API-stable, not guessed.
         */
        private fun rewriteAccountList(
            code: Int, method: String, data: Parcel, reply: Parcel, real: IBinder, flags: Int
        ): Boolean
        {
            val scratch = Parcel.obtain()
            inRewrite.set(true)
            return try
            {
                data.setDataPosition(0)
                val ok = real.transact(code, data, scratch, flags)
                scratch.setDataPosition(0)
                val exc = scratch.readInt()
                if (!ok || exc != 0) { // error reply: copy verbatim, do not touch content
                    reply.appendFrom(scratch, 0, scratch.dataSize())
                    reply.setDataPosition(0)
                    return ok
                }
                val got: Array<Account?>? = scratch.createTypedArray(Account.CREATOR)
                val guest = guestAccount
                val list = ArrayList<Account?>()
                var had = false
                got?.forEach { a ->
                    if (a != null && guest != null && a.type == guest.type && a.name == guest.name) had = true
                    list.add(a)
                }
                if (guest != null && !had) list.add(guest)
                if (guest != null && list.size != (got?.size ?: 0)) {
                    log("tx=$code $method: guest account added to reply (${got?.size ?: 0} -> ${list.size})")
                }
                reply.writeNoException()
                reply.writeTypedArray(list.toTypedArray(), 0)
                reply.setDataPosition(0)
                true
            } catch (t: Throwable)
            {
                log("tx=$code $method: rewrite failed, copying raw reply: $t")
                try {
                    scratch.setDataPosition(0)
                    reply.appendFrom(scratch, 0, scratch.dataSize())
                    reply.setDataPosition(0)
                    true
                } catch (t2: Throwable)
                {
                    log("tx=$code copy-back failed: $t2")
                    false // caller falls back to real.transact with reply untouched
                }
            } finally
            {
                inRewrite.set(false)
                scratch.recycle()
            }
        }
    }
}
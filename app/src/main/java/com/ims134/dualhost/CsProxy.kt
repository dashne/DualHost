package com.ims134.dualhost

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

// Added 2026-10-31: durable fix for the READ_SYNC_STATS crash (ContentResolver.isSyncActive()
// inside Viber's contacts-sync poller). The manifest entry only REQUESTS the permission — on
// MIUI/HyperOS and other vendor ROMs, a "normal" AOSP permission isn't guaranteed to be
// install-granted the way a plain -r update implies, so relying on the manifest alone is not
// durable across devices. This proxies android.content.ContentResolver's cached IContentService
// singleton (the private static ContentResolver.sContentService field) the exact same way
// AmProxy/PmProxy/TmProxy already proxy their own hidden AIDL singletons in this project, and
// answers isSyncActive()/isSyncPending() locally with a safe "nothing is syncing" default instead
// of ever making the real binder call — so neither this device's grant state nor whatever uid
// system_server sees for the call can ever matter again. Every other IContentService method
// (getIsSyncable/setIsSyncable, requestSync, etc. — already working via READ_SYNC_SETTINGS) still
// goes to the real service untouched. Matched by method name only (not exact overload signature),
// same robustness choice AmProxy already makes, since isSyncActive's parameter list has changed
// across Android versions.
//
// 2026-11-03: with AccProxy now faking Viber's account, the Contacts tab moved from "Allow
// access" to "Contacts syncing incomplete / Retry". This proxy now also LOGS (once per distinct
// call, never changing behavior) every other sync/account-related IContentService call and its
// arguments, so the next capture shows exactly which sync calls Viber makes (requestSync,
// setSyncAutomatically, getIsSyncable, addPeriodicSync, ...) and with which account/authority.
object CsProxy {
    private val seen = HashSet<String>()
    private fun logOnce(s: String) { if (synchronized(seen) { seen.add(s) }) FileLog.d(s) }

    private val interesting = Regex("(?i)sync|periodic|account|syncable")

    private fun describe(a: Any?): String = try {
        when (a) {
            null -> "null"
            is String, is Number, is Boolean -> a.toString()
            is android.accounts.Account -> "Account(" + a.name + "," + a.type + ")"
            is android.os.Bundle -> "Bundle" + a.keySet().toString()
            else -> a.javaClass.simpleName
        }.take(120)
    } catch (e: Throwable) { "?" }

    fun install() {
        try {
            val crClass = Class.forName("android.content.ContentResolver")
            val sf = crClass.getDeclaredField("sContentService")
            sf.isAccessible = true
            var real = sf.get(null)
            if (real == null) {
                // Not resolved yet in this process: force resolution via the real
                // getContentService(), then read the now-populated field.
                try {
                    val m = crClass.getDeclaredMethod("getContentService")
                    m.isAccessible = true
                    m.invoke(null)
                    real = sf.get(null)
                } catch (e: Throwable) {
                    FileLog.d("CsProxy: could not force-resolve sContentService: $e")
                }
            }
            if (real == null) {
                FileLog.d("CsProxy: sContentService still null, skipping install")
                return
            }

            val iface = Class.forName("android.content.IContentService")
            val proxy = Proxy.newProxyInstance(iface.classLoader, arrayOf(iface)) { _, m, args ->
                when (m.name) {
                    "isSyncActive", "isSyncPending" -> {
                        logOnce("CS " + m.name + " answered false locally (no real call made)")
                        false
                    }
                    else -> {
                        if (interesting.containsMatchIn(m.name)) {
                            logOnce("CS CALL " + m.name + "(" + (args?.joinToString(", ") { describe(it) } ?: "") + ")")
                        }
                        try {
                            val r = if (args == null) m.invoke(real) else m.invoke(real, *args)
                            if (interesting.containsMatchIn(m.name) && r != null && r !is Unit) {
                                logOnce("CS RESULT " + m.name + " -> " + describe(r))
                            }
                            r
                        } catch (e: InvocationTargetException) {
                            logOnce("CS THREW " + m.name + ": " + e.targetException)
                            throw e.targetException
                        }
                    }
                }
            }
            sf.set(null, proxy)
            FileLog.d("CsProxy installed")
        } catch (e: Throwable) {
            FileLog.d("CsProxy failed: $e")
        }
    }
}
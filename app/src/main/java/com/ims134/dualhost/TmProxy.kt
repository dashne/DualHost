package com.ims134.dualhost

import android.content.Intent
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

// Added 2026-10-31: answers the handoff's open "does every kind of launch get redirected"
// question. HostInstrumentation.execStartActivity/execStartActivities only fire when the
// CALLER is an Activity (Activity.startActivity -> Instrumentation.execStartActivity...).
// A guest Service, BroadcastReceiver, or Application context calling Context.startActivity()
// never goes through Instrumentation at all: ContextImpl routes it straight to
// ActivityTaskManager.getService() (IActivityTaskManager), a completely separate system-service
// singleton from the IActivityManager one AmProxy patches. That path was unguarded: an
// explicit launch of the guest's own component (e.g. a notification tap or an incoming-call
// screen raised from a background service) would reach the real system unredirected and crash
// the same cross-UID way the ContentProvider/execStartActivities bugs did, just one call site
// earlier. Not yet exercised against a real launch-from-service case — verify with a log line
// (TM redirected ...) before relying on it; the fallback if reflection fails here is the same
// degraded (but non-crashing for DualHost itself) behavior as before this file existed, since
// install() no-ops on any failure.
object TmProxy {
    private val seen = HashSet<String>()
    private fun logOnce(s: String) {
        if (synchronized(seen) { seen.add(s) }) FileLog.d(s)
    }

    fun install() {
        try {
            val atmClass = Class.forName("android.app.ActivityTaskManager")
            val sf = atmClass.getDeclaredField("IActivityTaskManagerSingleton")
            sf.isAccessible = true
            val singleton = sf.get(null)!!
            val sc = Class.forName("android.util.Singleton")
            val real = sc.getMethod("get").invoke(singleton)
            val iface = Class.forName("android.app.IActivityTaskManager")

            val proxy = Proxy.newProxyInstance(iface.classLoader, arrayOf(iface)) { _, m, args ->
                var a = args
                try {
                    if (a != null && (m.name == "startActivity" || m.name == "startActivityAsUser" ||
                                m.name == "startActivityWithFeature" || m.name == "startActivityAsCaller")) {
                        val idx = a.indexOfFirst { it is Intent }
                        if (idx >= 0) {
                            val orig = a[idx] as Intent
                            val fixed = AmProxy.fix(orig)
                            if (fixed !== orig) {
                                a = a.copyOf()
                                a[idx] = fixed
                                logOnce("TM redirected " + m.name + " " + orig.component)
                            }
                        }
                    } else if (a != null && m.name == "startActivities") {
                        val idx = a.indexOfFirst { it is Array<*> && it.isArrayOf<Intent>() }
                        if (idx >= 0) {
                            @Suppress("UNCHECKED_CAST")
                            val intents = a[idx] as Array<Intent>
                            val fixed = Array(intents.size) { AmProxy.fix(intents[it]) }
                            a = a.copyOf()
                            a[idx] = fixed
                            logOnce("TM redirected startActivities (" + intents.size + " intents)")
                        }
                    }
                } catch (e: Throwable) {
                    logOnce("TM fix failed: $e")
                }

                try {
                    if (a == null) m.invoke(real) else m.invoke(real, *a)
                } catch (e: InvocationTargetException) {
                    throw e.targetException
                }
            }

            val mf = sc.getDeclaredField("mInstance")
            mf.isAccessible = true
            mf.set(singleton, proxy)
            FileLog.d("TmProxy installed")
        } catch (e: Throwable) {
            FileLog.d("TmProxy failed: $e")
        }
    }
}
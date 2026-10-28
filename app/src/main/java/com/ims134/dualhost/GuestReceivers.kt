package com.ims134.dualhost

import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.util.Log
import org.xmlpull.v1.XmlPullParser

// Manifest receivers of the guest can't be in DualHost's manifest, so we register them at runtime
object GuestReceivers {
    private const val NS = "http://schemas.android.com/apk/res/android"

    private class Entry(val cls: String, val exported: Boolean) {
        val filters = ArrayList<IntentFilter>()
    }

    private fun parse(host: Context, apkPath: String): List<Entry> {
        val pm = host.packageManager
        val ai = pm.getPackageArchiveInfo(apkPath, 0)!!.applicationInfo!!
        ai.sourceDir = apkPath
        ai.publicSourceDir = apkPath
        val p = pm.getResourcesForApplication(ai).assets.openXmlResourceParser("AndroidManifest.xml")
        val out = ArrayList<Entry>()
        try {
            var pkg = ""
            var cur: Entry? = null
            var filter: IntentFilter? = null

            fun full(n: String?): String {
                if (n == null) return "?"
                if (n.startsWith(".")) return pkg + n
                if (!n.contains(".")) return pkg + "." + n
                return n
            }

            var event = p.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    when (p.name) {
                        "manifest" -> pkg = p.getAttributeValue(null, "package") ?: ""
                        "receiver" -> {
                            cur = if (p.getAttributeBooleanValue(NS, "enabled", true))
                                Entry(full(p.getAttributeValue(NS, "name")),
                                    p.getAttributeBooleanValue(NS, "exported", true)) else null
                            cur?.let { out.add(it) }
                        }
                        "activity", "service", "provider", "activity-alias" -> cur = null
                        "intent-filter" -> filter = if (cur != null) IntentFilter() else null
                        "action" -> p.getAttributeValue(NS, "name")?.let { filter?.addAction(it) }
                        "category" -> p.getAttributeValue(NS, "name")?.let { filter?.addCategory(it) }
                        "data" -> {
                            p.getAttributeValue(NS, "scheme")?.let { filter?.addDataScheme(it) }
                            p.getAttributeValue(NS, "mimeType")?.let {
                                try { filter?.addDataType(it) } catch (e: Throwable) { }
                            }
                        }
                    }
                } else if (event == XmlPullParser.END_TAG) {
                    when (p.name) {
                        "intent-filter" -> {
                            val f = filter
                            if (f != null && f.countActions() > 0) cur?.filters?.add(f)
                            filter = null
                        }
                        "receiver" -> cur = null
                    }
                }
                event = p.next()
            }
        } finally {
            p.close()
        }
        return out
    }

    fun register(host: Context, apkPath: String, loader: ClassLoader) {
        try {
            var count = 0
            for (e in parse(host, apkPath)) {
                if (e.filters.isEmpty()) continue
                try {
                    val r = loader.loadClass(e.cls).getDeclaredConstructor().newInstance() as BroadcastReceiver
                    val flag = if (e.exported) Context.RECEIVER_EXPORTED else Context.RECEIVER_NOT_EXPORTED
                    for (f in e.filters) host.registerReceiver(r, f, flag)
                    count++
                    FileLog.d( "GUESTRECV registered " + e.cls + " filters=" + e.filters.size + " exported=" + e.exported)
                } catch (t: Throwable) {
                    FileLog.d( "GUESTRECV failed " + e.cls + ": " + t)
                }
            }
            FileLog.d( "GUESTRECV done, " + count + " receiver(s)")
        } catch (t: Throwable) {
            FileLog.d( "GUESTRECV parse failed: " + t)
        }
    }
}
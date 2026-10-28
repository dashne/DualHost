package com.ims134.dualhost

import android.content.Context
import android.net.Uri
import org.xmlpull.v1.XmlPullParser

// Finds the guest activity whose VIEW + BROWSABLE filter matches a link
object OpenLinkHandlers {
    private const val NS = "http://schemas.android.com/apk/res/android"

    fun find(ctx: Context, apkPath: String, uri: Uri): String? {
        val scheme = uri.scheme ?: return null
        val host = uri.host   // custom schemes often have no host
        val pm = ctx.packageManager
        val ai = pm.getPackageArchiveInfo(apkPath, 0)!!.applicationInfo!!
        ai.sourceDir = apkPath
        ai.publicSourceDir = apkPath
        val res = pm.getResourcesForApplication(ai)
        val p = res.assets.openXmlResourceParser("AndroidManifest.xml")
        try {
            var pkg = ""
            var component: String? = null
            var inFilter = false
            val actions = ArrayList<String>()
            val categories = ArrayList<String>()
            val schemes = ArrayList<String>()
            val hosts = ArrayList<String>()

            fun full(name: String?): String? {
                if (name == null) return null
                if (name.startsWith(".")) return pkg + name
                if (!name.contains(".")) return pkg + "." + name
                return name
            }

            fun hostMatches(h: String): Boolean {
                val x = host ?: return false
                if (h.startsWith("*.")) return x.endsWith(h.substring(1), ignoreCase = true)
                return x.equals(h, ignoreCase = true)
            }

            var event = p.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    when (p.name) {
                        "manifest" -> pkg = p.getAttributeValue(null, "package") ?: ""
                        "activity" -> component = full(p.getAttributeValue(NS, "name"))
                        "activity-alias" ->
                            component = full(p.getAttributeValue(NS, "targetActivity")
                                ?: p.getAttributeValue(NS, "name"))
                        "service", "receiver", "provider" -> component = null
                        "intent-filter" -> {
                            inFilter = true
                            actions.clear(); categories.clear(); schemes.clear(); hosts.clear()
                        }
                        "action" -> if (inFilter) p.getAttributeValue(NS, "name")?.let { actions.add(it) }
                        "category" -> if (inFilter) p.getAttributeValue(NS, "name")?.let { categories.add(it) }
                        "data" -> if (inFilter) {
                            val sid = p.getAttributeResourceValue(NS, "scheme", 0)
                            val sv = if (sid != 0) {
                                try { res.getString(sid) } catch (e: Throwable) { null }
                            } else p.getAttributeValue(NS, "scheme")
                            sv?.let { schemes.add(it) }
                            p.getAttributeValue(NS, "host")?.let { hosts.add(it) }
                        }
                    }
                } else if (event == XmlPullParser.END_TAG && p.name == "intent-filter") {
                    inFilter = false
                    if (component != null &&
                        actions.contains("android.intent.action.VIEW") &&
                        categories.contains("android.intent.category.BROWSABLE") &&
                        schemes.any { it.equals(scheme, ignoreCase = true) } &&
                        (if (hosts.isEmpty()) !scheme.startsWith("http", ignoreCase = true)
                        else hosts.any { hostMatches(it) })) {
                        return component
                    }
                }
                event = p.next()
            }
            return null
        } finally {
            p.close()
        }
    }
}
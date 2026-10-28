package com.ims134.dualhost

import android.content.Context
import org.xmlpull.v1.XmlPullParser

object ManifestReader
{
    private const val NS = "http://schemas.android.com/apk/res/android"

    fun read(context: Context, apkPath: String): String {
        val pm = context.packageManager
        val ai = pm.getPackageArchiveInfo(apkPath, 0)!!.applicationInfo!!
        ai.sourceDir = apkPath
        ai.publicSourceDir = apkPath
        val res = pm.getResourcesForApplication(ai)
        val p = res.assets.openXmlResourceParser("AndroidManifest.xml")

        var pkg = ""
        var appClass = "(default)"
        var launcher = "(none)"
        val counts = HashMap<String, Int>()
        var component: String? = null
        var inFilter = false
        val actions = ArrayList<String>()
        val categories = ArrayList<String>()
        val schemes = ArrayList<String>()
        val hosts = ArrayList<String>()
        val links = ArrayList<String>()

        fun full(name: String?): String {
            if (name == null) return "?"
            if (name.startsWith(".")) return pkg + name
            if (!name.contains(".")) return pkg + "." + name
            return name
        }

        var event = p.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (p.name) {
                    "manifest" -> pkg = p.getAttributeValue(null, "package") ?: ""
                    "application" -> {
                        val n = p.getAttributeValue(NS, "name")
                        if (n != null) appClass = full(n)
                    }
                    "activity", "activity-alias", "service", "receiver", "provider" -> {
                        counts[p.name] = (counts[p.name] ?: 0) + 1
                        component = full(p.getAttributeValue(NS, "name"))
                    }
                    "intent-filter" -> {
                        inFilter = true
                        actions.clear(); categories.clear(); schemes.clear(); hosts.clear()
                    }
                    "action" -> if (inFilter) p.getAttributeValue(NS, "name")?.let { actions.add(it) }
                    "category" -> if (inFilter) p.getAttributeValue(NS, "name")?.let { categories.add(it) }
                    "data" -> if (inFilter) {
                        val sid = p.getAttributeResourceValue(NS, "scheme", 0)
                        val s = if (sid != 0) {
                            try { res.getString(sid) } catch (e: Throwable) { "@" + sid }
                        } else p.getAttributeValue(NS, "scheme")
                        s?.let { schemes.add(it) }
                        p.getAttributeValue(NS, "host")?.let { hosts.add(it) }
                    }
                }
            } else if (event == XmlPullParser.END_TAG) {
                when (p.name) {
                    "intent-filter" -> {
                        inFilter = false
                        if (actions.contains("android.intent.action.MAIN") &&
                            categories.contains("android.intent.category.LAUNCHER")) {
                            launcher = component ?: "?"
                        }
                        if (actions.contains("android.intent.action.VIEW") &&
                            categories.contains("android.intent.category.BROWSABLE") &&
                            schemes.isNotEmpty()) {
                            links.add((component ?: "?") + " -> " + schemes.distinct().joinToString("/") +
                                    "://" + hosts.distinct().joinToString(", "))
                        }
                    }
                    "activity", "activity-alias", "service", "receiver", "provider" -> component = null
                }
            }
            event = p.next()
        }

        var dexCount = 0
        val abis = java.util.TreeSet<String>()
        java.util.zip.ZipFile(apkPath).use { zip ->
            for (entry in zip.entries()) {
                val n = entry.name
                if (n.startsWith("classes") && n.endsWith(".dex")) dexCount++
                if (n.startsWith("lib/")) abis.add(n.split("/").getOrElse(1) { "" })
            }
        }

        return "Package: " + pkg +
                "\nDex files in APK: " + dexCount +
                "\nNative ABIs in APK: " + (if (abis.isEmpty()) "(none)" else abis.joinToString(", ")) +
                "\nApplication class: " + appClass +
                "\nLauncher activity: " + launcher +
                "\nActivities: " + (counts["activity"] ?: 0) +
                ", Services: " + (counts["service"] ?: 0) +
                ", Receivers: " + (counts["receiver"] ?: 0) +
                ", Providers: " + (counts["provider"] ?: 0) +
                "\nWeb link handlers:\n" +
                (if (links.isEmpty()) "  (none)" else links.take(15).joinToString("\n") { "  " + it })
    }
}
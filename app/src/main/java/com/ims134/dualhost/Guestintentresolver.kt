package com.ims134.dualhost

import android.content.Context
import org.xmlpull.v1.XmlPullParser

// Added 2026-11-03: Viber's "Contacts syncing incomplete -> Retry" crashed the clone with
// SecurityException "Permission Denial: starting Intent { act=com.viber.voip.action.SYSTEM_DIALOG
// pkg=com.viber.voip cmp=com.viber.voip/.app.ViberSystemActivity } ... not exported from uid
// 10364". The guest launched one of its OWN screens with an implicit intent (action + package,
// no component). HostInstrumentation.redirect() only acts on intents that already carry a
// component, so the intent reached the real system, which resolved it against the real,
// separately-installed Viber (a different uid) and refused. This reads the GUEST'S OWN manifest
// (same XmlPullParser technique ManifestReader already uses) to map each action declared by an
// activity's <intent-filter> to that activity's class, so redirect() can fill in the component and then route it
// through the normal stub-swap path. Only used for intents explicitly addressed to the guest's
// own package; nothing else is touched.
object GuestIntentResolver {
    private const val NS = "http://schemas.android.com/apk/res/android"
    private val cache = HashMap<String, Map<String, String>>()

    fun resolveActivity(context: Context, apkPath: String, action: String): String? {
        val map = synchronized(cache) {
            cache.getOrPut(apkPath) {
                try { parse(context, apkPath) } catch (e: Throwable) {
                    FileLog.d("GuestIntentResolver: parse failed: " + e)
                    emptyMap()
                }
            }
        }
        return map[action]
    }

    private fun parse(context: Context, apkPath: String): Map<String, String> {
        // IMPORTANT: inside a guest process, PackageManager.getResourcesForApplication() is
        // answered with the HOST's resources (confirmed on-device: parsing that way indexed
        // exactly 2 actions — the host manifest's MAIN and VIEW — and never saw Viber's
        // SYSTEM_DIALOG). So open the guest APK directly with a private AssetManager that points
        // only at that file, independent of anything the host's package-manager hooks answer.
        val am = android.content.res.AssetManager::class.java.getDeclaredConstructor().newInstance()
        val add = android.content.res.AssetManager::class.java.getMethod("addAssetPath", String::class.java)
        val cookie = add.invoke(am, apkPath) as Int
        if (cookie == 0) throw IllegalStateException("addAssetPath failed for " + apkPath)
        @Suppress("DEPRECATION")
        val res = android.content.res.Resources(am, context.resources.displayMetrics, context.resources.configuration)
        val p = am.openXmlResourceParser(cookie, "AndroidManifest.xml")

        var pkg = ""
        var activity: String? = null
        var inFilter = false
        val actions = ArrayList<String>()
        var hasDefault = false
        val out = HashMap<String, String>()

        // Attribute value, resolving @string/... references through the guest's own resources
        // (an action/category name can be declared as a resource reference, not a literal).
        fun attr(name: String): String? {
            val rid = p.getAttributeResourceValue(NS, name, 0)
            if (rid != 0) {
                try { return res.getString(rid) } catch (e: Throwable) { /* fall through */ }
            }
            return p.getAttributeValue(NS, name)
        }

        fun full(name: String?): String? {
            if (name == null) return null
            if (name.startsWith(".")) return pkg + name
            if (!name.contains(".")) return pkg + "." + name
            return name
        }

        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                when (p.name) {
                    "manifest" -> pkg = p.getAttributeValue(null, "package") ?: ""
                    "activity" -> activity = full(p.getAttributeValue(NS, "name"))
                    // An alias carries its own intent-filters but launches its targetActivity.
                    "activity-alias" -> activity = full(p.getAttributeValue(NS, "targetActivity"))
                    "intent-filter" -> { inFilter = true; actions.clear(); hasDefault = false }
                    "action" -> if (inFilter) attr("name")?.let { actions.add(it) }
                    "category" -> if (inFilter && attr("name") == "android.intent.category.DEFAULT") hasDefault = true
                }
            } else if (ev == XmlPullParser.END_TAG) {
                when (p.name) {
                    "intent-filter" -> {
                        inFilter = false
                        val a = activity
                        // DEFAULT is NOT required here: this only runs for intents the guest
                        // addressed to its own package, and the first run showed the real
                        // SYSTEM_DIALOG action missing from a DEFAULT-only index. Filters that do
                        // list DEFAULT win ties.
                        if (a != null) for (act in actions) {
                            if (hasDefault || !out.containsKey(act)) out[act] = a
                        }
                    }
                    "activity", "activity-alias" -> activity = null
                }
            }
            ev = p.next()
        }
        FileLog.d("GuestIntentResolver: indexed " + out.size + " action(s) from guest manifest; SYSTEM_DIALOG=" +
                out["com.viber.voip.action.SYSTEM_DIALOG"] + "; sample=" +
                out.keys.filter { it.contains("viber", true) }.take(12))
        return out
    }
}
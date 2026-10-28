package com.ims134.dualhost

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileNotFoundException
import java.util.concurrent.ConcurrentHashMap

// Guest ContentProviders running in this process, by authority (filled by HostInstrumentation)
object GuestProviders {
    val map = ConcurrentHashMap<String, ContentProvider>()

    fun find(authority: String): ContentProvider? {
        val exact = map[authority]
        if (exact != null) return exact
        val host = "com.ims134.dualhost"
        if (authority.startsWith(host + ".")) {
            // The guest built its authority from DualHost's package name: match by the ending
            val suffix = authority.substring(host.length)
            for ((k, v) in map) if (k.endsWith(suffix)) return v
        }
        return null
    }
}

// Two kinds of addresses reach a proxy:
//  1) content://com.ims134.dualhost.fpN/<guest authority>/<path>  (given to other apps)
//  2) content://<guest authority>/<path>                          (the guest's own lookups)
open class BaseFileProxy : ContentProvider() {

    private fun orig(uri: Uri): Pair<ContentProvider, Uri>? {
        val auth0 = uri.authority ?: return null
        if (!auth0.startsWith("com.ims134.dualhost.fp")) {
            val cp = GuestProviders.find(auth0) ?: return null
            return Pair(cp, uri)
        }
        val segs = uri.pathSegments
        if (segs.size < 2) return null
        val auth = segs[0]
        val cp = GuestProviders.find(auth) ?: return null
        val b = Uri.Builder().scheme("content").authority(auth)
        for (i in 1 until segs.size) b.appendPath(segs[i])
        return Pair(cp, b.build())
    }

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val o = orig(uri) ?: throw FileNotFoundException("No guest provider for " + uri.authority)
        FileLog.d( "FILEPROXY openFile mode=" + mode + " file=" + o.second.lastPathSegment)
        return o.first.openFile(o.second, mode)
    }

    override fun query(
        uri: Uri, projection: Array<String>?, selection: String?,
        selectionArgs: Array<String>?, sortOrder: String?
    ): Cursor? {
        val o = orig(uri) ?: return null
        return o.first.query(o.second, projection, selection, selectionArgs, sortOrder)
    }

    override fun getType(uri: Uri): String? {
        val o = orig(uri) ?: return null
        return o.first.getType(o.second)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        val o = orig(uri) ?: return null
        return o.first.insert(o.second, values)
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int {
        val o = orig(uri) ?: return 0
        return o.first.delete(o.second, selection, selectionArgs)
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int {
        val o = orig(uri) ?: return 0
        return o.first.update(o.second, values, selection, selectionArgs)
    }

    override fun call(authority: String, method: String, arg: String?, extras: Bundle?): Bundle? {
        val cp = GuestProviders.find(authority) ?: return null
        return cp.call(authority, method, arg, extras)
    }
}

class HostFileProxy0 : BaseFileProxy()
class HostFileProxy1 : BaseFileProxy()
class HostFileProxy2 : BaseFileProxy()
class HostFileProxy3 : BaseFileProxy()
class HostFileProxy4 : BaseFileProxy()
class HostFileProxy5 : BaseFileProxy()
class HostFileProxy6 : BaseFileProxy()
class HostFileProxy7 : BaseFileProxy()
class HostFileProxy8 : BaseFileProxy()
class HostFileProxy9 : BaseFileProxy()

// Not in the manifest: a fresh proxy that is created on demand
class DynFileProxy : BaseFileProxy()

// Android only accepts a call if the address authority is one of the provider's own.
// So for each authority the guest asks about, we make a fresh proxy that lists it,
// and AmProxy hands that proxy to the guest instead of the manifest one.
object DynProxies {
    private val map = ConcurrentHashMap<String, Any>()   // authority -> IContentProvider

    fun get(authority: String, slot: Int): Any? {
        val have = map[authority]
        if (have != null) return have
        try {
            val app = Class.forName("android.app.ActivityThread")
                .getDeclaredMethod("currentApplication").invoke(null) as android.app.Application
            val info = android.content.pm.ProviderInfo()
            info.name = DynFileProxy::class.java.name
            info.packageName = app.packageName
            info.applicationInfo = app.applicationInfo
            info.authority = "com.ims134.dualhost.fp" + slot + ";" + authority
            info.exported = false
            info.enabled = true
            val p = DynFileProxy()
            p.attachInfo(app, info)
            val m = ContentProvider::class.java.getDeclaredMethod("getIContentProvider")
            m.isAccessible = true
            val icp = m.invoke(p) ?: return null
            map[authority] = icp
            FileLog.d( "DynProxies: fresh proxy ready for " + authority)
            return icp
        } catch (e: Throwable) {
            FileLog.d( "DynProxies failed: " + e)
            return null
        }
    }
}
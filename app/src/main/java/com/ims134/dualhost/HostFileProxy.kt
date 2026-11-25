package com.ims134.dualhost

import android.content.ContentProvider
import android.content.ContentProviderOperation
import android.content.ContentProviderResult
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileNotFoundException
import java.util.concurrent.ConcurrentHashMap

// Guest ContentProviders running in this process, by authority (filled by HostInstrumentation)
object GuestProviders {
    val map = ConcurrentHashMap<String, ContentProvider>()

    // Every authority the guest's manifest DECLARES, whether or not startGuestProviders()
    // actually managed to construct an in-process instance for it (map only holds the
    // successes). Needed so AmProxy can tell "this belongs to the guest but failed to start"
    // apart from "this is a genuinely third-party authority" — see isDeclared().
    val declared = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

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

    // Same matching as find(), but against every declared authority, not just the ones that
    // actually started. True means "this authority is the guest's own, regardless of whether
    // we can currently serve it" — the signal AmProxy needs to decide whether a getContentProvider
    // call for it is safe to forward to the real system, or must never leave this process.
    fun isDeclared(authority: String): Boolean {
        if (authority in declared) return true
        val host = "com.ims134.dualhost"
        if (authority.startsWith(host + ".")) {
            val suffix = authority.substring(host.length)
            for (k in declared) if (k.endsWith(suffix)) return true
        }
        return false
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

    private fun setCallingPkg(cp: ContentProvider, pkg: String = "com.ims134.dualhost") {
        AmProxy.setCallingPkg(cp, pkg)
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val o = orig(uri) ?: throw FileNotFoundException("No guest provider for " + uri.authority)
        FileLog.d("FILEPROXY openFile mode=" + mode + " file=" + o.second.lastPathSegment)
        setCallingPkg(o.first)
        return try {
            o.first.openFile(o.second, mode)
        } catch (e: Throwable) {
            if (e is IllegalStateException && (e.message?.contains("callingPackage", true) == true || e.message?.contains("ContentProvider", true) == true)) {
                FileLog.d("BaseFileProxy.openFile retrying after setCallingPkg: $e")
                setCallingPkg(o.first)
                try { o.first.openFile(o.second, mode) } catch (_: Throwable) { null }
            } else {
                throw e
            }
        }
    }

    override fun query(
        uri: Uri, projection: Array<String>?, selection: String?,
        selectionArgs: Array<String>?, sortOrder: String?
    ): Cursor? {
        val o = orig(uri) ?: return null
        setCallingPkg(o.first)
        return try {
            o.first.query(o.second, projection, selection, selectionArgs, sortOrder)
        } catch (e: Throwable) {
            if (e is IllegalStateException && (e.message?.contains("callingPackage", true) == true || e.message?.contains("ContentProvider", true) == true)) {
                FileLog.d("BaseFileProxy.query retrying after setCallingPkg: $e")
                setCallingPkg(o.first)
                try { o.first.query(o.second, projection, selection, selectionArgs, sortOrder) } catch (_: Throwable) { android.database.MatrixCursor(projection ?: emptyArray()) }
            } else {
                throw e
            }
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        queryArgs: Bundle?,
        cancellationSignal: CancellationSignal?
    ): Cursor? {
        val o = orig(uri) ?: return null
        setCallingPkg(o.first)
        return try {
            o.first.query(o.second, projection, queryArgs, cancellationSignal)
        } catch (e: Throwable) {
            if (e is IllegalStateException && (e.message?.contains("callingPackage", true) == true || e.message?.contains("ContentProvider", true) == true)) {
                FileLog.d("BaseFileProxy.query(Bundle) retrying after setCallingPkg: $e")
                setCallingPkg(o.first)
                try { o.first.query(o.second, projection, queryArgs, cancellationSignal) } catch (_: Throwable) { android.database.MatrixCursor(projection ?: emptyArray()) }
            } else {
                throw e
            }
        }
    }

    override fun getType(uri: Uri): String? {
        val o = orig(uri) ?: return null
        setCallingPkg(o.first)
        return try {
            o.first.getType(o.second)
        } catch (e: Throwable) {
            if (e is IllegalStateException && (e.message?.contains("callingPackage", true) == true || e.message?.contains("ContentProvider", true) == true)) {
                FileLog.d("BaseFileProxy.getType retrying after setCallingPkg: $e")
                setCallingPkg(o.first)
                try { o.first.getType(o.second) } catch (_: Throwable) { null }
            } else {
                throw e
            }
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        val o = orig(uri) ?: return null
        setCallingPkg(o.first)
        return try {
            o.first.insert(o.second, values)
        } catch (e: Throwable) {
            if (e is IllegalStateException && (e.message?.contains("callingPackage", true) == true || e.message?.contains("ContentProvider", true) == true)) {
                FileLog.d("BaseFileProxy.insert retrying after setCallingPkg: $e")
                setCallingPkg(o.first)
                try { o.first.insert(o.second, values) } catch (_: Throwable) { null }
            } else {
                throw e
            }
        }
    }

    override fun bulkInsert(uri: Uri, values: Array<ContentValues>): Int {
        val o = orig(uri) ?: return 0
        setCallingPkg(o.first)
        return try {
            o.first.bulkInsert(o.second, values)
        } catch (e: Throwable) {
            if (e is IllegalStateException && (e.message?.contains("callingPackage", true) == true || e.message?.contains("ContentProvider", true) == true)) {
                FileLog.d("BaseFileProxy.bulkInsert retrying after setCallingPkg: $e")
                setCallingPkg(o.first)
                try { o.first.bulkInsert(o.second, values) } catch (_: Throwable) { 0 }
            } else {
                throw e
            }
        }
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int {
        val o = orig(uri) ?: return 0
        setCallingPkg(o.first)
        return try {
            o.first.delete(o.second, selection, selectionArgs)
        } catch (e: Throwable) {
            if (e is IllegalStateException && (e.message?.contains("callingPackage", true) == true || e.message?.contains("ContentProvider", true) == true)) {
                FileLog.d("BaseFileProxy.delete retrying after setCallingPkg: $e")
                setCallingPkg(o.first)
                try { o.first.delete(o.second, selection, selectionArgs) } catch (_: Throwable) { 0 }
            } else {
                throw e
            }
        }
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int {
        val o = orig(uri) ?: return 0
        setCallingPkg(o.first)
        return try {
            o.first.update(o.second, values, selection, selectionArgs)
        } catch (e: Throwable) {
            if (e is IllegalStateException && (e.message?.contains("callingPackage", true) == true || e.message?.contains("ContentProvider", true) == true)) {
                FileLog.d("BaseFileProxy.update retrying after setCallingPkg: $e")
                setCallingPkg(o.first)
                try { o.first.update(o.second, values, selection, selectionArgs) } catch (_: Throwable) { 0 }
            } else {
                throw e
            }
        }
    }

    override fun call(authority: String, method: String, arg: String?, extras: Bundle?): Bundle? {
        val cp = GuestProviders.find(authority) ?: return null
        setCallingPkg(cp)
        return try {
            cp.call(authority, method, arg, extras)
        } catch (e: Throwable) {
            if (e is IllegalStateException && (e.message?.contains("callingPackage", true) == true || e.message?.contains("ContentProvider", true) == true)) {
                FileLog.d("BaseFileProxy.call retrying after setCallingPkg: $e")
                setCallingPkg(cp)
                try { cp.call(authority, method, arg, extras) } catch (_: Throwable) { null }
            } else {
                throw e
            }
        }
    }

    override fun applyBatch(
        authority: String,
        operations: ArrayList<ContentProviderOperation>
    ): Array<ContentProviderResult> {
        val cp = GuestProviders.find(authority) ?: return super.applyBatch(authority, operations)
        setCallingPkg(cp)
        return try {
            cp.applyBatch(authority, operations)
        } catch (e: Throwable) {
            if (e is IllegalStateException && (e.message?.contains("callingPackage", true) == true || e.message?.contains("ContentProvider", true) == true)) {
                FileLog.d("BaseFileProxy.applyBatch(auth) retrying after setCallingPkg: $e")
                setCallingPkg(cp)
                try { cp.applyBatch(authority, operations) } catch (_: Throwable) { super.applyBatch(authority, operations) }
            } else {
                throw e
            }
        }
    }

    override fun applyBatch(operations: ArrayList<ContentProviderOperation>): Array<ContentProviderResult> {
        val auth = operations.firstOrNull()?.uri?.authority
        val cp = auth?.let { GuestProviders.find(it) } ?: return super.applyBatch(operations)
        setCallingPkg(cp)
        return try {
            cp.applyBatch(operations)
        } catch (e: Throwable) {
            if (e is IllegalStateException && (e.message?.contains("callingPackage", true) == true || e.message?.contains("ContentProvider", true) == true)) {
                FileLog.d("BaseFileProxy.applyBatch retrying after setCallingPkg: $e")
                setCallingPkg(cp)
                try { cp.applyBatch(operations) } catch (_: Throwable) { super.applyBatch(operations) }
            } else {
                throw e
            }
        }
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
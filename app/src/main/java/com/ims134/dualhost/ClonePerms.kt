package com.ims134.dualhost

import android.content.Context
import android.content.SharedPreferences

// Per-clone permission toggles. Backed by real SharedPreferences (not virtualized —
// this is DualHost's own data, keyed by guest package + clone id), independent of the
// real OS-level grant for com.ims134.dualhost. Default is "allowed" (true), matching
// whatever the real OS permission actually is, until the user turns a clone's toggle
// off in the (future) per-clone permissions UI.
object ClonePerms {
    private const val PREFS = "dh_clone_perms"
    private var prefs: SharedPreferences? = null

    private fun p(ctx: Context): SharedPreferences {
        var pr = prefs
        if (pr == null) {
            pr = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs = pr
        }
        return pr
    }

    private fun key(pkg: String, cloneId: Int, permission: String) = "$pkg|$cloneId|$permission"

    fun isAllowed(ctx: Context, pkg: String, cloneId: Int, permission: String): Boolean =
        p(ctx).getBoolean(key(pkg, cloneId, permission), true)

    fun setAllowed(ctx: Context, pkg: String, cloneId: Int, permission: String, allowed: Boolean) {
        p(ctx).edit().putBoolean(key(pkg, cloneId, permission), allowed).apply()
    }

    // The only permissions gated per-clone for now (the sensitive ones RuntimePerms already
    // shows in the app-wide Permissions screen). checkSelfPermission/checkPermission for any
    // permission not in this set pass straight through to the real OS answer, unchanged.
    val GATED = setOf(
        android.Manifest.permission.CAMERA,
        android.Manifest.permission.RECORD_AUDIO,
        android.Manifest.permission.ACCESS_FINE_LOCATION,
        android.Manifest.permission.ACCESS_COARSE_LOCATION,
        android.Manifest.permission.READ_MEDIA_IMAGES,
        android.Manifest.permission.READ_MEDIA_VIDEO,
        android.Manifest.permission.READ_MEDIA_AUDIO,
        android.Manifest.permission.BLUETOOTH_CONNECT,
        android.Manifest.permission.READ_CONTACTS,
        android.Manifest.permission.WRITE_CONTACTS
    )
}
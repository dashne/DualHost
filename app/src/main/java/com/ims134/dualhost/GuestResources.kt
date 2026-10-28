package com.ims134.dualhost

import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import android.util.DisplayMetrics

// Guest Resources that treat DualHost's package name as the guest's own in lookups

@Suppress("DEPRECATION","DiscouragedApi")
class GuestResources(
    assets: AssetManager, dm: DisplayMetrics, cfg: Configuration,
    private val guestPkg: String
) : Resources(assets, dm, cfg) {

    override fun getIdentifier(name: String?, defType: String?, defPackage: String?): Int {
        val pkg = if (defPackage == "com.ims134.dualhost") guestPkg else defPackage
        return super.getIdentifier(name, defType, pkg)
    }

    override fun getString(id: Int): String {
        return try {
            super.getString(id)
        } catch (e: Resources.NotFoundException) {
            FileLog.d("DIAG getString(0x${Integer.toHexString(id)}) NOT FOUND")
            throw e
        }
    }
}
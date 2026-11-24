package com.ims134.dualhost

import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import android.util.DisplayMetrics
import androidx.core.graphics.drawable.toDrawable

// Guest Resources that treat DualHost's package name as the guest's own in lookups

@Suppress("DEPRECATION", "DiscouragedApi", "OverrideDeprecatedMember")
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

    //--------
    @Deprecated("Deprecated in Java")
    override fun getDrawable(id: Int): android.graphics.drawable.Drawable {
        return try {
            super.getDrawable(id)
        } catch (e: Resources.NotFoundException) {
            FileLog.d("DIAG getDrawable(0x${Integer.toHexString(id)}) NOT FOUND, returning transparent fallback")
            android.graphics.Color.TRANSPARENT.toDrawable()
        }
    }

    override fun getDrawable(id: Int, theme: Theme?): android.graphics.drawable.Drawable {
        return try {
            super.getDrawable(id, theme)
        } catch (e: Resources.NotFoundException) {
            FileLog.d("DIAG getDrawable(0x${Integer.toHexString(id)}, theme) NOT FOUND, returning transparent fallback")
            android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun getDrawableForDensity(id: Int, density: Int): android.graphics.drawable.Drawable? {
        return try {
            super.getDrawableForDensity(id, density)
        } catch (e: Resources.NotFoundException) {
            FileLog.d("DIAG getDrawableForDensity(0x${Integer.toHexString(id)}, $density) NOT FOUND, returning transparent fallback")
            android.graphics.Color.TRANSPARENT.toDrawable()
        }
    }

    override fun getDrawableForDensity(id: Int, density: Int, theme: Theme?): android.graphics.drawable.Drawable? {
        return try {
            super.getDrawableForDensity(id, density, theme)
        } catch (e: Resources.NotFoundException) {
            FileLog.d("DIAG getDrawableForDensity(0x${Integer.toHexString(id)}, $density, theme) NOT FOUND, returning transparent fallback")
            android.graphics.Color.TRANSPARENT.toDrawable()
        }
    }
}

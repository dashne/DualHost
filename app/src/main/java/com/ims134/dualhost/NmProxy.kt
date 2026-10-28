package com.ims134.dualhost

import android.app.Notification
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Icon
import android.util.Log
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

object NmProxy {
    private const val HOST = "com.ims134.dualhost"
    private var real: Any? = null

    // Turn "resource in DualHost" icons into bitmaps drawn from the guest's resources
    fun fix(n: Notification) {
        val res = GuestEnvs.map.values.firstOrNull()?.resources ?: return
        val small = n.smallIcon
        if (small != null) {
            val b = toBitmapIcon(small, res)
            if (b != null) {
                try {
                    Notification::class.java.getMethod("setSmallIcon", Icon::class.java).invoke(n, b)
                    FileLog.d( "NM small icon converted")
                } catch (e: Throwable) {
                    try {
                        val f = Notification::class.java.getDeclaredField("mSmallIcon")
                        f.isAccessible = true
                        f.set(n, b)
                        FileLog.d( "NM small icon converted (field)")
                    } catch (e2: Throwable) {
                        FileLog.d( "NM could not set small icon: " + e2)
                    }
                }
            }
        }
        val large = n.getLargeIcon()
        if (large != null) {
            val b = toBitmapIcon(large, res)
            if (b != null) {
                try {
                    val f = Notification::class.java.getDeclaredField("mLargeIcon")
                    f.isAccessible = true
                    f.set(n, b)
                    FileLog.d( "NM large icon converted")
                } catch (e: Throwable) {
                    FileLog.d( "NM could not set large icon: " + e)
                }
            }
        }
    }

    private fun toBitmapIcon(icon: Icon, res: android.content.res.Resources): Icon? {
        return try {
            if (icon.type != Icon.TYPE_RESOURCE || icon.resPackage != HOST) return null
            val d = res.getDrawable(icon.resId, null) ?: return null
            val size = 96
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            d.setBounds(0, 0, size, size)
            d.draw(Canvas(bmp))
            Icon.createWithBitmap(bmp)
        } catch (e: Throwable) {
            FileLog.d( "NM icon convert failed: " + e)
            null
        }
    }

    // Notification calls look like (pkg, opPkg, tag, id, ..., userId). Prefix the tag with the clone id.
    private fun tagArgs(args: Array<Any?>) {
        val id = ProcessClone.id
        if (id < 0 || args.size < 5) return
        if (args[0] !is String || args[3] !is Int) return
        val old = args[2] as? String
        val prefix = "dh" + id + ":"
        if (old != null && old.startsWith(prefix)) return
        args[2] = prefix + (old ?: "")
    }

    fun install() {
        try {
            val nm = Class.forName("android.app.NotificationManager")
            real = nm.getDeclaredMethod("getService").invoke(null)
            val iface = Class.forName("android.app.INotificationManager")
            val proxy = Proxy.newProxyInstance(iface.classLoader, arrayOf(iface)) { _, m, args ->
                if (m.name == "enqueueNotificationWithTag" && args != null) {
                    try {
                        args.firstOrNull { it is Notification }?.let { fix(it as Notification) }
                        tagArgs(args)
                    } catch (e: Throwable) {
                        FileLog.d( "NM fix failed: " + e)
                    }
                }
                if (m.name == "cancelNotificationWithTag" && args != null) {
                    try { tagArgs(args) } catch (e: Throwable) { }
                }

                try {
                    if (args == null) m.invoke(real) else m.invoke(real, *args)
                } catch (e: InvocationTargetException) {
                    throw e.targetException
                }
            }
            val f = nm.getDeclaredField("sService")
            f.isAccessible = true
            f.set(null, proxy)
            FileLog.d( "NmProxy installed")
        } catch (e: Throwable) {
            FileLog.d( "NmProxy failed: " + e)
        }
    }
}
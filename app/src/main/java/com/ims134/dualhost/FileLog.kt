package com.ims134.dualhost

import android.app.Application
import android.util.Log
import java.io.File

object FileLog {
    @Volatile private var file: File? = null

    // The file is opened on the first message after the Application exists
    private fun open(): File? {
        file?.let { return it }
        try {
            val app = Class.forName("android.app.ActivityThread")
                .getDeclaredMethod("currentApplication").invoke(null) as? Application ?: return null
            val dir = File(app.filesDir, "dhlog")
            dir.mkdirs()
            val name = ProcessClone.realName.substringAfterLast(":", "main")
            val f = File(dir, "log_" + name + ".txt")
            if (f.length() > 200_000) f.writeText("")
            file = f
            return f
        } catch (e: Throwable) {
            return null
        }
    }

    fun d(msg: String) {
        Log.d("DualHost", msg)
        try {
            val f = open() ?: return
            synchronized(this) {
                f.appendText(
                    java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
                        .format(java.util.Date()) + " " + msg + "\n")
            }
        } catch (e: Throwable) { }
    }

    // Added 2026-11-20: names the guest method that is asking, for the CsProxy/PmProxy
    // diagnostics. Same idea as the PKGSURVEY lines in CloneContext: the next capture shows
    // WHICH guest code drives each proxied call, not only that the call happened.
    fun callerChain(): String = try {
        Thread.currentThread().stackTrace
            .drop(2)   // getStackTrace + callerChain itself
            .filter {
                !it.className.startsWith("com.ims134.dualhost.") &&
                        !it.className.startsWith("java.") && !it.className.startsWith("javax.") &&
                        !it.className.startsWith("android.") && !it.className.startsWith("kotlin.")
            }
            .take(3)
            .joinToString(" <- ") { it.className.substringAfterLast('.') + "." + it.methodName }
            .ifEmpty { "(no guest frames)" }
    } catch (e: Throwable) { "?" }
}
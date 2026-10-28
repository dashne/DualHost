package com.ims134.dualhost

import android.content.Context
import java.io.File

object CloneOptions {
    private fun dir(c: Context, pkg: String) = File(c.filesDir, "guest/" + pkg)

    fun nameMode(c: Context, pkg: String): String {
        val f = File(dir(c, pkg), "name_override.txt")
        return if (f.exists()) f.readText().trim() else "auto"
    }

    fun setNameMode(c: Context, pkg: String, mode: String) {
        val d = dir(c, pkg); d.mkdirs()
        val f = File(d, "name_override.txt")
        if (mode == "auto") f.delete() else f.writeText(mode)
    }

    // What the dex scan decided for this app ("own" or "host"), or null if it has not been scanned yet.
    fun scanMode(c: Context, pkg: String): String? {
        val versions = dir(c, pkg).listFiles()?.filter { it.isDirectory } ?: return null
        for (v in versions.sortedByDescending { it.lastModified() }) {
            val f = File(v, "names.txt")
            if (f.exists()) return f.readText().trim()
        }
        return null
    }

    fun procGuest(c: Context, pkg: String): Boolean =
        File(dir(c, pkg), "process_name_guest.txt").exists()

    fun setProcGuest(c: Context, pkg: String, on: Boolean) {
        val d = dir(c, pkg); d.mkdirs()
        val f = File(d, "process_name_guest.txt")
        if (on) f.writeText("on") else f.delete()
    }
}
package com.ims134.dualhost

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.AndroidViewModel
import java.io.File

class LinkMatch(val clone: Clone, val number: Int, val handler: String, val apk: String,
                      val icon: androidx.compose.ui.graphics.ImageBitmap?)

class LinkViewModel(app: Application) : AndroidViewModel(app) {

    var link by mutableStateOf("")
    var matches by mutableStateOf<List<LinkMatch>>(emptyList())
        private set
    var checked by mutableStateOf(false)
        private set

    fun search() {
        val c = getApplication<Application>()
        val text = link.trim()
        val uri = Uri.parse(text)
        checked = true
        if (uri.scheme == null) { matches = emptyList(); return }
        val out = ArrayList<LinkMatch>()
        CloneStore.list(c).forEachIndexed { index, cl ->
            try {
                val installed = GuestInstaller.install(c, cl.pkg)
                if (installed.problem != null) return@forEachIndexed
                val apk = File(installed.dir, "base.apk").absolutePath
                val handler = OpenLinkHandlers.find(c, apk, uri)
                val icon = try {
                    c.packageManager.getApplicationIcon(cl.pkg).toBitmap(96, 96).asImageBitmap()
                } catch (e: Throwable) { null }
                if (handler != null) out.add(LinkMatch(cl, index + 1, handler, apk, icon))
            } catch (e: Throwable) {
                android.util.Log.d("DualHost", "LinkViewModel check failed for " + cl.pkg + ": " + e)
            }
        }
        matches = out
    }
}
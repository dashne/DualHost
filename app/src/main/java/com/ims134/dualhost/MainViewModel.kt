package com.ims134.dualhost

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.AndroidViewModel
import java.io.File

enum class TileState { OPEN, BACKGROUND, STOPPED }

// number = position in the grid, counted from 1. clone.id is the data folder and never changes.
class Tile(val clone: Clone, val number: Int, val state: TileState, val slot: Int?, val icon: ImageBitmap?)

class BusyAsk(val oldest: Int, val oldName: String, val clone: Clone, val apk: File)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    var tiles by mutableStateOf<List<Tile>>(emptyList())
        private set
    var pendingRemove by mutableStateOf<Tile?>(null)

    var optionsFor by mutableStateOf<Tile?>(null)
    var busyAsk by mutableStateOf<BusyAsk?>(null)
    var askRemoveAll by mutableStateOf(false)

    private val icons = HashMap<String, ImageBitmap?>()

    private fun iconOf(pkg: String): ImageBitmap? = icons.getOrPut(pkg) {
        try {
            getApplication<Application>().packageManager.getApplicationIcon(pkg)
                .toBitmap(96, 96).asImageBitmap()
        } catch (e: Throwable) { null }
    }

    fun refresh() {
        val c = getApplication<Application>()
        val running = SlotManager.runningSlots(c)
        val open = SlotManager.openSlots(c)
        val slotOfClone = HashMap<Int, Int>()
        for (s in running) SlotManager.cloneIn(c, s)?.let { slotOfClone[it.id] = s }
        tiles = CloneStore.list(c).mapIndexed { index, cl ->
            val s = slotOfClone[cl.id]
            val st = when {
                s == null -> TileState.STOPPED
                s in open -> TileState.OPEN
                else -> TileState.BACKGROUND
            }
            Tile(cl, index + 1, st, s, iconOf(cl.pkg))
        }
    }

    // The process must already be killed. Waits a moment, then deletes the clone's files.
    fun remove(clone: Clone) {
        val c = getApplication<Application>()
        Thread {
            try {
                Thread.sleep(600)
                try {
                    val nm = c.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                    val mine = "dh" + clone.id + ":"
                    var n = 0
                    for (sbn in nm.activeNotifications) {
                        if (sbn.tag?.startsWith(mine) == true) { nm.cancel(sbn.tag, sbn.id); n++ }
                    }
                    FileLog.d( "CLONEREMOVE notifications cancelled=" + n)
                } catch (e: Throwable) {
                    FileLog.d( "CLONEREMOVE notif failed: " + e)
                }
                try {
                    val js = c.getSystemService(android.content.Context.JOB_SCHEDULER_SERVICE) as android.app.job.JobScheduler
                    var jn = 0
                    for (job in js.allPendingJobs) {
                        if (job.extras.getInt("dh_clone", -1) == clone.id) { js.cancel(job.id); jn++ }
                    }
                    FileLog.d( "CLONEREMOVE jobs cancelled=" + jn)
                } catch (e: Throwable) {
                    FileLog.d( "CLONEREMOVE jobs failed: " + e)
                }
                val root = File(c.dataDir, "virtual/" + clone.pkg + "/" + clone.id)
                val ok = root.deleteRecursively()
                val ext = c.getExternalFilesDir(null)?.let { File(it, "virtual/" + clone.pkg + "/" + clone.id) }
                val extOk = ext?.deleteRecursively()
                val extCache = c.externalCacheDir?.let { File(it, "virtual/" + clone.pkg + "/" + clone.id) }
                extCache?.deleteRecursively()
                val prefix = "clone_" + clone.pkg + "_" + clone.id + "_"
                var prefsDeleted = 0
                File(c.dataDir, "shared_prefs").listFiles()?.forEach {
                    if (it.name.startsWith(prefix) && it.delete()) prefsDeleted++
                }
                CloneStore.remove(c, clone.id)
                FileLog.d( "CLONEREMOVE id=" + clone.id + " " + clone.pkg +
                        " folder=" + ok + " prefsFiles=" + prefsDeleted + " ext=" + extOk)
            } catch (e: Throwable) {
                FileLog.d( "CLONEREMOVE failed: " + e)
            }
            Handler(Looper.getMainLooper()).post { refresh() }
        }.start()
    }

    // All slot processes must already be killed.
    fun removeAll() {
        val c = getApplication<Application>()
        Thread {
            try {
                Thread.sleep(800)
                try {
                    (c.getSystemService(android.content.Context.JOB_SCHEDULER_SERVICE) as android.app.job.JobScheduler).cancelAll()
                } catch (e: Throwable) { }
                val ok = File(c.dataDir, "virtual").deleteRecursively()
                c.getExternalFilesDir(null)?.let { File(it, "virtual").deleteRecursively() }
                c.externalCacheDir?.let { File(it, "virtual").deleteRecursively() }
                var prefsDeleted = 0
                File(c.dataDir, "shared_prefs").listFiles()?.forEach {
                    if (it.name.startsWith("clone_") && it.delete()) prefsDeleted++
                }
                CloneStore.clear(c)
                FileLog.d( "CLONEREMOVEALL virtual=" + ok + " prefsFiles=" + prefsDeleted)
            } catch (e: Throwable) {
                FileLog.d( "CLONEREMOVEALL failed: " + e)
            }
            Handler(Looper.getMainLooper()).post { refresh() }
        }.start()
    }
}
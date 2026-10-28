package com.ims134.dualhost

import android.app.ActivityManager
import android.content.Context

object SlotManager {
    private fun prefs(c: Context) = c.getSharedPreferences("dualhost_slots", Context.MODE_PRIVATE)

    // Asks Android which :pN processes are alive right now
    fun runningSlots(c: Context): Set<Int> {
        val am = c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val prefix = c.packageName + ":p"
        val out = HashSet<Int>()
        am.runningAppProcesses?.forEach { p ->
            if (p.processName.startsWith(prefix)) {
                p.processName.substring(prefix.length).toIntOrNull()?.let { out.add(it) }
            }
        }
        return out
    }

    fun assign(c: Context, slot: Int, cloneId: Int) {
        prefs(c).edit().putInt("slot_" + slot, cloneId).apply()
    }

    fun cloneIn(c: Context, slot: Int): Clone? {
        val id = prefs(c).getInt("slot_" + slot, -1)
        return CloneStore.list(c).firstOrNull { it.id == id }
    }

    // The live slot that runs this clone, or null
    fun slotOf(c: Context, cloneId: Int): Int? =
        runningSlots(c).firstOrNull { prefs(c).getInt("slot_" + it, -1) == cloneId }

    fun freeSlot(c: Context): Int? {
        val busy = runningSlots(c)
        return Slots.stubs.indices.firstOrNull { it !in busy }
    }

    // Among live slots, the one opened longest ago (unknown counts as oldest)
    fun oldestSlot(c: Context): Int? =
        runningSlots(c).minByOrNull { cloneIn(c, it)?.lastOpened ?: 0L }

    // Slots that have a window (task) open, as seen from Recents
    fun openSlots(c: Context): Set<Int> {
        val am = c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val out = HashSet<Int>()
        try {
            for (t in am.appTasks) {
                val cn = t.taskInfo?.baseActivity ?: continue
                val idx = Slots.stubs.indexOfFirst { it.name == cn.className }
                if (idx >= 0) out.add(idx)
            }
        } catch (e: Throwable) {
            android.util.Log.d("DualHost", "openSlots failed: " + e)
        }
        return out
    }
}
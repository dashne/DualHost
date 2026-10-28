package com.ims134.dualhost

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.edit

class Clone(val id: Int, val pkg: String, val launcher: String, val label: String, val lastOpened: Long)

// Saved clones. The id is the data folder number and never changes.
object CloneStore {
    private const val FIRST_NEW_ID = 10

    private fun prefs(c: Context) = c.getSharedPreferences("dualhost_clones", Context.MODE_PRIVATE)

    private fun ids(c: Context): List<Int> =
        (prefs(c).getString("ids", "") ?: "").split(",").mapNotNull { it.toIntOrNull() }

    fun list(c: Context): List<Clone> {
        migrate(c)
        val p = prefs(c)
        val out = ArrayList<Clone>()
        for (id in ids(c)) {
            val pkg = p.getString("c" + id + "_pkg", null) ?: continue
            out.add(Clone(id, pkg, p.getString("c" + id + "_launcher", "") ?: "",
                p.getString("c" + id + "_label", pkg) ?: pkg, p.getLong("c" + id + "_last", 0L)))
        }
        return out
    }

    fun add(c: Context, pkg: String, launcher: String, label: String): Clone {
        val id = maxOf(FIRST_NEW_ID, (ids(c).maxOrNull() ?: -1) + 1)
        save(c, id, pkg, launcher, label)
        return Clone(id, pkg, launcher, label, 0L)
    }

    fun touch(c: Context, id: Int) {
        prefs(c).edit { putLong("c" + id + "_last", System.currentTimeMillis()) }
    }

    // Forgets a clone. Its files are deleted by MainViewModel.remove
    fun remove(c: Context, id: Int) {
        val p = prefs(c)
        p.edit {
            putString("ids", ids(c).filter { it != id }.joinToString(","))
            for (k in listOf("_pkg", "_launcher", "_label", "_last")) remove("c" + id + k)
        }
        val sp = c.getSharedPreferences("dualhost_slots", Context.MODE_PRIVATE)
        sp.edit {
            for (s in 0..9) if (sp.getInt("slot_" + s, -1) == id) remove("slot_$s")
        }
        c.getSharedPreferences("dualhost", Context.MODE_PRIVATE).edit { remove("clonekey_" + id) }
    }

    private fun save(c: Context, id: Int, pkg: String, launcher: String, label: String) {
        val p = prefs(c)
        val all = ids(c).toMutableList()
        if (!all.contains(id)) all.add(id)
        p.edit {
            putString("ids", all.joinToString(","))
                .putString("c" + id + "_pkg", pkg)
                .putString("c" + id + "_launcher", launcher)
                .putString("c" + id + "_label", label)
        }
    }

    // Old clones: slot N used data folder N, so it becomes clone id N
    private fun migrate(c: Context) {
        val p = prefs(c)
        if (p.getBoolean("migrated", false)) return
        val old = c.getSharedPreferences("dualhost", Context.MODE_PRIVATE)
        val pm = c.packageManager
        for (i in 0..9) {
            val pkg = (old.getString("slotkey_$i", null) ?: continue).substringBefore(":")
            try {
                val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg)
                val ri = pm.queryIntentActivities(q, 0).firstOrNull() ?: continue
                save(c, i, pkg, ri.activityInfo.targetActivity ?: ri.activityInfo.name,
                    ri.loadLabel(pm).toString())
                Log.d("DualHost", "CLONES migrated id=$i $pkg")
            } catch (e: Throwable) {
                Log.d("DualHost", "CLONES migrate failed for $pkg: $e")
            }
        }
        p.edit { putBoolean("migrated", true) }
    }

    // Forgets every clone and slot record. Files are deleted by MainViewModel.removeAll
    fun clear(c: Context) {
        prefs(c).edit().clear().putBoolean("migrated", true).apply()
        c.getSharedPreferences("dualhost_slots", Context.MODE_PRIVATE).edit().clear().apply()
        val d = c.getSharedPreferences("dualhost", Context.MODE_PRIVATE)
        val e = d.edit()
        for (k in d.all.keys) if (k.startsWith("clonekey_") || k.startsWith("slotkey_")) e.remove(k)
        e.apply()
    }
    fun exists(c: Context, id: Int, pkg: String?): Boolean =
        list(c).any { it.id == id && (pkg == null || it.pkg == pkg) }
}
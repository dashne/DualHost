package com.ims134.dualhost

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File

open class BaseHostReceiver : BroadcastReceiver() {
    // The slot this alarm was made for now runs another clone: run it in a free slot instead
    private fun moveToFreeSlot(context: Context, intent: Intent, clone: Int): Boolean {
        // Same clone already running somewhere? Ring there. Otherwise, use a free slot.
        val free = SlotManager.slotOf(context, clone) ?: SlotManager.freeSlot(context) ?: return false
        if (free >= Slots.receivers.size) return false
        val i = Intent(intent)
        i.component = ComponentName(context.packageName, Slots.receivers[free].name)
        i.putExtra("dh_vuid", free)
        i.putExtra("dh_hops", 1)
        SlotManager.assign(context, free, clone)
        context.sendBroadcast(i)
        FileLog.d( "HOSTRECV moved to free slot $free for clone $clone")
        return true
    }
    override fun onReceive(context: Context, intent: Intent) {
        val cls = intent.getStringExtra("dh_class") ?: return
        val apk = intent.getStringExtra("dh_apk")
        val pkg = intent.getStringExtra("dh_pkg")
        val vuid = intent.getIntExtra("dh_vuid", 0)
        val clone = intent.getIntExtra("dh_clone", vuid)
        FileLog.d( "HOSTRECV fired for $cls clone=$clone")
        if (!CloneStore.exists(context, clone, pkg)) {
            FileLog.d( "HOSTRECV ignored, clone $clone was removed")
            return
        }

        val pr = goAsync()
        var handed = false
        var getPending: java.lang.reflect.Method? = null
        val inst = HostInstrumentation.current
        if (apk != null && inst != null && inst.slotBusyWith(apk, clone)) {
            if (intent.getIntExtra("dh_hops", 0) < 1 && moveToFreeSlot(context, intent, clone)) {
                pr?.finish()
                return
            }
            FileLog.d( "HOSTRECV slot busy and no free slot, skipped clone $clone")
            pr?.finish()
            return
        }
        try {
            var env = if (apk != null) GuestEnvs.map[apk] else null
            if (env == null && apk != null && pkg != null && File(apk).exists()) {
                env = HostInstrumentation.current?.ensureEnv(apk, vuid, pkg, clone)
            }
            if (env == null) {
                FileLog.d( "HOSTRECV no guest environment for $cls")
                pr?.finish()
                return
            }
            val r = env.loader.loadClass(cls).getDeclaredConstructor().newInstance() as BroadcastReceiver
            try {
                val set = BroadcastReceiver::class.java.getDeclaredMethod(
                    "setPendingResult", BroadcastReceiver.PendingResult::class.java)
                set.isAccessible = true
                getPending = BroadcastReceiver::class.java.getDeclaredMethod("getPendingResult")
                getPending.isAccessible = true
                set.invoke(r, pr)
                handed = true
            } catch (e: Throwable) {
                FileLog.d( "HOSTRECV could not hand over pending result: $e")
            }
            val i = Intent(intent)
            i.component = ComponentName("com.ims134.dualhost", cls)
            i.removeExtra("dh_class"); i.removeExtra("dh_apk")
            i.removeExtra("dh_pkg"); i.removeExtra("dh_vuid")
            i.removeExtra("dh_clone")
            r.onReceive(env.application, i)
            FileLog.d( "HOSTRECV guest $cls onReceive done")

            val left = if (handed) getPending?.invoke(r) else null
            if (!handed || left != null) pr?.finish()
        } catch (e: Throwable) {
            FileLog.d( "HOSTRECV failed for " + cls + ": " + e + " cause=" + e.cause)
            pr?.finish()
        }
    }
}

class HostReceiver0 : BaseHostReceiver()
class HostReceiver1 : BaseHostReceiver()
class HostReceiver2 : BaseHostReceiver()
class HostReceiver3 : BaseHostReceiver()
class HostReceiver4 : BaseHostReceiver()
class HostReceiver5 : BaseHostReceiver()
class HostReceiver6 : BaseHostReceiver()
class HostReceiver7 : BaseHostReceiver()
class HostReceiver8 : BaseHostReceiver()
class HostReceiver9 : BaseHostReceiver()
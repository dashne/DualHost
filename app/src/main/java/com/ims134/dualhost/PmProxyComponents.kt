package com.ims134.dualhost



import android.content.ComponentName
import java.util.concurrent.ConcurrentHashMap

object PmProxyComponents {
    private val overrides = ConcurrentHashMap<String, Int>()

    /**
     * Call from your setComponentEnabledSetting hook. Returns true when consumed
     * (guest package => always consume, never throw — the old IllegalArgumentException
     * blocked Viber's AccountAuthenticatorService / AccountContactbookService /
     * CallerIdCallScreeningService / androidx.work SystemJobService at startup).
     */
    fun onSetComponentEnabledSetting(component: ComponentName, newState: Int, flags: Int): Boolean {
        if (component.packageName != /* ADAPT: guest pkg */ "com.viber.voip") return false
        overrides[component.flattenToString()] = newState
        FileLog.d( "PM setComponentEnabledSetting applied: ${component.flattenToString()} -> $newState (flags=$flags)")
        return true
    }

    /** Call from your getComponentEnabledSetting hook; pass your current default as fallback. */
    fun onGetComponentEnabledSetting(component: ComponentName, currentDefault: Int): Int =
        overrides[component.flattenToString()] ?: currentDefault
}
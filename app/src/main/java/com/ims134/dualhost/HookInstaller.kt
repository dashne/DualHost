package com.ims134.dualhost

import android.app.Application
import android.app.Instrumentation
import android.util.Log

object HookInstaller {

    // Each clone process needs its own WebView data folder, or two clones crash each other
    private fun setWebViewSuffix(proc: String) {
        try {
            val suffix = proc.substringAfterLast(":")
            android.webkit.WebView.setDataDirectorySuffix(suffix)
            FileLog.d( "WebView data dir suffix set: " + suffix)
        } catch (e: Throwable) {
            FileLog.d( "WebView suffix failed: " + e)
        }
    }

    fun install() {
        try {
            val atClass = Class.forName("android.app.ActivityThread")
            val thread = atClass.getDeclaredMethod("currentActivityThread").invoke(null)
            val f = atClass.getDeclaredField("mInstrumentation")
            f.isAccessible = true
            val current = f.get(thread) as Instrumentation
            if (current is HostInstrumentation) return

            f.set(thread, HostInstrumentation(current))
            val proc = Application.getProcessName()
            ProcessClone.realName = proc ?: ""

            if (proc.contains(":p") || proc == "com.viber.voip") {
                try {
                    val bf = atClass.getDeclaredField("mBoundApplication")
                    bf.isAccessible = true
                    val bound = bf.get(thread)!!
                    val pf = bound.javaClass.getDeclaredField("processName")
                    pf.isAccessible = true
                    FileLog.d( "PROCNAME field readable, value=" + pf.get(bound))
                } catch (e: Throwable) {
                    FileLog.d( "PROCNAME field blocked: " + e)
                }
                try {
                    FileLog.d( "PROCNAME cmdline=" +
                            java.io.File("/proc/self/cmdline").readText().trim { it <= ' ' })
                } catch (e: Throwable) { }
                setWebViewSuffix(proc)
                PmProxy.install()
                // Added 2026-10-31: see PermProxy's file comment — on Android versions where
                // checkPermission moved off IPackageManager onto its own IPermissionManager
                // service, PmProxy's own checkPermission interception (just below it in this
                // file's history) never fires, so this covers that case too. Must come after
                // PmProxy.install() since guestApk (used to detect the guest's own package name)
                // is read from PmProxy, not duplicated here.
                PermProxy.install()
                NmProxy.install()
                AmProxy.install()
                // Added 2026-10-31: see TmProxy's file comment. Must come after AmProxy.install()
                // since it calls AmProxy.fix() directly (no dependency on AmProxy's installed
                // proxy object itself, just the function, but keeping the order documents intent).
                TmProxy.install()
                // Added 2026-10-31: see CsProxy's file comment — durable local-answer fix for
                // ContentResolver.isSyncActive() instead of depending on this ROM's
                // READ_SYNC_STATS grant state.
                CsProxy.install()
            }
            FileLog.d( "HookInstaller: installed in " + proc)
        } catch (e: Throwable) {
            FileLog.d( "HookInstaller failed: " + e)
        }
    }
}
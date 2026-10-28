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
                NmProxy.install()
                AmProxy.install()
            }
            FileLog.d( "HookInstaller: installed in " + proc)
        } catch (e: Throwable) {
            FileLog.d( "HookInstaller failed: " + e)
        }
    }
}
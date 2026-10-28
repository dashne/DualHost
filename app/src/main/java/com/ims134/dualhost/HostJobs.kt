package com.ims134.dualhost

import android.app.job.JobParameters
import android.app.job.JobService
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

// Guest environments by APK path, filled in by HostInstrumentation
object GuestEnvs {
    val map = ConcurrentHashMap<String, GuestEnv>()
}

open class BaseHostJob : JobService() {

    companion object {
        private val running = ConcurrentHashMap<Int, JobService>()
    }

    override fun onStartJob(params: JobParameters): Boolean {
        // --- replaces the old cls / apk / env lines ---
        val cls = params.extras.getString("dh_class")
        val apk = params.extras.getString("dh_apk")
        val pkg = params.extras.getString("dh_pkg")
        val vuid = params.extras.getInt("dh_vuid", 0)
        val clone = params.extras.getInt("dh_clone", vuid)
        FileLog.d( "HOSTJOB clone=" + clone + " " + cls)
        if (!CloneStore.exists(applicationContext, clone, pkg)) {
            FileLog.d( "HOSTJOB ignored, clone " + clone + " was removed")
            return false
        }
        var env = if (apk != null) GuestEnvs.map[apk] else null
        if (env == null && apk != null && pkg != null && java.io.File(apk).exists()) {
            FileLog.d( "HOSTJOB building guest environment")
            env = HostInstrumentation.current?.ensureEnv(apk, vuid, pkg, clone)
        }
        // --- everything below is unchanged from Step 57 ---
        if (cls == null || env == null) {
            FileLog.d( "HOSTJOB no guest environment for " + cls + " (clone not running in this process)")
            return false
        }
        return try {
            val svc = env.loader.loadClass(cls).getDeclaredConstructor().newInstance() as JobService
            val attach = ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
            attach.isAccessible = true
            attach.invoke(svc, env.application)
            svc.onCreate()
            svc.onBind(Intent())
            running[params.jobId] = svc
            val more = svc.onStartJob(params)
            if (!more) running.remove(params.jobId)
            FileLog.d( "HOSTJOB guest " + cls + " onStartJob returned " + more)
            more
        } catch (e: Throwable) {
            FileLog.d( "HOSTJOB failed for " + cls + ": " + e + " cause=" + e.cause)
            false
        }
    }

    override fun onStopJob(params: JobParameters): Boolean {
        FileLog.d( "HOSTJOB onStopJob id=" + params.jobId)
        val svc = running.remove(params.jobId) ?: return false
        return try { svc.onStopJob(params) } catch (e: Throwable) { false }
    }

    @Suppress("NewApi")
    override fun onNetworkChanged(params: JobParameters) {
        val svc = running[params.jobId]
        if (svc != null) {
            try { svc.onNetworkChanged(params) } catch (e: Throwable) {
                FileLog.d( "HOSTJOB onNetworkChanged failed: " + e)
            }
        } else {
            super.onNetworkChanged(params)
        }
    }
}

class HostJob0 : BaseHostJob()
class HostJob1 : BaseHostJob()
class HostJob2 : BaseHostJob()
class HostJob3 : BaseHostJob()
class HostJob4 : BaseHostJob()
class HostJob5 : BaseHostJob()
class HostJob6 : BaseHostJob()
class HostJob7 : BaseHostJob()
class HostJob8 : BaseHostJob()
class HostJob9 : BaseHostJob()
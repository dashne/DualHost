package com.ims134.dualhost

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.app.job.JobWorkItem
import android.content.ComponentName
import android.os.PersistableBundle
import android.util.Log

// Rewrites a guest's job so it targets our proxy service for this slot
class LoggingJobScheduler(private val real: JobScheduler, private val vuid: Int, private val pkg: String) : JobScheduler() {

    companion object {
        private const val HOST = "com.ims134.dualhost"
        private val seen = HashSet<String>()
//        private val hostJobs: List<Class<*>> = listOf(
//            HostJob0::class.java, HostJob1::class.java, HostJob2::class.java,
//            HostJob3::class.java, HostJob4::class.java, HostJob5::class.java
//        )
private val hostJobs: List<Class<*>> = Slots.jobs
    }

    private fun logOnce(s: String) {
        if (synchronized(seen) { seen.add(s) }) Log.d("DualHost", s)
    }

    private fun rewrite(job: JobInfo): JobInfo {
        val cn = job.service
        if (cn.className.startsWith("com.ims134.dualhost.")) return job
        if (cn.packageName != HOST && cn.packageName != pkg) return job
        if (vuid !in hostJobs.indices) return job

        val extras = PersistableBundle(job.extras)
        extras.putString("dh_class", cn.className)
        extras.putString("dh_apk", PmProxy.guestApk ?: "")
        extras.putInt("dh_vuid", vuid)
        extras.putInt("dh_clone", if (ProcessClone.id >= 0) ProcessClone.id else vuid)
        extras.putString("dh_pkg", pkg)

        val b = JobInfo.Builder(job.id, ComponentName(HOST, hostJobs[vuid].name))
            .setExtras(extras)
            .setTransientExtras(job.transientExtras)
            //.setBackoffCriteria(job.initialBackoffMillis, job.backoffPolicy)
            .setRequiresCharging(job.isRequireCharging)
            .setRequiresDeviceIdle(job.isRequireDeviceIdle)
            .setRequiresBatteryNotLow(job.isRequireBatteryNotLow)
            .setRequiresStorageNotLow(job.isRequireStorageNotLow)
            .setPersisted(false)
        if (!job.isRequireDeviceIdle) b.setBackoffCriteria(job.initialBackoffMillis, job.backoffPolicy)
        job.requiredNetwork?.let { b.setRequiredNetwork(it) }
        job.triggerContentUris?.let { uris ->
            for (u in uris) b.addTriggerContentUri(u)
            if (job.triggerContentUpdateDelay >= 0) b.setTriggerContentUpdateDelay(job.triggerContentUpdateDelay)
            if (job.triggerContentMaxDelay >= 0) b.setTriggerContentMaxDelay(job.triggerContentMaxDelay)
            logOnce("JOB content triggers copied: " + uris.size + " for " + cn.className)
        }
        if (job.isPeriodic) {
            b.setPeriodic(job.intervalMillis, job.flexMillis)
        } else {
            if (job.minLatencyMillis > 0) b.setMinimumLatency(job.minLatencyMillis)
            if (job.maxExecutionDelayMillis > 0) b.setOverrideDeadline(job.maxExecutionDelayMillis)
        }
        logOnce("JOB rewritten id=" + job.id + " " + cn.className + " -> " + hostJobs[vuid].simpleName)
        return b.build()
    }

    override fun schedule(job: JobInfo): Int = real.schedule(rewrite(job))

    override fun enqueue(job: JobInfo, work: JobWorkItem): Int = real.enqueue(rewrite(job), work)

    override fun cancel(jobId: Int) = real.cancel(jobId)

    override fun cancelAll() = real.cancelAll()

    override fun getAllPendingJobs(): MutableList<JobInfo> = real.allPendingJobs

    override fun getPendingJob(jobId: Int): JobInfo? = real.getPendingJob(jobId)

    // Android 14+: apps can ask for a scheduler with their own namespace
    @Suppress("NewApi")
    override fun forNamespace(namespace: String): JobScheduler =
        LoggingJobScheduler(real.forNamespace(namespace), vuid, pkg)

    @Suppress("NewApi")
    override fun getNamespace(): String? = real.namespace
}
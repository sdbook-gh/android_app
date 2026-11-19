package com.example.mobiledatanotifier

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import android.util.Log

/**
 * 兜底保活：周期性检查 OverlayService 是否存活，若被杀则重新拉起。
 * - 基于 JobScheduler 周期任务，最小间隔 15 分钟（系统限制）。
 * - setPersisted(true)：设备重启后任务依然生效。
 * - Doze 维护窗口也会触发，作为"被系统杀后"的兜底拉起。
 */
class KeepAliveJob : JobService() {

    companion object {
        private const val JOB_ID = 7001
        // ColorOS can throttle repeated JobScheduler.schedule() calls.
        private const val MIN_RESCHEDULE_MS = 60_000L
        @Volatile
        private var lastScheduledAt = 0L
        private const val TAG = "KeepAliveJob"
        private const val INTERVAL_MS = 15 * 60 * 1000L  // 15 分钟

        @Synchronized
        fun schedule(ctx: Context) {
            val now = SystemClock.elapsedRealtime()
            val last = lastScheduledAt
            // last == 0 means this process has never successfully registered the job.
            // Allow that first registration even during the first minute after boot.
            if (last != 0L && now >= last && now - last < MIN_RESCHEDULE_MS) {
                Log.d(TAG, "Skip duplicate schedule request; last success was ${now - last}ms ago")
                return
            }
            try {
                val js = ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
                val job = JobInfo.Builder(JOB_ID, ComponentName(ctx, KeepAliveJob::class.java))
                    .setPeriodic(INTERVAL_MS)
                    .setPersisted(true)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
                    .build()
                val result = js.schedule(job)
                if (result == JobScheduler.RESULT_SUCCESS) {
                    lastScheduledAt = now
                    Log.i(TAG, "Periodic keep-alive job scheduled; id=$JOB_ID")
                } else {
                    Log.e(TAG, "JobScheduler rejected keep-alive job; id=$JOB_ID result=$result")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to schedule keep-alive job; id=$JOB_ID", e)
            }
        }
    }

    override fun onStartJob(params: JobParameters?): Boolean {
        Log.i(TAG, "Keep-alive job fired")
        if (!Prefs.isServiceEnabled(this)) {
            Log.i(TAG, "Service disabled; keep-alive job does nothing")
            return false
        }
        if (!ProcessUtil.isOverlayAlive(this)) {
            Log.w(TAG, "OverlayService is not running; requesting recovery")
            // A persisted JobScheduler job can be the first callback after a ROM
            // suppresses BOOT_COMPLETED; create a recovery attempt before relaunch.
            try { BootStartupFeedback.beginRecoveryAttempt(this) }
            catch (e: Exception) { android.util.Log.e("KeepAliveJob", "Could not schedule recovery verification", e) }
            try { OverlayService.start(this) }
            catch (e: Exception) { android.util.Log.e("KeepAliveJob", "OverlayService recovery start failed", e) }
        }
        if (!ProcessUtil.isGuardAlive(this)) {
            try { GuardService.start(this) } catch (_: Exception) {}
        }
        try { AlarmKeeper.scheduleRolling(this) } catch (_: Exception) {}
        return false  // 任务已同步完成，无需后台线程
    }

    override fun onStopJob(params: JobParameters?): Boolean = true  // 被系统中断则重新调度
}

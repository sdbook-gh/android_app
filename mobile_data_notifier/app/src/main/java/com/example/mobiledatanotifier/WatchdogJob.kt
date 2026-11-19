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
 * 第二道兜底守护：与 KeepAliveJob 互为"双保险"。
 * 当 OverlayService 被杀后，KeepAliveJob 可能因进程消亡一并丢失调度，
 * 此时 WatchdogJob 独立存在，负责再次拉起服务并重建所有保活任务。
 */
class WatchdogJob : JobService() {

    companion object {
        private const val JOB_ID = 7002
        private const val MIN_RESCHEDULE_MS = 60_000L
        @Volatile
        private var lastScheduledAt = 0L
        private const val TAG = "WatchdogJob"
        private const val JOB_ID_ONESHOT = 7003
        private const val INTERVAL_MS = 15 * 60 * 1000L

        @Synchronized
        fun schedule(ctx: Context) {
            val now = SystemClock.elapsedRealtime()
            val last = lastScheduledAt
            // First registration must not be dropped while elapsedRealtime is under 60s.
            if (last != 0L && now >= last && now - last < MIN_RESCHEDULE_MS) {
                Log.d(TAG, "Skip duplicate schedule request; last success was ${now - last}ms ago")
                return
            }
            try {
                val js = ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
                val job = JobInfo.Builder(JOB_ID, ComponentName(ctx, WatchdogJob::class.java))
                    .setPeriodic(INTERVAL_MS)
                    .setPersisted(true)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
                    .build()
                val result = js.schedule(job)
                if (result == JobScheduler.RESULT_SUCCESS) {
                    lastScheduledAt = now
                    Log.i(TAG, "Periodic watchdog job scheduled; id=$JOB_ID")
                } else {
                    Log.e(TAG, "JobScheduler rejected watchdog job; id=$JOB_ID result=$result")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to schedule watchdog job; id=$JOB_ID", e)
            }
        }

        /** 划掉任务/进程死亡后的一次性短延迟拉起，不走 15 分钟周期。 */
        fun scheduleImmediate(ctx: Context, delayMs: Long = 1500L) {
            if (!Prefs.isServiceEnabled(ctx)) {
                Log.d(TAG, "Skip one-shot watchdog: service disabled")
                return
            }
            try {
                val js = ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
                val job = JobInfo.Builder(JOB_ID_ONESHOT, ComponentName(ctx, WatchdogJob::class.java))
                    .setMinimumLatency(delayMs)
                    .setOverrideDeadline(delayMs + 2000L)
                    .setPersisted(true)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
                    .build()
                val result = js.schedule(job)
                if (result == JobScheduler.RESULT_SUCCESS) {
                    Log.i(TAG, "One-shot watchdog scheduled; delayMs=$delayMs")
                } else {
                    Log.e(TAG, "JobScheduler rejected one-shot watchdog; result=$result delayMs=$delayMs")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to schedule one-shot watchdog; delayMs=$delayMs", e)
            }
        }
    }

    override fun onStartJob(params: JobParameters?): Boolean {
        Log.i(TAG, "Watchdog job fired")
        if (!Prefs.isServiceEnabled(this)) {
            Log.i(TAG, "Service disabled; watchdog job does nothing")
            return false
        }
        if (!ProcessUtil.isOverlayAlive(this)) {
            Log.w(TAG, "OverlayService is not running; requesting recovery")
            // A persisted JobScheduler job can be the first callback after a ROM
            // suppresses BOOT_COMPLETED; create a recovery attempt before relaunch.
            try { BootStartupFeedback.beginRecoveryAttempt(this) }
            catch (e: Exception) { android.util.Log.e("WatchdogJob", "Could not schedule recovery verification", e) }
            try { OverlayService.start(this) }
            catch (e: Exception) { android.util.Log.e("WatchdogJob", "OverlayService recovery start failed", e) }
        }
        if (!ProcessUtil.isGuardAlive(this)) {
            try { GuardService.start(this) } catch (_: Exception) {}
        }
        // 确保另外两道保活也在运行
        try { KeepAliveJob.schedule(this) } catch (_: Exception) {}
        try { schedule(this) } catch (_: Exception) {}
        try { AlarmKeeper.register(this) } catch (_: Exception) {}
        try { AlarmKeeper.scheduleRolling(this) } catch (_: Exception) {}
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean = true
}

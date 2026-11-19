package com.example.mobiledatanotifier

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * 精确闹钟兜底：使用 AlarmManager.setExactAndAllowWhileIdle 保证即使在 Doze 模式下
 * 也能按时唤醒，触发守护接收器检查并重启服务。
 *
 * 滚动短延迟闹钟由系统持有：进程被杀但未被强制停止时，约 3 秒内仍会被拉回。
 */
object AlarmKeeper {

    private const val INTERVAL_MS = 5 * 60 * 1000L
    private const val ROLLING_MS = 3000L
    private const val RC_PERIODIC = 0
    private const val RC_RESTART = 1
    private const val RC_ROLLING = 2
    private const val RC_RESTART_BASE = 100
    private const val RESTART_STAGGER_COUNT = 3
    private const val TAG = "AlarmKeeper"
    private const val EXTRA_KEEP_ALIVE = "com.example.mobiledatanotifier.KEEP_ALIVE"

    /**
     * 注册周期心跳闹钟。Android 14+ 默认不授予 SCHEDULE_EXACT_ALARM，
     * 无权限时降级为 setAndAllowWhileIdle（inexact），保证闹钟链不断。
     * 设备重启后由 BootReceiver / MultiBroadcastReceiver 重新注册。
     */
    fun register(ctx: Context) {
        schedule(ctx, INTERVAL_MS, RC_PERIODIC)
    }

    /**
     * 任务被从最近列表划掉后的延迟重启：闹钟由系统持有，
     * 不随进程死亡而丢失，触发后由 MultiBroadcastReceiver 拉起全部保活组件。
     */
    fun scheduleRestart(ctx: Context, delayMs: Long = 1000L) {
        if (!Prefs.isServiceEnabled(ctx)) return
        schedule(ctx, delayMs, RC_RESTART)
    }

    /**
     * 开机/恢复后的错峰重试。每次重试使用独立 PendingIntent，
     * 避免多个 set() 因相同 requestCode 互相覆盖。
     */
    fun scheduleStaggeredRestarts(ctx: Context, delaysMs: List<Long>) {
        if (!Prefs.isServiceEnabled(ctx)) {
            Log.i(TAG, "Skip staggered restart alarms: service disabled")
            return
        }
        if (delaysMs.size > RESTART_STAGGER_COUNT) {
            Log.w(TAG, "Only first $RESTART_STAGGER_COUNT staggered restart delays are supported")
        }
        delaysMs.take(RESTART_STAGGER_COUNT).forEachIndexed { index, delay ->
            schedule(ctx, delay, RC_RESTART_BASE + index)
        }
    }

    /** 服务运行期间持续刷新「约 3 秒后」的精确闹钟。 */
    fun scheduleRolling(ctx: Context, delayMs: Long = ROLLING_MS) {
        if (!Prefs.isServiceEnabled(ctx)) return
        schedule(ctx, delayMs, RC_ROLLING)
    }

    /** 用户主动停止保活时取消全部闹钟，避免停了又被拉起。 */
    fun cancel(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        (listOf(RC_PERIODIC, RC_RESTART, RC_ROLLING) +
            (RC_RESTART_BASE until RC_RESTART_BASE + RESTART_STAGGER_COUNT)).forEach { rc ->
            val pi = pendingIntent(ctx, rc)
            try { am.cancel(pi) } catch (_: Exception) {}
            try { pi.cancel() } catch (_: Exception) {}
        }
    }

    private fun pendingIntent(ctx: Context, requestCode: Int): PendingIntent {
        val intent = Intent(ctx, MultiBroadcastReceiver::class.java).apply {
            action = EXTRA_KEEP_ALIVE
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(ctx, requestCode, intent, flags)
    }

    private fun schedule(ctx: Context, delayMs: Long, requestCode: Int) {
        val am = try {
            ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        } catch (e: Exception) {
            Log.e(TAG, "AlarmManager unavailable: requestCode=$requestCode delayMs=$delayMs", e)
            return
        }
        val pi = try {
            pendingIntent(ctx, requestCode)
        } catch (e: Exception) {
            Log.e(TAG, "Could not create alarm PendingIntent: requestCode=$requestCode", e)
            return
        }
        val triggerAt = System.currentTimeMillis() + delayMs
        val canExact = try {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        } catch (e: Exception) {
            // If this ROM cannot answer the permission query, still attempt a best-effort inexact alarm.
            Log.w(TAG, "Could not query exact-alarm access; using inexact scheduling", e)
            false
        }
        if (canExact) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                } else {
                    am.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                }
                Log.i(TAG, "Scheduled exact alarm: requestCode=$requestCode delayMs=$delayMs")
                return
            } catch (e: SecurityException) {
                // Exact-alarm access can be revoked between checking and scheduling.
                Log.w(TAG, "Exact alarm denied; falling back to inexact: requestCode=$requestCode", e)
            } catch (e: Exception) {
                Log.e(TAG, "Exact alarm scheduling failed; trying inexact: requestCode=$requestCode", e)
            }
        } else {
            Log.w(TAG, "Exact alarms unavailable; using inexact scheduling: requestCode=$requestCode")
        }

        try {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            Log.i(TAG, "Scheduled inexact alarm: requestCode=$requestCode delayMs=$delayMs")
        } catch (e: Exception) {
            Log.e(TAG, "Could not schedule alarm: requestCode=$requestCode delayMs=$delayMs", e)
        }
    }
}

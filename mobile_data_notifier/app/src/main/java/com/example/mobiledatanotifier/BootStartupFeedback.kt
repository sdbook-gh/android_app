package com.example.mobiledatanotifier

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Audible confirmation that startup/recovery happened, plus a delayed health check.
 *
 * This can report a failed launch only when Android runs either the boot receiver or
 * the persisted watchdog job. No app-side mechanism can report a failure if the OS
 * force-stops the package or suppresses every callback.
 */
object BootStartupFeedback {
    const val ACTION_VERIFY_BOOT_START =
        "com.example.mobiledatanotifier.action.VERIFY_BOOT_START"

    private const val PREFS = "startup_feedback"
    private const val KEY_ATTEMPT_ID = "attempt_id"
    private const val KEY_STARTED_ID = "started_id"
    private const val KEY_FAILURE_ID = "failure_id"
    private const val EXTRA_ATTEMPT_ID = "attempt_id"
    private const val VERIFY_REQUEST_CODE = 9017
    private const val STATUS_CHANNEL = "ch_startup_feedback"
    private const val STATUS_NOTIFICATION_ID = 9017
    private const val VERIFY_DELAY_MS = 45_000L
    private const val ACTIVE_ATTEMPT_MS = 180_000L
    private const val TAG = "BootStartupFeedback"
    /** MainActivity 自动打开厂商自启动设置页的通知参数。 */
    const val EXTRA_OPEN_AUTOSTART = "com.example.mobiledatanotifier.open_autostart_settings"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Called by BOOT_COMPLETED (not package replacement). */
    fun beginBootAttempt(context: Context) {
        beginAttempt(context, "boot")
    }

    /** Called by a persisted job if it finds monitoring stopped unexpectedly. */
    fun beginRecoveryAttempt(context: Context) {
        beginAttempt(context, "recovery")
    }

    private fun beginAttempt(context: Context, reason: String) {
        if (!Prefs.isServiceEnabled(context)) return
        val ctx = context.applicationContext
        val now = System.currentTimeMillis()
        val sp = prefs(ctx)
        val oldId = sp.getLong(KEY_ATTEMPT_ID, 0L)
        val oldStarted = sp.getLong(KEY_STARTED_ID, 0L)
        if (oldId > 0L && now - oldId in 0L..ACTIVE_ATTEMPT_MS && oldStarted != oldId) {
            scheduleVerification(ctx, oldId)
            return
        }

        sp.edit()
            .putLong(KEY_ATTEMPT_ID, now)
            .putLong(KEY_STARTED_ID, 0L)
            .putLong(KEY_FAILURE_ID, 0L)
            .commit()
        createChannel(ctx)
        Log.i(TAG, "Startup attempt $now ($reason)")
        scheduleVerification(ctx, now)
    }

    /** Called only when OverlayService has reached onCreate/startForeground successfully. */
    fun onServiceStarted(context: Context) {
        val ctx = context.applicationContext
        val sp = prefs(ctx)
        val attemptId = sp.getLong(KEY_ATTEMPT_ID, 0L)
        if (attemptId <= 0L || System.currentTimeMillis() - attemptId > ACTIVE_ATTEMPT_MS) return
        if (sp.getLong(KEY_STARTED_ID, 0L) == attemptId) return

        sp.edit().putLong(KEY_STARTED_ID, attemptId).commit()
        Log.i(TAG, "Monitoring service started for attempt $attemptId")
        postStatus(
            ctx,
            title = "移动数据监控已启动",
            message = "启动提示：移动数据监控正在运行",
            isFailure = false
        )
        playDoubleBeep()
    }

    /** Called by MultiBroadcastReceiver after the grace period. */
    fun verifyStartup(context: Context, intent: Intent?) {
        val ctx = context.applicationContext
        val attemptId = intent?.getLongExtra(EXTRA_ATTEMPT_ID, 0L) ?: 0L
        val sp = prefs(ctx)
        if (attemptId <= 0L || sp.getLong(KEY_ATTEMPT_ID, 0L) != attemptId) return
        if (!Prefs.isServiceEnabled(ctx)) return

        val alive = ProcessUtil.isOverlayAlive(ctx)
        val intentionallyHidden = Prefs.isOverlayHiddenByUser(ctx)
        val overlayShown = OverlayService.isOverlayShown
        Log.i(
            TAG,
            "Verify startup attempt=$attemptId ageMs=${System.currentTimeMillis() - attemptId} " +
                "alive=$alive overlayShown=$overlayShown intentionallyHidden=$intentionallyHidden"
        )
        when {
            !alive -> reportFailure(
                ctx,
                attemptId,
                "移动数据监控未启动",
                "开机后监控服务没有成功运行。点击此通知打开应用并检查自启动/后台运行权限。"
            )
            !intentionallyHidden && !overlayShown -> reportFailure(
                ctx,
                attemptId,
                "移动数据监控悬浮窗未显示",
                "服务已运行，但悬浮窗未显示。请检查悬浮窗权限和后台弹出界面权限。"
            )
            sp.getLong(KEY_STARTED_ID, 0L) != attemptId -> onServiceStarted(ctx)
            else -> Log.i(TAG, "Startup attempt $attemptId verified")
        }
    }

    private fun reportFailure(
        context: Context,
        attemptId: Long,
        title: String,
        message: String
    ) {
        val sp = prefs(context)
        if (sp.getLong(KEY_FAILURE_ID, 0L) == attemptId) return
        sp.edit().putLong(KEY_FAILURE_ID, attemptId).commit()
        Log.w(TAG, "$title (attempt $attemptId)")
        postStatus(context, title, message, isFailure = true)
        playDoubleBeep()
    }

    private fun scheduleVerification(context: Context, attemptId: Long) {
        val ctx = context.applicationContext
        val intent = Intent(ctx, MultiBroadcastReceiver::class.java)
            .setAction(ACTION_VERIFY_BOOT_START)
            .putExtra(EXTRA_ATTEMPT_ID, attemptId)
        val pi = PendingIntent.getBroadcast(
            ctx,
            VERIFY_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerAt = System.currentTimeMillis() + VERIFY_DELAY_MS
        try {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        } catch (e: SecurityException) {
            // Exact-alarm permission is optional; keep a best-effort inexact check.
            Log.w(TAG, "Exact alarm unavailable; using an inexact startup check", e)
            try {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } catch (fallbackError: Exception) {
                Log.e(TAG, "Could not schedule inexact startup verification", fallbackError)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not schedule startup verification", e)
        }
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                STATUS_CHANNEL,
                "启动状态与故障提醒",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "移动数据监控开机启动结果和异常提醒"
                enableVibration(true)
                vibrationPattern = longArrayOf(0L, 180L, 120L, 180L)
                // The audible cue is produced directly via ToneGenerator so it still
                // works when Android 13+ notification permission has not been granted.
                setSound(null, null)
                setShowBadge(false)
            }
        )
    }

    private fun postStatus(
        context: Context,
        title: String,
        message: String,
        isFailure: Boolean
    ) {
        try {
            createChannel(context)
            val launch = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val pi = PendingIntent.getActivity(
                context,
                if (isFailure) STATUS_NOTIFICATION_ID + 1 else STATUS_NOTIFICATION_ID,
                launch,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val builder = Notification.Builder(context, STATUS_CHANNEL)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(title)
                .setContentText(message)
                .setContentIntent(pi)
                .setAutoCancel(!isFailure)
                .setOnlyAlertOnce(true)
                .setTimeoutAfter(if (isFailure) 0L else 12_000L)
            if (isFailure) {
                // Failures are commonly caused by vendor autostart/background restrictions.
                val autoStart = Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(EXTRA_OPEN_AUTOSTART, true)
                val autoStartPi = PendingIntent.getActivity(
                    context,
                    STATUS_NOTIFICATION_ID + 2,
                    autoStart,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                @Suppress("DEPRECATION")
                builder.addAction(0, "打开自启动设置", autoStartPi)
            }
            val notification = builder.build()
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(if (isFailure) STATUS_NOTIFICATION_ID + 1 else STATUS_NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification permission is unavailable", e)
        } catch (e: Exception) {
            Log.e(TAG, "Could not post startup status notification", e)
        }
    }

    private fun playDoubleBeep() {
        val generator = try {
            ToneGenerator(AudioManager.STREAM_ALARM, 80)
        } catch (e: Exception) {
            Log.w(TAG, "Could not initialize audible startup cue", e)
            return
        }
        val handler = Handler(Looper.getMainLooper())
        try {
            generator.startTone(ToneGenerator.TONE_PROP_BEEP, 180)
            handler.postDelayed({
                try {
                    generator.startTone(ToneGenerator.TONE_PROP_BEEP2, 180)
                } catch (e: Exception) {
                    Log.w(TAG, "Second startup beep failed", e)
                }
                handler.postDelayed({
                    try { generator.release() } catch (_: Exception) {}
                }, 350L)
            }, 260L)
        } catch (e: Exception) {
            try { generator.release() } catch (_: Exception) {}
            Log.w(TAG, "Could not play audible startup cue", e)
        }
    }
}

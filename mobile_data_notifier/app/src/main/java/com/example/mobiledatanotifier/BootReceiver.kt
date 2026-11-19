package com.example.mobiledatanotifier

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.os.SystemClock

/** 开机自启：启动保活服务并重新排程定时提醒。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i("BootReceiver", "Received action=${intent.action} uptimeMs=${SystemClock.elapsedRealtime()}")
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON" -> {
                if (!Prefs.isServiceEnabled(context)) {
                    Log.w("BootReceiver", "Service disabled; skip startup for action=${intent.action}")
                    return
                }
                val isBootEvent = intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
                if (isBootEvent) {
                    try { BootStartupFeedback.beginBootAttempt(context) }
                    catch (e: Exception) { Log.e("BootReceiver", "Could not schedule boot-start verification", e) }
                }
                try {
                    OverlayService.start(context)
                    Log.i("BootReceiver", "OverlayService start request accepted")
                } catch (e: Exception) {
                    Log.e("BootReceiver", "OverlayService start request failed", e)
                }
                try {
                    GuardService.start(context)
                    Log.i("BootReceiver", "GuardService start request accepted")
                } catch (e: Exception) {
                    Log.e("BootReceiver", "GuardService start request failed", e)
                }
                try { KeepAliveJob.schedule(context) } catch (_: Exception) {}
                try { WatchdogJob.schedule(context) } catch (_: Exception) {}
                try { AlarmKeeper.register(context) } catch (_: Exception) {}
                try { AlarmKeeper.scheduleRolling(context) } catch (_: Exception) {}
                try { ScheduleManager.rescheduleAll(context) } catch (_: Exception) {}
                // 错峰重试：使用独立 PendingIntent，避免 5/15/30 秒闹钟相互覆盖。
                try {
                    AlarmKeeper.scheduleStaggeredRestarts(context, listOf(5_000L, 15_000L, 30_000L))
                } catch (e: Exception) {
                    Log.e("BootReceiver", "Could not schedule staggered startup retries", e)
                }
            }
        }
    }
}

package com.example.mobiledatanotifier

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import java.util.ArrayDeque

/** 内存中的一条移动数据开关变化。不落盘，进程被杀即清空。 */
class DataSwitchEntry(
    val timeMillis: Long,
    val enabled: Boolean,
    val appLabel: String,
    val packageName: String
)

/**
 * 环形缓冲：记录移动数据开关开/关，并关联当时前台应用名。
 * 系统不提供「谁拨的开关」；前台应用只是关联，不是确证。
 */
object DataSwitchLog {
    const val MAX_ENTRIES = 50
    private const val DEDUP_MS = 1500L
    private const val FG_WINDOW_MS = 10_000L

    private val lock = Any()
    private val entries = ArrayDeque<DataSwitchEntry>()
    private val listeners = ArrayList<() -> Unit>()
    @Volatile
    private var lastKnown: Boolean? = null

    /** 持续上报当前开关状态；仅在与上次不同时写入一条记录。首次只记状态不写日志。 */
    fun note(ctx: Context, enabled: Boolean) {
        val prev: Boolean?
        synchronized(lock) {
            prev = lastKnown
            lastKnown = enabled
        }
        if (prev == null || prev == enabled) return
        record(ctx, enabled)
    }

    fun snapshot(): List<DataSwitchEntry> {
        synchronized(lock) {
            return ArrayList(entries)
        }
    }

    fun clear() {
        synchronized(lock) {
            entries.clear()
        }
        notifyListeners()
    }

    fun addListener(listener: () -> Unit) {
        synchronized(lock) {
            listeners.add(listener)
        }
    }

    fun removeListener(listener: () -> Unit) {
        synchronized(lock) {
            listeners.remove(listener)
        }
    }

    private fun record(ctx: Context, enabled: Boolean) {
        val now = System.currentTimeMillis()
        val fg = resolveForeground(ctx, now)
        val entry = DataSwitchEntry(now, enabled, fg.first, fg.second)
        var changed = false
        synchronized(lock) {
            val newest = entries.peekFirst()
            if (newest != null && newest.enabled == enabled && now - newest.timeMillis < DEDUP_MS) {
                return
            }
            entries.addFirst(entry)
            while (entries.size > MAX_ENTRIES) entries.removeLast()
            changed = true
        }
        if (changed) notifyListeners()
    }

    private fun notifyListeners() {
        val copy: List<() -> Unit>
        synchronized(lock) {
            copy = ArrayList(listeners)
        }
        for (listener in copy) {
            try {
                listener()
            } catch (_: Exception) {
            }
        }
    }

    private fun resolveForeground(ctx: Context, now: Long): Pair<String, String> {
        if (!PermUtil.hasUsageAccess(ctx)) {
            return Pair(ctx.getString(R.string.data_log_no_usage), "")
        }
        val pkg = queryForegroundPackage(ctx, now)
            ?: return Pair(ctx.getString(R.string.data_log_unknown_app), "")
        if (isSystemUi(pkg)) {
            return Pair(ctx.getString(R.string.data_log_system_ui), pkg)
        }
        val label = appLabel(ctx, pkg)
        return Pair(ctx.getString(R.string.data_log_fg_fmt, label), pkg)
    }

    @Suppress("DEPRECATION")
    private fun queryForegroundPackage(ctx: Context, now: Long): String? {
        return try {
            val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val events = usm.queryEvents(now - FG_WINDOW_MS, now)
            val ev = UsageEvents.Event()
            val resumed = ArrayList<String>()
            while (events.hasNextEvent()) {
                events.getNextEvent(ev)
                val resume = ev.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                    ev.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND
                if (!resume) continue
                val pkg = ev.packageName ?: continue
                resumed.add(pkg)
            }
            for (i in resumed.size - 1 downTo 0) {
                val pkg = resumed[i]
                if (pkg != ctx.packageName) return pkg
            }
            resumed.lastOrNull()
        } catch (_: SecurityException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun isSystemUi(pkg: String): Boolean {
        val p = pkg.lowercase()
        if (p == "com.android.systemui" ||
            p == "com.android.settings" ||
            p == "com.android.phone" ||
            p == "com.oplus.wirelesssettings"
        ) return true
        if (p.contains("systemui")) return true
        return (p.startsWith("com.android.") || p.startsWith("com.oplus.") || p.startsWith("com.coloros.")) &&
            p.contains("settings")
    }

    private fun appLabel(ctx: Context, pkg: String): String {
        return try {
            val pm = ctx.packageManager
            val info = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(info).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            pkg
        } catch (_: Exception) {
            pkg
        }
    }
}

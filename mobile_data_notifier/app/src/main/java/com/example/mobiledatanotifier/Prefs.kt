package com.example.mobiledatanotifier

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import java.io.File

/** SharedPreferences 封装：定时时段、流量统计基线、保活/悬浮窗开关。 */
object Prefs {
    private const val NAME = "mdn_prefs"
    private const val KEY_PERIODS = "periods"
    private const val KEY_BASELINE = "usage_baseline"
    private const val KEY_BASELINE_TIME = "usage_baseline_time"
    private const val KEY_BASELINE_ELAPSED = "usage_baseline_elapsed"
    private const val KEY_TRAFFIC_SCOPE = "traffic_scope"
    private const val KEY_NEXT_PERIOD_ID = "next_period_id"
    private const val KEY_CUMULATIVE_USAGE = "cumulative_usage"
    private const val KEY_SERVICE_ENABLED = "service_enabled"
    private const val KEY_OVERLAY_HIDDEN = "overlay_hidden_by_user"
    private const val KEY_OVERLAY_POS_X = "overlay_pos_x"
    private const val KEY_OVERLAY_POS_Y = "overlay_pos_y"
    private const val KEY_WARNING_MUSIC_URI = "warning_music_uri"

    private fun sp(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun getPeriods(ctx: Context): MutableList<TimePeriod> {
        val raw = sp(ctx).getString(KEY_PERIODS, null) ?: return mutableListOf()
        return try {
            TimePeriod.fromJsonArray(JSONArray(raw))
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun savePeriods(ctx: Context, periods: List<TimePeriod>) {
        val arr = JSONArray()
        periods.forEach { arr.put(it.toJson()) }
        sp(ctx).edit().putString(KEY_PERIODS, arr.toString()).apply()
    }

    fun nextPeriodId(ctx: Context): Long {
        val cur = sp(ctx).getLong(KEY_NEXT_PERIOD_ID, 1L)
        sp(ctx).edit().putLong(KEY_NEXT_PERIOD_ID, cur + 1).apply()
        return cur
    }

    fun setUsageBaseline(ctx: Context, bytes: Long) {
        sp(ctx).edit()
            .putLong(KEY_BASELINE, bytes)
            .putLong(KEY_BASELINE_TIME, System.currentTimeMillis())
            .putLong(KEY_BASELINE_ELAPSED, SystemClock.elapsedRealtime())
            .apply()
    }

    fun getUsageBaseline(ctx: Context): Long = sp(ctx).getLong(KEY_BASELINE, 0L)

    fun getUsageBaselineTime(ctx: Context): Long = sp(ctx).getLong(KEY_BASELINE_TIME, 0L)

    fun getUsageBaselineElapsed(ctx: Context): Long = sp(ctx).getLong(KEY_BASELINE_ELAPSED, 0L)

    fun getTrafficScope(ctx: Context): TrafficScope =
        TrafficScope.fromPref(sp(ctx).getString(KEY_TRAFFIC_SCOPE, TrafficScope.MOBILE.prefValue))

    fun setTrafficScope(ctx: Context, scope: TrafficScope) {
        sp(ctx).edit().putString(KEY_TRAFFIC_SCOPE, scope.prefValue).apply()
    }

    fun getCumulativeUsage(ctx: Context): Long = sp(ctx).getLong(KEY_CUMULATIVE_USAGE, 0L)

    fun setCumulativeUsage(ctx: Context, bytes: Long) {
        sp(ctx).edit().putLong(KEY_CUMULATIVE_USAGE, bytes).apply()
    }

    fun resetCumulativeUsage(ctx: Context) {
        sp(ctx).edit().remove(KEY_CUMULATIVE_USAGE).apply()
    }

    /**
     * 用户是否要保活服务。划掉/进程死亡后的重启路径必须尊重此标记。commit 以免进程马上被杀时丢失。
     *
     * 缺键自愈：开关键只应被显式写入（true=用户在 App 内启用，false=用户主动停止）。
     * 若 prefs 文件已存在（App 此前运行过）却没有这个键，说明键因异常丢失
     * （历史版本使用已废弃的 MODE_MULTI_PROCESS，多进程各自缓存整份 map，
     * 任一进程写入都会覆盖整个文件、静默丢弃其它进程写入的键），此时按“启用”处理，
     * 避免一次丢键导致开机广播 / KeepAliveJob / WatchdogJob 全部提前退出、
     * App 永久无法自启（自愈时把开关写回 prefs，后续读取恢复走正常路径）。
     * 全新安装（prefs 为空）仍返回 false，
     * 等待用户首次打开 App 时由 MainActivity.onCreate 置 true。
     */
    fun isServiceEnabled(ctx: Context): Boolean {
        val p = sp(ctx)
        if (p.contains(KEY_SERVICE_ENABLED)) return p.getBoolean(KEY_SERVICE_ENABLED, false)
        // 键丢失自愈：prefs 里已有其它数据或文件已存在，说明 App 曾运行过、用户启用过服务，
        // 按启用处理并写回修复；全新安装（prefs 为空且文件不存在）仍返回 false。
        if (p.all.isNotEmpty() || prefsFileExists(ctx)) {
            Log.w("Prefs", "service_enabled key missing on an existing prefs file; self-healing to enabled")
            try { setServiceEnabled(ctx, true) } catch (_: Exception) {}
            return true
        }
        return false
    }

    /** prefs 文件是否已创建过：App 此前运行过的判据，全新安装时为 false。 */
    private fun prefsFileExists(ctx: Context): Boolean = try {
        File(ctx.applicationInfo.dataDir, "shared_prefs/$NAME.xml").exists()
    } catch (_: Exception) {
        false
    }

    fun setServiceEnabled(ctx: Context, enabled: Boolean) {
        sp(ctx).edit().putBoolean(KEY_SERVICE_ENABLED, enabled).commit()
    }

    /** 用户是否主动隐藏了悬浮窗。服务重建后按此决定是否重新挂载。 */
    fun isOverlayHiddenByUser(ctx: Context): Boolean =
        sp(ctx).getBoolean(KEY_OVERLAY_HIDDEN, false)

    fun setOverlayHiddenByUser(ctx: Context, hidden: Boolean) {
        sp(ctx).edit().putBoolean(KEY_OVERLAY_HIDDEN, hidden).commit()
    }

    /** 用户拖动后悬浮窗停留的位置（相对屏幕左上角）。commit 保证进程被杀前落盘。 */
    fun getOverlayPosX(ctx: Context): Int =
        sp(ctx).getInt(KEY_OVERLAY_POS_X, 0)

    fun getOverlayPosY(ctx: Context): Int =
        sp(ctx).getInt(KEY_OVERLAY_POS_Y, 0)

    fun setOverlayPos(ctx: Context, x: Int, y: Int) {
        sp(ctx).edit()
            .putInt(KEY_OVERLAY_POS_X, x)
            .putInt(KEY_OVERLAY_POS_Y, y)
            .commit()
    }
    fun getWarningMusicUri(ctx: Context): String? =
        sp(ctx).getString(KEY_WARNING_MUSIC_URI, null)

    fun setWarningMusicUri(ctx: Context, uri: String?) {
        sp(ctx).edit().putString(KEY_WARNING_MUSIC_URI, uri).apply()
    }

    // ---- 余量查询结果（来自 WebView 内 20085 接口明文） ----
    private const val KEY_MARGIN_USED = "margin_used"
    private const val KEY_MARGIN_REMAIN = "margin_remain"
    private const val KEY_MARGIN_UNIT = "margin_unit"
    private const val KEY_MARGIN_RAW = "margin_raw"
    private const val KEY_MARGIN_TIME = "margin_update_time"
    private const val KEY_MARGIN_STALE = "margin_stale"
    private const val KEY_WEB_STORAGE = "web_storage_snapshot"
    private const val KEY_MARGIN_REFRESH_SEC = "margin_refresh_sec"
    private const val KEY_MARGIN_KEEPALIVE_SEC = "margin_keepalive_sec"
    private const val KEY_OVERLAY_MARGIN_SHOW_SEC = "overlay_margin_show_sec"

    fun getMarginUsed(ctx: Context): String? = sp(ctx).getString(KEY_MARGIN_USED, null)

    fun getMarginRemain(ctx: Context): String? = sp(ctx).getString(KEY_MARGIN_REMAIN, null)

    fun getMarginUnit(ctx: Context): String? = sp(ctx).getString(KEY_MARGIN_UNIT, null)

    fun getMarginRaw(ctx: Context): String? = sp(ctx).getString(KEY_MARGIN_RAW, null)

    fun getMarginUpdateTime(ctx: Context): Long = sp(ctx).getLong(KEY_MARGIN_TIME, 0L)

    fun saveMargin(ctx: Context, used: String?, remain: String?, unit: String?, raw: String?) {
        sp(ctx).edit()
            .putString(KEY_MARGIN_USED, used)
            .putString(KEY_MARGIN_REMAIN, remain)
            .putString(KEY_MARGIN_UNIT, unit)
            .putString(KEY_MARGIN_RAW, raw)
            .putLong(KEY_MARGIN_TIME, System.currentTimeMillis())
            .apply()
    }

    /**
     * WebView 的 localStorage/sessionStorage 快照（JSON）。
     * 余量查询页的登录态有一部分只存在前端存储里，尤其 sessionStorage 不跨
     * WebView 实例，必须自己落盘再写回，否则每次都要重新收验证码登录。
     */
    fun getWebStorage(ctx: Context): String? = sp(ctx).getString(KEY_WEB_STORAGE, null)

    fun saveWebStorage(ctx: Context, json: String?) {
        sp(ctx).edit().putString(KEY_WEB_STORAGE, json).apply()
    }

    /** 主界面余量自动刷新间隔（秒）。0 = 关闭（只保留手动刷新）。 */
    fun getMarginRefreshIntervalSec(ctx: Context): Int = sp(ctx).getInt(KEY_MARGIN_REFRESH_SEC, 0)

    fun setMarginRefreshIntervalSec(ctx: Context, sec: Int) {
        sp(ctx).edit().putInt(KEY_MARGIN_REFRESH_SEC, sec).apply()
    }

    /** 后台会话保活间隔（秒）。0 = 关闭。默认 300（5 分钟）。 */
    fun getMarginKeepAliveSec(ctx: Context): Int = sp(ctx).getInt(KEY_MARGIN_KEEPALIVE_SEC, 300)

    fun setMarginKeepAliveSec(ctx: Context, sec: Int) {
        sp(ctx).edit().putInt(KEY_MARGIN_KEEPALIVE_SEC, sec).apply()
    }

    fun isMarginStale(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_MARGIN_STALE, false)

    fun setMarginStale(ctx: Context, stale: Boolean) {
        sp(ctx).edit().putBoolean(KEY_MARGIN_STALE, stale).apply()
    }

    /** 悬浮窗「查询流量」显示时长（秒）。0 = 一直显示（不自动恢复）。 */
    fun getOverlayMarginShowSec(ctx: Context): Int = sp(ctx).getInt(KEY_OVERLAY_MARGIN_SHOW_SEC, 10)

    fun setOverlayMarginShowSec(ctx: Context, sec: Int) {
        sp(ctx).edit().putInt(KEY_OVERLAY_MARGIN_SHOW_SEC, sec).apply()
    }
}

package com.example.mobiledatanotifier

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.app.AlertDialog
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 主界面：权限授予、状态展示、悬浮窗/保活控制、定时时段管理。
 * 仅使用 Android 框架 API（不引入 AndroidX，避免 Maven 依赖）。
 */
class MainActivity : Activity() {

    private lateinit var tvDataState: TextView
    private lateinit var cardWlanAssistant: LinearLayout
    private lateinit var tvWlanSectionTitle: TextView
    private lateinit var tvWlanBanner: TextView
    private lateinit var wlanFlagsContainer: LinearLayout
    private lateinit var tvDataLogHint: TextView
    private lateinit var tvDataLogEmpty: TextView
    private lateinit var dataLogContainer: LinearLayout
    private lateinit var btnClearDataLog: Button
    private lateinit var rgTrafficScope: RadioGroup
    private lateinit var tvUsageBoot: TextView
    private lateinit var tvUsageCumulative: TextView
    private lateinit var btnEditCumulative: Button
    private lateinit var btnResetCumulative: Button
    private lateinit var btnResetUsage: Button
    private lateinit var btnStartService: Button
    private lateinit var btnStopService: Button
    private lateinit var btnShowOverlay: Button
    private lateinit var btnHideOverlay: Button
    private lateinit var btnGrantOverlay: Button
    private lateinit var btnChooseWarningMusic: Button
    private lateinit var btnBatteryWhitelist: Button
    private lateinit var btnAutostart: Button
    private lateinit var btnBackground: Button
    private lateinit var btnBgPopup: Button
    private lateinit var tvBatteryStatus: TextView
    private lateinit var tvUsageAccess: TextView
    private lateinit var tvServiceState: TextView
    private lateinit var btnMarginQuery: Button
    private lateinit var tvMarginDisplay: TextView
    private lateinit var tvMarginRefreshState: TextView
    private lateinit var btnMarginRefreshNow: Button
    private lateinit var btnMarginRefreshInterval: Button
    private lateinit var btnMarginDuration: Button
    private lateinit var btnMarginKeepAlive: Button
    /** 静默刷新用的隐藏 WebView（懒创建，离开界面时释放）。 */
    private var marginFetcher: MarginSilentFetcher? = null
    private var marginFetching = false
    // ---- 定时关闭部分已隐藏 ----
    // private lateinit var tvNoPeriods: TextView
    // private lateinit var periodsContainer: LinearLayout
    // private lateinit var btnAddPeriod: Button
    // private var periods: MutableList<TimePeriod> = mutableListOf()
    // ---- 定时关闭部分已隐藏 ----
    private val handler = Handler(Looper.getMainLooper())

    companion object {
        private const val REQ_WARNING_MUSIC = 0x1001
        /** 余量自动刷新间隔的允许范围（秒）。 */
        private const val MARGIN_REFRESH_MIN_SEC = 30
        private const val MARGIN_REFRESH_MAX_SEC = 86_400
        /** 后台保活间隔的允许范围（秒）。 */
        private const val MARGIN_KEEPALIVE_MIN_SEC = 60
        private const val MARGIN_KEEPALIVE_MAX_SEC = 86_400
        /** 悬浮窗「查询失败」点击后：自动进入余量查询页。 */
        const val EXTRA_OPEN_MARGIN_QUERY = "open_margin_query"
    }
    private var wlanSettingsObserver: ContentObserver? = null
    private val dataLogListener: () -> Unit = { handler.post { refreshDataLog() } }
    private val logTimeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val refreshRunnable = object : Runnable {
        override fun run() { refreshStatus(); handler.postDelayed(this, 3000) }
    }
    /** 余量自动刷新：只在主界面可见时跑（隐藏 WebView 依附于本 Activity）。 */
    private val marginAutoRefreshRunnable = object : Runnable {
        override fun run() {
            val sec = Prefs.getMarginRefreshIntervalSec(this@MainActivity)
            if (sec <= 0) return
            if (!marginFetching) triggerMarginRefresh(manual = false)
            handler.postDelayed(this, sec * 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvDataState = findViewById(R.id.tv_data_state)
        cardWlanAssistant = findViewById(R.id.card_wlan_assistant)
        tvWlanSectionTitle = findViewById(R.id.tv_wlan_section_title)
        tvWlanBanner = findViewById(R.id.tv_wlan_banner)
        wlanFlagsContainer = findViewById(R.id.wlan_flags_container)
        tvDataLogHint = findViewById(R.id.tv_data_log_hint)
        tvDataLogEmpty = findViewById(R.id.tv_data_log_empty)
        dataLogContainer = findViewById(R.id.data_log_container)
        btnClearDataLog = findViewById(R.id.btn_clear_data_log)
        rgTrafficScope = findViewById(R.id.rg_traffic_scope)
        tvUsageBoot = findViewById(R.id.tv_usage_boot)
        tvUsageCumulative = findViewById(R.id.tv_usage_cumulative)
        btnEditCumulative = findViewById(R.id.btn_edit_cumulative)
        btnResetCumulative = findViewById(R.id.btn_reset_cumulative)
        btnResetUsage = findViewById(R.id.btn_reset_usage)
        btnStartService = findViewById(R.id.btn_start_service)
        btnStopService = findViewById(R.id.btn_stop_service)
        btnShowOverlay = findViewById(R.id.btn_show_overlay)
        btnHideOverlay = findViewById(R.id.btn_hide_overlay)
        btnGrantOverlay = findViewById(R.id.btn_grant_overlay)
        btnBatteryWhitelist = findViewById(R.id.btn_battery_whitelist)
        btnAutostart = findViewById(R.id.btn_autostart)
        btnBackground = findViewById(R.id.btn_background)
        btnBgPopup = findViewById(R.id.btn_bg_popup)
        btnGrantOverlay = findViewById(R.id.btn_grant_overlay)
        btnChooseWarningMusic = findViewById(R.id.btn_choose_warning_music)
        tvBatteryStatus = findViewById(R.id.tv_battery_status)
        tvUsageAccess = findViewById(R.id.tv_usage_access)
        btnMarginQuery = findViewById(R.id.btn_margin_query)
        tvServiceState = findViewById(R.id.tv_service_state)
        tvMarginDisplay = findViewById(R.id.tv_margin_display)
        tvMarginRefreshState = findViewById(R.id.tv_margin_refresh_state)
        btnMarginRefreshNow = findViewById(R.id.btn_margin_refresh_now)
        btnMarginRefreshInterval = findViewById(R.id.btn_margin_refresh_interval)
        btnMarginDuration = findViewById(R.id.btn_margin_duration)
        btnMarginKeepAlive = findViewById(R.id.btn_margin_keepalive)
        tvUsageAccess.setOnClickListener {
            PermUtil.openUsageAccessSettings(this)
            toast(getString(R.string.usage_access_open_toast))
        }
        btnClearDataLog.setOnClickListener {
            DataSwitchLog.clear()
            toast(getString(R.string.data_log_cleared))
        }
        btnMarginQuery.setOnClickListener {
            try {
                startActivity(Intent(this, MarginQueryActivity::class.java))
            } catch (e: Exception) {
                toast("打开余量查询失败：${e.message}")
            }
        }
        btnMarginRefreshNow.setOnClickListener { triggerMarginRefresh(manual = true) }
        btnMarginRefreshInterval.setOnClickListener { showMarginIntervalDialog() }
        btnMarginDuration.setOnClickListener { showOverlayMarginDurationDialog() }
        btnMarginKeepAlive.setOnClickListener { showMarginKeepAliveDialog() }
        btnMarginKeepAlive.setOnLongClickListener {
            showMarginKeepAliveLog()
            true
        }
        // ---- 定时关闭部分已隐藏 ----
        // tvNoPeriods = findViewById(R.id.tv_no_periods)
        // periodsContainer = findViewById(R.id.periods_container)
        // btnAddPeriod = findViewById(R.id.btn_add_period)
        // ---- 定时关闭部分已隐藏 ----

        btnEditCumulative.setOnClickListener {
            showEditCumulativeDialog()
        }
        btnResetCumulative.setOnClickListener {
            Prefs.resetCumulativeUsage(this)
            refreshStatus()
            toast("累计流量已重置")
        }
        btnResetUsage.setOnClickListener {
            val cur = DataMonitor.bytesSinceBoot(this)
            if (cur >= 0L) Prefs.setUsageBaseline(this, cur)
            refreshStatus()
            toast("已重置流量统计")
        }
        bindTrafficScopeRadios()
        btnStartService.setOnClickListener {
            Prefs.setServiceEnabled(this, true)
            try { OverlayService.start(this); toast("已启动保活服务") } catch (e: Exception) { toast("启动失败：$e") }
            try { GuardService.start(this) } catch (_: Exception) {}
        }
        btnStopService.setOnClickListener {
            Prefs.setServiceEnabled(this, false)
            try { AlarmKeeper.cancel(this) } catch (_: Exception) {}
            sendServiceAction(OverlayService.ACTION_STOP)
            GuardService.stop(this)
            toast("已停止服务")
        }
        btnShowOverlay.setOnClickListener {
            Prefs.setServiceEnabled(this, true)
            Prefs.setOverlayHiddenByUser(this, false)
            OverlayService.start(this)
            sendServiceAction(OverlayService.ACTION_SHOW_OVERLAY)
        }
        btnHideOverlay.setOnClickListener {
            Prefs.setOverlayHiddenByUser(this, true)
            sendServiceAction(OverlayService.ACTION_HIDE_OVERLAY)
        }
        btnGrantOverlay.setOnClickListener {
            PermUtil.openOverlaySettings(this)
        }
        btnChooseWarningMusic.setOnClickListener {
            val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, getString(R.string.choose_warning_music))
                putExtra(
                    RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                    Prefs.getWarningMusicUri(this@MainActivity)?.let { Uri.parse(it) }
                        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                )
                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            }
            startActivityForResult(intent, REQ_WARNING_MUSIC)
        }
        btnBatteryWhitelist.setOnClickListener {
            PermUtil.requestIgnoreBatteryOptimizations(this)
        }
        btnAutostart.setOnClickListener {
            PermUtil.openAutoStartSettings(this)
        }
        btnBackground.setOnClickListener {
            PermUtil.openBackgroundRunSettings(this)
        }
        btnBgPopup.setOnClickListener {
            PermUtil.openBackgroundPopupSettings(this)
        }
        // ---- 定时关闭部分已隐藏 ----
        // btnAddPeriod.setOnClickListener {
        //     val p = TimePeriod(Prefs.nextPeriodId(this), 23, 0, 7, 0, enabled = true)
        //     periods.add(p)
        //     Prefs.savePeriods(this, periods)
        //     renderPeriods()
        //     ScheduleManager.rescheduleAll(this)
        //     toast("已添加时段")
        // }
        // periods = Prefs.getPeriods(this)
        // renderPeriods()
        // ---- 定时关闭部分已隐藏 ----
        requestRuntimePermissions()
        Prefs.setServiceEnabled(this, true)
        // 调度兜底保活心跳（首次打开即注册，设备重启后依然生效）
        try { KeepAliveJob.schedule(this) } catch (_: Exception) {}
        try { WatchdogJob.schedule(this) } catch (_: Exception) {}
        try { AlarmKeeper.register(this) } catch (_: Exception) {}
        try { AlarmKeeper.scheduleRolling(this) } catch (_: Exception) {}
        try { ScheduleManager.rescheduleAll(this) } catch (_: Exception) {}
        try { if (!ProcessUtil.isGuardAlive(this)) GuardService.start(this) } catch (_: Exception) {}
        // If opened from the startup-failure notification, honor the autostart-settings action
        // even when Android creates a brand-new Activity instead of calling onNewIntent().
        handleAutostartExtra(intent)
        handleMarginQueryExtra(intent)
    }

    private fun sendServiceAction(action: String) {
        val i = Intent(this, OverlayService::class.java).setAction(action)
        try { startService(i) } catch (_: Exception) {}
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null) setIntent(intent)
        handleAutostartExtra(intent)
        handleMarginQueryExtra(intent)
    }

    /** A startup-failure notification can open the vendor autostart settings directly. */
    private fun handleAutostartExtra(intent: Intent?) {
        if (intent?.getBooleanExtra(BootStartupFeedback.EXTRA_OPEN_AUTOSTART, false) == true) {
            intent.removeExtra(BootStartupFeedback.EXTRA_OPEN_AUTOSTART)
            try {
                PermUtil.openAutoStartSettings(this)
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Could not open autostart settings", e)
                toast("无法自动打开自启动设置，请手动进入系统设置")
            }
        }
    }

    /** 悬浮窗「查询失败」点击后：打开余量查询页（WebView 登录态与主界面同源）。 */
    private fun handleMarginQueryExtra(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_MARGIN_QUERY, false) == true) {
            intent.removeExtra(EXTRA_OPEN_MARGIN_QUERY)
            try {
                startActivity(Intent(this, MarginQueryActivity::class.java))
            } catch (e: Exception) {
                toast("打开余量查询失败：${e.message}")
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_WARNING_MUSIC && resultCode == Activity.RESULT_OK) {
            val uri = data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            if (uri != null) {
                try {
                    if (RingtoneManager.getRingtone(this, uri) != null) {
                        Prefs.setWarningMusicUri(this, uri.toString())
                        toast(getString(R.string.warning_music_set))
                    } else {
                        Prefs.setWarningMusicUri(this, null)
                        toast(getString(R.string.warning_music_cleared))
                    }
                } catch (_: Exception) {
                    Prefs.setWarningMusicUri(this, null)
                    toast(getString(R.string.warning_music_cleared))
                }
            } else {
                Prefs.setWarningMusicUri(this, null)
                toast(getString(R.string.warning_music_cleared))
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    override fun onResume() {
        super.onResume()
        OverlayService.isMainActivityForeground = true
        refreshStatus()
        refreshPermStatus()
        refreshMarginDisplay()
        startMarginAutoRefresh()
        // 打开 App 界面时按需激活：保活服务未运行则启动；悬浮窗未显示则显示
        val justStarted = !OverlayService.isRunning
        if (justStarted) {
            Prefs.setServiceEnabled(this, true)
            try { OverlayService.start(this) } catch (_: Exception) {}
            try { GuardService.start(this) } catch (_: Exception) {}
            toast("服务已自动恢复")
        }
        // 服务已在运行但悬浮窗未显示，且用户未主动隐藏、具备权限时，补发显示指令
        if (!justStarted && OverlayService.isRunning &&
            !OverlayService.isOverlayShown &&
            !Prefs.isOverlayHiddenByUser(this) &&
            PermUtil.canDrawOverlays(this)) {
            sendServiceAction(OverlayService.ACTION_SHOW_OVERLAY)
        }
        // 无论如何都重新注册所有保活组件，确保无遗漏
        try { KeepAliveJob.schedule(this) } catch (_: Exception) {}
        try { WatchdogJob.schedule(this) } catch (_: Exception) {}
        try { AlarmKeeper.register(this) } catch (_: Exception) {}
        try { AlarmKeeper.scheduleRolling(this) } catch (_: Exception) {}
        try { ScheduleManager.rescheduleAll(this) } catch (_: Exception) {}
        handler.post(refreshRunnable)
        WlanAssistantMonitor.unregisterObserver(this, wlanSettingsObserver)
        wlanSettingsObserver = WlanAssistantMonitor.registerObserver(this, handler) {
            handler.post { refreshWlanFlags() }
        }
        DataSwitchLog.removeListener(dataLogListener)
        DataSwitchLog.addListener(dataLogListener)
        refreshDataLog()
    }

    override fun onPause() {
        super.onPause()
        OverlayService.isMainActivityForeground = false
        handler.removeCallbacks(refreshRunnable)
        stopMarginAutoRefresh()
        WlanAssistantMonitor.unregisterObserver(this, wlanSettingsObserver)
        wlanSettingsObserver = null
        DataSwitchLog.removeListener(dataLogListener)
    }

    override fun onDestroy() {
        stopMarginAutoRefresh()
        marginFetcher?.destroy()
        marginFetcher = null
        super.onDestroy()
    }

    // ---------- 状态刷新 ----------
    private fun refreshStatus() {
        val on = try { DataMonitor.isMobileDataEnabled(this) } catch (_: Exception) { false }
        DataSwitchLog.note(this, on)
        tvDataState.text = getString(
            if (on) R.string.data_state_on else R.string.data_state_off
        )
        tvDataState.setTextColor(
            getColor(if (on) R.color.danger else R.color.accent)
        )
        DataMonitor.autoUpdateCumulative(this)
        val scope = Prefs.getTrafficScope(this)
        val boot = DataMonitor.bytesSinceBoot(this, scope).coerceAtLeast(0L)
        tvUsageBoot.text = getString(
            R.string.usage_since_boot_fmt,
            scopeLabel(scope),
            DataMonitor.formatBytes(boot)
        )
        tvUsageCumulative.text = getString(
            R.string.usage_cumulative_fmt,
            DataMonitor.formatBytes(Prefs.getCumulativeUsage(this))
        )
        refreshWlanFlags()
        refreshDataLog()
        refreshServiceState()
    }

    /** 刷新余量显示：从 Prefs 读上次 20085 解析结果。 */
    private fun refreshMarginDisplay() {
        updateMarginRefreshState()
        updateMarginKeepAliveButton()
        val raw = Prefs.getMarginRaw(this)
        if (raw.isNullOrBlank()) {
            // 从未收到过 20085 明文
            tvMarginDisplay.text = getString(R.string.margin_not_fetched)
            return
        }
        if (Prefs.isMarginStale(this)) {
            // 登录失效/接口失败：旧数据仍在但已不可信
            tvMarginDisplay.text = getString(R.string.margin_stale)
            return
        }
        val unit = Prefs.getMarginUnit(this)
        val used = Prefs.getMarginUsed(this)?.let { withMarginUnit(it, unit) }
        val remain = Prefs.getMarginRemain(this)?.let { withMarginUnit(it, unit) }
        if (used.isNullOrBlank() || remain.isNullOrBlank()) {
            // 收到明文但候选路径未命中，需第二轮精修解析
            tvMarginDisplay.text = getString(R.string.margin_fetch_pending)
            return
        }
        val time = Prefs.getMarginUpdateTime(this)
        val date = if (time > 0L) java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(time)) else null
        tvMarginDisplay.text = buildString {
            append(getString(R.string.margin_used_label, used))
            append("  ")
            append(getString(R.string.margin_remain_label, remain))
            if (date != null) append("  ·  $date")
        }
    }

    /** 数值本身已带单位（如 438.25MB）时不重复追加。 */
    private fun withMarginUnit(value: String, unit: String?): String {
        if (unit.isNullOrBlank() || value.isBlank()) return value
        return if (value.any { it.isLetter() }) value else value + unit
    }

    // ---------- 余量刷新（定时 + 手动） ----------

    /** 按配置的间隔调度余量自动刷新：数据过期就立刻拉一次，否则等剩余时间。 */
    private fun startMarginAutoRefresh() {
        stopMarginAutoRefresh()
        updateMarginRefreshState()
        val sec = Prefs.getMarginRefreshIntervalSec(this)
        if (sec <= 0) return
        val intervalMs = sec * 1000L
        val last = Prefs.getMarginUpdateTime(this)
        val age = System.currentTimeMillis() - last
        val delay = if (last <= 0L || age >= intervalMs) 0L else intervalMs - age
        handler.postDelayed(marginAutoRefreshRunnable, delay)
    }

    private fun stopMarginAutoRefresh() {
        handler.removeCallbacks(marginAutoRefreshRunnable)
    }

    /** 静默刷新一次余量（隐藏 WebView 走页面自己的 20085 请求）。 */
    private fun triggerMarginRefresh(manual: Boolean) {
        if (marginFetching) {
            if (manual) toast(getString(R.string.margin_refresh_busy))
            return
        }
        marginFetching = true
        updateMarginRefreshState()
        val fetcher = marginFetcher ?: MarginSilentFetcher(this, findViewById<ViewGroup>(android.R.id.content)).also { marginFetcher = it }
        fetcher.start(Prefs.getWebStorage(this)) { outcome ->
            marginFetching = false
            if (isFinishing || isDestroyed) return@start
            when {
                outcome == null -> {
                    // 超时/未加载完：不改动已有数据，只提示
                    if (manual) toast(getString(R.string.margin_refresh_timeout))
                }
                !outcome.success -> {
                    Prefs.setMarginStale(this, true)
                    refreshMarginDisplay()
                    if (manual) toast(getString(R.string.margin_refresh_failed))
                }
                else -> {
                    val r = outcome.result
                    Prefs.saveMargin(this, r?.used, r?.remain, r?.unit, outcome.raw)
                    Prefs.setMarginStale(this, false)
                    refreshMarginDisplay()
                    if (manual) {
                        toast(
                            if (r != null) getString(R.string.margin_refresh_ok)
                            else getString(R.string.margin_refresh_pending)
                        )
                    }
                }
            }
            updateMarginRefreshState()
        }
    }

    private fun updateMarginRefreshState() {
        if (!::tvMarginRefreshState.isInitialized) return
        val sec = Prefs.getMarginRefreshIntervalSec(this)
        val base = if (sec <= 0) {
            getString(R.string.margin_refresh_state_off)
        } else {
            getString(R.string.margin_refresh_state_fmt, sec)
        }
        tvMarginRefreshState.text = if (marginFetching) {
            base + " · " + getString(R.string.margin_refreshing_suffix)
        } else {
            base
        }
    }

    /** 设置自动刷新间隔：单位秒，0 = 关闭。 */
    private fun showMarginIntervalDialog() {
        val current = Prefs.getMarginRefreshIntervalSec(this)
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(if (current > 0) current.toString() else "0")
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.margin_refresh_interval)
            .setMessage(getString(R.string.margin_refresh_interval_msg, MARGIN_REFRESH_MIN_SEC))
            .setView(input)
            .setPositiveButton("确认") { _, _ ->
                var sec = input.text.toString().trim().toIntOrNull() ?: 0
                if (sec < 0) sec = 0
                if (sec in 1 until MARGIN_REFRESH_MIN_SEC) {
                    sec = MARGIN_REFRESH_MIN_SEC
                    toast(getString(R.string.margin_refresh_min_fmt, MARGIN_REFRESH_MIN_SEC))
                }
                if (sec > MARGIN_REFRESH_MAX_SEC) sec = MARGIN_REFRESH_MAX_SEC
                Prefs.setMarginRefreshIntervalSec(this, sec)
                startMarginAutoRefresh()
                toast(
                    if (sec > 0) getString(R.string.margin_refresh_on_fmt, sec)
                    else getString(R.string.margin_refresh_off_toast)
                )
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 设置后台保活间隔：单位秒，0 = 关闭。变更后通知常驻服务立即重排。 */
    private fun showMarginKeepAliveDialog() {
        val current = Prefs.getMarginKeepAliveSec(this)
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(current.toString())
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.margin_keepalive_title)
            .setMessage(getString(R.string.margin_keepalive_msg, MARGIN_KEEPALIVE_MIN_SEC, MARGIN_KEEPALIVE_MAX_SEC))
            .setView(input)
            .setPositiveButton("确认") { _, _ ->
                var sec = input.text.toString().trim().toIntOrNull() ?: 0
                if (sec < 0) sec = 0
                if (sec in 1 until MARGIN_KEEPALIVE_MIN_SEC) {
                    sec = MARGIN_KEEPALIVE_MIN_SEC
                    toast(getString(R.string.margin_keepalive_min_fmt, MARGIN_KEEPALIVE_MIN_SEC))
                }
                if (sec > MARGIN_KEEPALIVE_MAX_SEC) sec = MARGIN_KEEPALIVE_MAX_SEC
                Prefs.setMarginKeepAliveSec(this, sec)
                updateMarginKeepAliveButton()
                sendServiceAction(OverlayService.ACTION_KEEPALIVE_CHANGED)
                toast(
                    if (sec > 0) getString(R.string.margin_keepalive_on_fmt, sec)
                    else getString(R.string.margin_keepalive_off_toast)
                )
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 长按「保活间隔」：查看 margin.log 最近的保活记录（只读展示，不提供清空）。 */
    private fun showMarginKeepAliveLog() {
        val text = try {
            val f = java.io.File(filesDir, "logs/margin.log")
            if (!f.exists() || f.length() == 0L) {
                "（暂无保活记录）"
            } else {
                val lines = f.readLines()
                val tail = if (lines.size > 200) lines.subList(lines.size - 200, lines.size) else lines
                tail.joinToString("\n")
            }
        } catch (e: Exception) {
            Log.w("MainActivity", "读取保活日志失败", e)
            "（读日志失败：${e.message}）"
        }
        val tv = TextView(this).apply {
            this.text = text
            textSize = 10f
            setTextIsSelectable(true)
            val pad = (12 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val scroll = android.widget.ScrollView(this).apply { addView(tv) }
        AlertDialog.Builder(this)
            .setTitle(R.string.margin_keepalive_log_title)
            .setView(scroll)
            .setPositiveButton("关闭", null)
            .show()
    }

    private fun updateMarginKeepAliveButton() {
        if (!::btnMarginKeepAlive.isInitialized) return
        val sec = Prefs.getMarginKeepAliveSec(this)
        btnMarginKeepAlive.text = if (sec > 0) {
            getString(R.string.margin_keepalive_btn_fmt, sec)
        } else {
            getString(R.string.margin_keepalive_btn_off)
        }
    }

    /** 设置悬浮窗「查询流量」显示时长：单位秒，0 = 一直显示。 */
    private fun showOverlayMarginDurationDialog() {
        val current = Prefs.getOverlayMarginShowSec(this)
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(current.toString())
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.overlay_margin_duration_title)
            .setMessage(getString(R.string.overlay_margin_duration_msg, 5, 3600))
            .setView(input)
            .setPositiveButton("确认") { _, _ ->
                var sec = input.text.toString().trim().toIntOrNull() ?: 0
                if (sec < 0) sec = 0
                if (sec in 1 until 5) {
                    sec = 5
                    toast(getString(R.string.overlay_margin_duration_min_fmt, 5))
                }
                if (sec > 3600) sec = 3600
                Prefs.setOverlayMarginShowSec(this, sec)
                toast(
                    if (sec > 0) getString(R.string.overlay_margin_duration_set_fmt, sec)
                    else getString(R.string.overlay_margin_duration_forever)
                )
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 保活服务状态行：开关 + 两个进程的实际存活情况。
     * 直接暴露“开机能否自启”的关键状态，避免服务被开关缺失/进程死亡悄停时用户无从判断。
     */
    private fun refreshServiceState() {
        val enabled = Prefs.isServiceEnabled(this)
        val guardAlive = try { ProcessUtil.isGuardAlive(this) } catch (_: Exception) { false }
        tvServiceState.text = when {
            !enabled -> getString(R.string.service_state_off)
            OverlayService.isRunning && guardAlive -> getString(R.string.service_state_on_full)
            OverlayService.isRunning -> getString(R.string.service_state_on_overlay_only)
            else -> getString(R.string.service_state_on_not_running)
        }
        tvServiceState.setTextColor(getColor(if (enabled) R.color.accent else R.color.danger))
    }

    private fun refreshDataLog() {
        val list = DataSwitchLog.snapshot()
        tvDataLogEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        dataLogContainer.removeAllViews()
        val inflater = LayoutInflater.from(this)
        for (entry in list) {
            val row = inflater.inflate(R.layout.item_data_switch_log, dataLogContainer, false)
            val tvTime = row.findViewById<TextView>(R.id.tv_log_time)
            val tvApp = row.findViewById<TextView>(R.id.tv_log_app)
            val action = getString(if (entry.enabled) R.string.data_log_opened else R.string.data_log_closed)
            tvTime.text = getString(
                R.string.data_log_line_fmt,
                logTimeFormat.format(Date(entry.timeMillis)),
                action
            )
            tvTime.setTextColor(getColor(if (entry.enabled) R.color.danger else R.color.accent))
            tvApp.text = if (entry.packageName.isEmpty()) {
                entry.appLabel
            } else {
                entry.appLabel + "\n" + entry.packageName
            }
            dataLogContainer.addView(row)
        }
    }

    private fun refreshWlanFlags() {
        val flags = WlanAssistantMonitor.flags(this)
        val autoMobileOn = flags.any { it.causesDataToggle && it.enabled }
        val warnColor = getColor(if (autoMobileOn) R.color.danger else R.color.accent)
        tvWlanBanner.text = getString(if (autoMobileOn) R.string.wlan_banner_on else R.string.wlan_banner_off)
        tvWlanBanner.setTextColor(warnColor)
        tvWlanSectionTitle.setTextColor(warnColor)
        cardWlanAssistant.setBackgroundColor(
            getColor(if (autoMobileOn) R.color.warning_bg else R.color.ok_bg)
        )
        wlanFlagsContainer.removeAllViews()
        val inflater = LayoutInflater.from(this)
        for (flag in flags) {
            val row = inflater.inflate(R.layout.item_wlan_flag, wlanFlagsContainer, false)
            val title = row.findViewById<TextView>(R.id.tv_wlan_flag_title)
            val detail = row.findViewById<TextView>(R.id.tv_wlan_flag_detail)
            val btn = row.findViewById<Button>(R.id.btn_wlan_flag_close)
            val stateRes = if (flag.enabled) R.string.wlan_flag_state_on else R.string.wlan_flag_state_off
            title.text = getString(R.string.wlan_flag_title_fmt, getString(flag.titleRes), getString(stateRes))
            title.setTextColor(
                getColor(
                    when {
                        flag.causesDataToggle && flag.enabled -> R.color.danger
                        flag.enabled -> R.color.text_primary
                        else -> R.color.text_secondary
                    }
                )
            )
            detail.text = getString(flag.detailRes)
            if (flag.enabled) {
                btn.visibility = View.VISIBLE
                btn.setOnClickListener {
                    try {
                        WlanAssistantMonitor.openClosePage(this, flag.closeAction)
                    } catch (_: Exception) {
                        toast(getString(R.string.wlan_open_page_failed))
                    }
                }
            } else {
                btn.visibility = View.GONE
            }
            wlanFlagsContainer.addView(row)
        }
    }

    private fun bindTrafficScopeRadios() {
        val checkedId = when (Prefs.getTrafficScope(this)) {
            TrafficScope.WIFI -> R.id.rb_scope_wifi
            TrafficScope.ALL -> R.id.rb_scope_all
            TrafficScope.MOBILE -> R.id.rb_scope_mobile
        }
        rgTrafficScope.check(checkedId)
        rgTrafficScope.setOnCheckedChangeListener { _, id ->
            val scope = when (id) {
                R.id.rb_scope_wifi -> TrafficScope.WIFI
                R.id.rb_scope_all -> TrafficScope.ALL
                else -> TrafficScope.MOBILE
            }
            DataMonitor.switchTrafficScope(this, scope)
            refreshStatus()
            sendServiceAction(OverlayService.ACTION_REFRESH_OVERLAY)
        }
    }

    private fun scopeLabel(scope: TrafficScope): String = when (scope) {
        TrafficScope.MOBILE -> getString(R.string.traffic_scope_mobile)
        TrafficScope.WIFI -> getString(R.string.traffic_scope_wifi)
        TrafficScope.ALL -> getString(R.string.traffic_scope_all)
    }

    private fun refreshPermStatus() {
        btnGrantOverlay.visibility =
            if (PermUtil.canDrawOverlays(this)) View.GONE else View.VISIBLE
        val batteryOk = PermUtil.isIgnoringBatteryOptimizations(this)
        tvBatteryStatus.text = "电池优化白名单：" + if (batteryOk) "已加入" else "未加入"
        val alarmOk = PermUtil.canScheduleExactAlarms(this)
        if (!alarmOk) {
            tvBatteryStatus.append("  |  精确闹钟：未授权")
            tvBatteryStatus.setOnClickListener { PermUtil.openExactAlarmSettings(this) }
        }
        // ColorOS 后台弹出界面权限检查
        val bgPopupOk = PermUtil.canBackgroundPopup(this)
        if (!bgPopupOk) {
            tvBatteryStatus.append("  |  后台弹出界面：未授权")
            tvBatteryStatus.setOnClickListener { PermUtil.openBackgroundPopupSettings(this) }
        }
        // 使用情况访问权限状态（AppOps GET_USAGE_STATS）
        val usageAccessOk = PermUtil.hasUsageAccess(this)
        tvUsageAccess.text = getString(
            if (usageAccessOk) R.string.usage_access_granted else R.string.usage_access_denied
        )
        tvUsageAccess.setTextColor(
            getColor(if (usageAccessOk) R.color.accent else R.color.danger)
        )
    }

    // ---------- 权限 ----------
    private fun requestRuntimePermissions() {
        val need = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.READ_PHONE_STATE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (need.isNotEmpty()) {
            requestPermissions(need.toTypedArray(), 100)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshStatus()
        if (grantResults.isNotEmpty() && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
            toast("部分权限未授予，相关功能可能受限")
        }
    }

    // ---------- 时段列表（已隐藏）----------
    // private fun renderPeriods() {
    //     periodsContainer.removeAllViews()
    //     tvNoPeriods.visibility = if (periods.isEmpty()) View.VISIBLE else View.GONE
    //     for (p in periods) {
    //         addPeriodRow(p)
    //     }
    // }
    // private fun addPeriodRow(p: TimePeriod) {
    //     val row = LayoutInflater.from(this).inflate(R.layout.item_time_period, periodsContainer, false)
    //     val tvStart = row.findViewById<TextView>(R.id.tv_start)
    //     val tvEnd = row.findViewById<TextView>(R.id.tv_end)
    //     val sw = row.findViewById<Switch>(R.id.sw_enabled)
    //     val btnDel = row.findViewById<Button>(R.id.btn_delete)
    //     tvStart.text = "开始 " + p.startLabel()
    //     tvEnd.text = "结束 " + p.endLabel()
    //     sw.isChecked = p.enabled
    //     tvStart.setOnClickListener {
    //         show24hTimePicker(p.startHour, p.startMin) { h, m ->
    //             p.startHour = h; p.startMin = m
    //             tvStart.text = "开始 " + p.startLabel()
    //             Prefs.savePeriods(this, periods)
    //             ScheduleManager.rescheduleAll(this)
    //         }
    //     }
    //     tvEnd.setOnClickListener {
    //         show24hTimePicker(p.endHour, p.endMin) { h, m ->
    //             p.endHour = h; p.endMin = m
    //             tvEnd.text = "结束 " + p.endLabel()
    //             Prefs.savePeriods(this, periods)
    //             ScheduleManager.rescheduleAll(this)
    //         }
    //     }
    //     sw.setOnCheckedChangeListener { _, isChecked ->
    //         p.enabled = isChecked
    //         Prefs.savePeriods(this, periods)
    //         ScheduleManager.rescheduleAll(this)
    //     }
    //     btnDel.setOnClickListener {
    //         periods.remove(p)
    //         Prefs.savePeriods(this, periods)
    //         renderPeriods()
    //         ScheduleManager.rescheduleAll(this)
    //     }
    //     periodsContainer.addView(row)
    // }
    // /** 24 小时制时间选择器（is24HourView=true 强制 24h）。 */
    // private fun show24hTimePicker(hour: Int, minute: Int, onSet: (Int, Int) -> Unit) {
    //     TimePickerDialog(this, { _, h, m -> onSet(h, m) }, hour, minute, true).show()
    // }

    private fun showEditCumulativeDialog() {
        val current = DataMonitor.formatBytes(Prefs.getCumulativeUsage(this))
        val input = EditText(this).apply {
            hint = getString(R.string.edit_cumulative_hint)
            setRawInputType(android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
            setText(current)
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle("修改累计流量")
            .setMessage("当前累计：$current\n支持输入：纯数字=字节，或带 m/M、k/K、g/G（支持小数）")
            .setView(input)
            .setPositiveButton("确认") { _, _ ->
                val text = input.text.toString().trim()
                val bytes = DataMonitor.parseCumulativeInput(text)
                if (bytes < 0) {
                    toast("输入不合法，请检查单位")
                } else {
                    Prefs.setCumulativeUsage(this, bytes)
                    refreshStatus()
                    toast("累计流量已更新：" + DataMonitor.formatBytes(bytes))
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}

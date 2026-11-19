package com.example.mobiledatanotifier

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.graphics.PixelFormat
import android.media.AudioAttributes
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import java.util.concurrent.Executor
import android.telephony.PhoneStateListener
import android.telephony.ServiceState
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.media.AudioManager
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.SeekBar
import android.widget.TextView
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 保活前台服务 + 顶部悬浮窗。
 * - 前台服务通知走低优先级渠道（静音、不震动）。
 * - 悬浮窗显示移动数据开关状态、已用流量，含"关闭流量"按钮（打开 OPPO SIM 设置页）。
 * - START_STICKY：被杀后系统尝试重建。
 */
class OverlayService : Service() {

    companion object {
        const val CH_FG = "ch_fg"
        const val CH_REMINDER = "ch_reminder"
        const val ACTION_SHOW_OVERLAY = "com.example.mobiledatanotifier.SHOW_OVERLAY"
        const val ACTION_HIDE_OVERLAY = "com.example.mobiledatanotifier.HIDE_OVERLAY"
        const val ACTION_REFRESH_OVERLAY = "com.example.mobiledatanotifier.REFRESH_OVERLAY"
        const val ACTION_STOP = "com.example.mobiledatanotifier.STOP"
        const val ACTION_KEEPALIVE_CHANGED = "com.example.mobiledatanotifier.KEEPALIVE_CHANGED"
        private const val MOBILE_DATA_FAST_POLL_MS = 500L

        /** 保活服务是否在运行（供 MainActivity / KeepAliveJob 按需判断） */
        @Volatile
        var isRunning: Boolean = false
            private set

        /** 悬浮窗是否在显示（供 MainActivity 按需判断） */
        @Volatile
        var isOverlayShown: Boolean = false
            private set

        /** 主界面前台状态（仅用于诊断日志；不控制告警音播放） */
        @Volatile
        var isMainActivityForeground = false

        fun start(ctx: Context) {
            val i = Intent(ctx, OverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        fun startIfEnabled(ctx: Context) {
            if (!Prefs.isServiceEnabled(ctx)) return
            start(ctx)
        }

        /**
         * 打开 OPPO SIM/双卡设置（可关移动数据）。
         * 第三方通常无法直接启动 OplusSimSettingsActivity（resolve 成功但 start 会抛），
         * 因此优先返回系统公开的 NETWORK_OPERATOR_SETTINGS / GEMINI_MANAGEMENT。
         */
        fun createMobileDataSettingsIntent(ctx: Context): Intent {
            val pm = ctx.packageManager
            for (intent in simSettingsIntents(includePrivilegedExplicit = false)) {
                if (pm.resolveActivity(intent, 0) != null) return intent
            }
            return Intent(Settings.ACTION_DATA_USAGE_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        /** ColorOS 设置首页进入 SIM 页时用的 identifier。 */
        private const val OPLUS_FROM_SETTINGS = "oplus.intent.category.START_FROM_SETTINGS_MAIN_PAGE"

        private fun simSettingsIntents(includePrivilegedExplicit: Boolean): List<Intent> {
            val flags = Intent.FLAG_ACTIVITY_NEW_TASK
            val list = mutableListOf<Intent>()
            if (includePrivilegedExplicit) {
                val explicit = Intent(Intent.ACTION_MAIN)
                    .setClassName(
                        "com.android.phone",
                        "com.android.simsettings.activity.OplusSimSettingsActivity"
                    )
                    .addCategory(Intent.CATEGORY_DEFAULT)
                    .addFlags(flags)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    explicit.identifier = OPLUS_FROM_SETTINGS
                }
                list.add(explicit)
            }
            list.add(
                Intent(Settings.ACTION_NETWORK_OPERATOR_SETTINGS)
                    .addCategory(Intent.CATEGORY_DEFAULT)
                    .addFlags(flags)
            )
            list.add(
                Intent("android.settings.MANAGE_ALL_SIM_PROFILES_SETTINGS")
                    .addCategory(Intent.CATEGORY_DEFAULT)
                    .addFlags(flags)
            )
            list.add(
                Intent("android.settings.GEMINI_MANAGEMENT")
                    .addCategory(Intent.CATEGORY_DEFAULT)
                    .addFlags(flags)
            )
            list.add(
                Intent("com.android.settings.MULTI_SIM_SETTINGS")
                    .addCategory(Intent.CATEGORY_DEFAULT)
                    .addFlags(flags)
            )
            return list
        }
    }

    private lateinit var wm: WindowManager
    @Volatile private var overlayView: View? = null
    private lateinit var handler: Handler
    private var connectivityCallback: ConnectivityManager.NetworkCallback? = null
    private var phoneStateListener: PhoneStateListener? = null
    private var dataEnabledCallback: TelephonyCallback? = null
    private var mobileDataObserver: ContentObserver? = null
    @Volatile private var lastMobileDataState: Boolean? = null
    @Volatile private var lastWlanAssistantOn: Boolean? = null
    private var mobileDataMonitorThread: HandlerThread? = null
    private var mobileDataMonitorHandler: Handler? = null
    // Run foreground-app attribution and traffic accounting off the UI thread.
    private val overlayStatsExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "OverlayStatsWorker").apply { isDaemon = true }
    }
    private val overlayStatsPending = AtomicBoolean(false)
    private val overlayStatsNeedsRefresh = AtomicBoolean(false)
    private var colorFlashing = false
    private var flashOnRed = true
    /** 用户手动在浮窗菜单点了“停止警告”后，本轮数据开启期间不再播放提醒音。 */
    @Volatile private var userMutedWarning = false
    /**
     * Audio playback has its own HandlerThread. The sound is looped by MediaPlayer itself
     * and is started/stopped only when the flashing state changes (not on every refresh).
     */
    private val warningAudioPlayer by lazy(LazyThreadSafetyMode.NONE) {
        LoopingWarningAudioPlayer(applicationContext)
    }
    private val flashRunnable = object : Runnable {
        override fun run() {
            val tv = overlayView?.findViewById<TextView>(R.id.tv_state)
            if (tv == null) {
                colorFlashing = false
                stopSoundLoop()
                return
            }
            flashOnRed = !flashOnRed
            tv.setTextColor(if (flashOnRed) 0xFFFF0000.toInt() else 0xFF00FF00.toInt())
            tv.invalidate()
            handler.postDelayed(this, 400L)
        }
    }
    // 动态注册的系统事件接收器（SCREEN_ON/OFF、USER_PRESENT、TIME_TICK 无法 Manifest 静态注册）
    private var systemEventReceiver: BroadcastReceiver? = null
    private var wlanSettingsObserver: ContentObserver? = null
    // 用户是否主动隐藏了悬浮窗；为 false 时开机/被杀重建后可自愈重新挂载
    @Volatile private var overlayHiddenByUser = false
    // 音量调节悬浮窗
    private var volumeOverlayView: View? = null
    // 悬浮窗「查询流量」：显示中标记 + 恢复计时器 + 静默刷新用临时窗口/WebView
    @Volatile private var marginOverlayShowing = false
    private var marginFetchContainer: View? = null
    private var marginFetcher: MarginSilentFetcher? = null
    private val marginOverlayRestoreRunnable = Runnable { onMarginRestoreDue() }
    /** 悬浮窗余量查询失败：显示简短失败信息，点击可打开主界面并进入余量查询页。 */
    @Volatile private var marginOverlayFailed = false
    /** 长按菜单弹出中：抬起时不响应失败信息点击，避免菜单与跳转同时触发。 */
    @Volatile private var popupMenuActive = false
    // 余量会话保活 + 自动重登（后台定时循环）
    private var sessionKeeper: MarginSessionKeeper? = null

    override fun onCreate() {
        super.onCreate()
        Log.i("OverlayService", "onCreate begin uptimeMs=${SystemClock.elapsedRealtime()} enabled=${Prefs.isServiceEnabled(this)}")
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        handler = Handler(Looper.getMainLooper())
        createChannels()
        startForeground(1, buildFgNotification())
        Log.i("OverlayService", "Foreground service established uptimeMs=${SystemClock.elapsedRealtime()}")
        registerConnectivity()
        registerPhoneState()
        registerDataEnabledListener()
        registerWlanObserver()
        overlayHiddenByUser = Prefs.isOverlayHiddenByUser(this)
        if (!overlayHiddenByUser) addOverlay()
        registerSystemEvents()
        isRunning = true
        // Give the overlay at least one render opportunity before the first periodic refresh.
        handler.postDelayed(updateRunnable, 3_000L)
        startMobileDataFastPoll()
        // Startup feedback is non-critical; it must never abort service initialization.
        try {
            BootStartupFeedback.onServiceStarted(this)
        } catch (e: Exception) {
            Log.w("OverlayService", "Startup feedback failed; continuing service initialization", e)
        }
        Log.i("OverlayService", "Initialization complete uptimeMs=${SystemClock.elapsedRealtime()} overlayShown=$isOverlayShown")
        // 调度兜底心跳：被系统杀后由 JobScheduler 拉起
        try { KeepAliveJob.schedule(this) } catch (_: Exception) {}
        sessionKeeper = MarginSessionKeeper(this) { marginFetcher != null }.also { it.start() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("OverlayService", "onStartCommand action=${intent?.action} startId=$startId uptimeMs=${SystemClock.elapsedRealtime()}")
        when (intent?.action) {
            ACTION_HIDE_OVERLAY -> {
                overlayHiddenByUser = true
                Prefs.setOverlayHiddenByUser(this, true)
                removeOverlay()
            }
            ACTION_SHOW_OVERLAY -> {
                overlayHiddenByUser = false
                Prefs.setOverlayHiddenByUser(this, false)
                addOverlay()
            }
            ACTION_REFRESH_OVERLAY -> handler.post { refreshOverlay() }
            ACTION_KEEPALIVE_CHANGED -> sessionKeeper?.reschedule()
            ACTION_STOP -> {
                Prefs.setServiceEnabled(this, false)
                try { AlarmKeeper.cancel(this) } catch (_: Exception) {}
                shutdown()
                try { GuardService.stop(this) } catch (_: Exception) {}
                return START_NOT_STICKY
            }
        }
        // 无论以何种方式被拉起，都重新确保所有保活组件生效
        ensureKeepAlive()
        return START_STICKY
    }

    /** 确保所有保活组件已注册。失败时通过 AlarmKeeper 兜底。用户主动停止后不再拉起。 */
    private fun ensureKeepAlive() {
        if (!Prefs.isServiceEnabled(this)) return
        try { KeepAliveJob.schedule(this) } catch (_: Exception) {}
        try { WatchdogJob.schedule(this) } catch (_: Exception) {}
        try { AlarmKeeper.register(this) } catch (_: Exception) {}
        try { AlarmKeeper.scheduleRolling(this) } catch (_: Exception) {}
        try { ScheduleManager.rescheduleAll(this) } catch (_: Exception) {}
        try { if (!ProcessUtil.isGuardAlive(this)) GuardService.start(this) } catch (_: Exception) {}
    }

    private fun shutdown() {
        isRunning = false
        stopMobileDataFastPoll()
        unregisterSystemEvents()
        unregisterPhoneState()
        unregisterDataEnabledListener()
        unregisterWlanObserver()
        handler.removeCallbacks(updateRunnable)
        handler.removeCallbacks(mobileDataFastPollRunnable)
        handler.removeCallbacks(flashRunnable)
        colorFlashing = false
        stopSoundLoop()
        unregisterConnectivity()
        removeOverlay()
        sessionKeeper?.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        isRunning = false
        stopSelf()
    }

    override fun onDestroy() {
        isRunning = false
        stopMobileDataFastPoll()
        unregisterSystemEvents()
        unregisterPhoneState()
        unregisterDataEnabledListener()
        unregisterWlanObserver()
        handler.removeCallbacks(updateRunnable)
        handler.removeCallbacks(mobileDataFastPollRunnable)
        handler.removeCallbacks(flashRunnable)
        colorFlashing = false
        stopSoundLoop()
        unregisterConnectivity()
        removeOverlay()
        sessionKeeper?.stop()
        overlayStatsExecutor.shutdownNow()
        isRunning = false
        scheduleRestartIfEnabled()
        super.onDestroy()
    }

    /**
     * 任务被从最近列表划掉时：直接 start 自己会随进程一起消亡，无效。
     * 改为通过 AlarmManager 安排 1 秒后延迟重启（闹钟由系统持有），并调度一次性 Job。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        scheduleRestartIfEnabled()
        try { if (Prefs.isServiceEnabled(this) && !ProcessUtil.isGuardAlive(this)) GuardService.start(this) } catch (_: Exception) {}
        super.onTaskRemoved(rootIntent)
    }

    private fun scheduleRestartIfEnabled() {
        if (!Prefs.isServiceEnabled(this)) return
        try { AlarmKeeper.scheduleRestart(this, 1000L) } catch (_: Exception) {}
        try { AlarmKeeper.scheduleRolling(this) } catch (_: Exception) {}
        try { WatchdogJob.scheduleImmediate(this) } catch (_: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------- 通知渠道 ----------
    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val fg = NotificationChannel(CH_FG, getString(R.string.channel_fg_name),
            NotificationManager.IMPORTANCE_LOW).apply {
            description = getString(R.string.channel_fg_desc)
            setShowBadge(false)
        }
        nm.createNotificationChannel(fg)

        // 高重要性提醒渠道：带声音 + 震动
        val reminder = NotificationChannel(CH_REMINDER, getString(R.string.channel_reminder_name),
            NotificationManager.IMPORTANCE_HIGH).apply {
            description = getString(R.string.channel_reminder_desc)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 300, 200, 300)
            setSound(
                Settings.System.DEFAULT_NOTIFICATION_URI,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannel(reminder)
    }

    private fun buildFgNotification(): Notification {
        val showIntent = Intent(this, OverlayService::class.java).setAction(ACTION_SHOW_OVERLAY)
        val showPi = PendingIntent.getService(this, 0, showIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val mainIntent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val mainPi = PendingIntent.getActivity(this, 0, mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CH_FG)
            .setContentTitle(getString(R.string.fg_notification_title))
            .setContentText(getString(R.string.fg_notification_text))
            .setSmallIcon(R.drawable.ic_notify)
            .setOngoing(true)
            .setContentIntent(mainPi)
            .addAction(0, getString(R.string.action_show_overlay), showPi)
            .build()
    }

    // ---------- 悬浮窗 ----------
    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private var overlayRetryCount = 0
    private val MAX_OVERLAY_RETRY = 10

    private fun addOverlay() {
        overlayHiddenByUser = false
        if (overlayView != null) return
        if (!PermUtil.canDrawOverlays(this)) {
            Log.w("OverlayService", "addOverlay: cannot draw overlays, permission denied")
            return
        }
        val v = LayoutInflater.from(this).inflate(R.layout.view_overlay, null, false)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = Prefs.getOverlayPosX(this)
        params.y = Prefs.getOverlayPosY(this)
        // clamp so the window never lands off-screen after a size/rotation change
        val dm = resources.displayMetrics
        val maxX = maxOf(0, dm.widthPixels - (v.width.takeIf { it > 0 } ?: dm.widthPixels / 4))
        val maxY = maxOf(0, dm.heightPixels - (v.height.takeIf { it > 0 } ?: dm.heightPixels / 4))
        params.x = params.x.coerceIn(0, maxX)
        params.y = params.y.coerceIn(0, maxY)
        enableDrag(v, params)
        try {
            wm.addView(v, params)
            overlayView = v
            isOverlayShown = true
            overlayRetryCount = 0
            // Populate the visible state immediately; statistics/foreground attribution are async.
            val initialMobileOn = try { DataMonitor.isMobileDataEnabled(this) } catch (_: Exception) { false }
            val initialAssistantOn = try { WlanAssistantMonitor.shouldFlashOverlay(this) } catch (_: Exception) { false }
            lastMobileDataState = initialMobileOn
            lastWlanAssistantOn = initialAssistantOn
            updateOverlayState(initialMobileOn, initialAssistantOn)
            Log.i("OverlayService", "addOverlay: overlay added successfully")
            requestOverlayDetailsRefresh(initialMobileOn)
        } catch (e: Exception) {
            Log.e("OverlayService", "addOverlay failed: ${e.javaClass.simpleName}: ${e.message}")
            // 重试机制：如果添加失败，延迟后重试
            if (overlayRetryCount < MAX_OVERLAY_RETRY) {
                overlayRetryCount++
                Log.i("OverlayService", "addOverlay: retrying ($overlayRetryCount/$MAX_OVERLAY_RETRY)...")
                handler.postDelayed({
                    if (overlayView == null && !overlayHiddenByUser) {
                        addOverlay()
                    }
                }, 2000L)
            } else {
                Log.e("OverlayService", "addOverlay: max retries reached, giving up")
            }
        }
    }

    private fun removeOverlay() {
        handler.removeCallbacks(flashRunnable)
        handler.removeCallbacks(marginOverlayRestoreRunnable)
        marginOverlayShowing = false
        marginOverlayFailed = false
        cancelMarginFetch()
        colorFlashing = false
        stopSoundLoop()
        removeVolumeOverlay()
        overlayView?.let {
            try { wm.removeView(it) } catch (_: Exception) {}
        }
        overlayView = null
        isOverlayShown = false
    }

    private fun enableDrag(v: View, params: WindowManager.LayoutParams) {
        var initX = 0; var initY = 0; var touchX = 0f; var touchY = 0f; var moved = false
        val longPressRunnable = Runnable { showPopupMenu(v) }
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = params.x; initY = params.y
                    touchX = e.rawX; touchY = e.rawY; moved = false
                    // 启动长按计时器，未移动且满 4 秒则弹菜单
                    handler.removeCallbacks(longPressRunnable)
                    handler.postDelayed(longPressRunnable, 1000L)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - touchX).toInt()
                    val dy = (e.rawY - touchY).toInt()
                    if (dx * dx + dy * dy > 25) {
                        moved = true
                        handler.removeCallbacks(longPressRunnable)
                    }
                    params.x = initX + dx
                    params.y = initY + dy
                    try { wm.updateViewLayout(v, params) } catch (_: Exception) {}
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPressRunnable)
                    if (moved) {
                        Prefs.setOverlayPos(this@OverlayService, params.x, params.y)
                        return@setOnTouchListener true
                    }
                    // 余量查询失败显示中：未拖动且点按落在失败文案上 → 打开主界面并进入余量查询页
                    if (!popupMenuActive && marginOverlayFailed && isMarginFailedTapped(v, e)) {
                        openMainAndMarginQuery()
                        return@setOnTouchListener true
                    }
                }
            }
            false
        }
    }

    /** 失败文案是否可点击：失败显示中（tv_margin 可见）且点按落在其范围内。 */
    private fun isMarginFailedTapped(v: View, e: MotionEvent): Boolean {
        val tvMargin = v.findViewById<TextView>(R.id.tv_margin) ?: return false
        if (tvMargin.visibility != View.VISIBLE) return false
        val x = e.x; val y = e.y
        return x >= tvMargin.left && x <= tvMargin.right &&
            y >= tvMargin.top && y <= tvMargin.bottom
    }

    /** 长按浮窗满 4 秒后弹出操作菜单。 */
    private fun showPopupMenu(anchor: View) {
        popupMenuActive = true
        val idClose = 0x0001_0001
        val idVolume = 0x0001_0002
        val idStopWarn = 0x0001_0003
        val idQueryMargin = 0x0001_0004
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, idClose, 0, getString(R.string.overlay_close_data))
        menu.menu.add(0, idVolume, 1, "调节警报音量")
        menu.menu.add(0, idQueryMargin, 2, getString(R.string.overlay_query_margin))
        menu.menu.add(0, idStopWarn, 3, getString(R.string.overlay_stop_warning))
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                idClose -> {
                    openDataSettings()
                    true
                }
                idVolume -> {
                    showVolumeDialog()
                    true
                }
                idQueryMargin -> {
                    showMarginInOverlay()
                    true
                }
                idStopWarn -> {
                    stopWarningAudio()
                    true
                }
                else -> false
            }
        }
        menu.setOnDismissListener { popupMenuActive = false }
        try { menu.show() } catch (_: Exception) { popupMenuActive = false }
    }

    /** 弹出警报音量调节悬浮窗。使用 WindowManager 直接创建，不依赖 Activity。 */
    private fun showVolumeDialog() {
        // 如果已显示则先移除
        removeVolumeOverlay()

        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        val cur = am.getStreamVolume(AudioManager.STREAM_ALARM)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 48, 64, 48)
            setBackgroundColor(0xF0303030.toInt())
        }
        val tvTitle = TextView(this).apply {
            text = "调节警报音量"
            textSize = 16f
            setTextColor(0xFFCCCCCC.toInt())
            setPadding(0, 0, 0, 16)
        }
        val seekBar = SeekBar(this).apply {
            this.max = if (max > 0) max else 1
            progress = cur.coerceIn(0, this.max)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val tvCur = TextView(this).apply {
            text = "当前警报音量：$cur / $max"
            textSize = 14f
            setTextColor(0xFFCCCCCC.toInt())
        }
        // 右上角关闭按钮
        val btnClose = TextView(this).apply {
            text = "×"
            textSize = 20f
            setTextColor(0xFF888888.toInt())
            setPadding(16, 8, 8, 8)
            setOnClickListener { removeVolumeOverlay() }
        }
        // 标题栏：标题 + 关闭按钮
        val titleBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        titleBar.addView(tvTitle, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        titleBar.addView(btnClose)
        layout.addView(titleBar)
        layout.addView(seekBar)
        layout.addView(tvCur)
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                Log.d("OverlayVol", "onProgressChanged p=$p fromUser=$fromUser mainFg=$isMainActivityForeground")
                if (fromUser) {
                    setAlarmVolume(am, p)
                    tvCur.text = "当前警报音量：$p / $max"
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {
                Log.d("OverlayVol", "onStartTrackingTouch mainFg=$isMainActivityForeground")
            }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                Log.d("OverlayVol", "onStopTrackingTouch progress=${seekBar.progress} mainFg=$isMainActivityForeground")
                // 兜底：部分系统仅在松手时才提交音量更改
                setAlarmVolume(am, seekBar.progress)
            }
        })
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            // FLAG_NOT_FOCUSABLE：不请求窗口焦点。后台（主界面不在前台）时，
            // 一个请求焦点的 overlay 窗口拿不到焦点，导致内部 SeekBar 收不到
            // 完整的触摸拖动序列（MOVE/UP），表现为"滑动不起作用"。
            // 加该 flag 后触摸事件仍会正常派发到窗口内的 SeekBar。
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }
        try {
            wm.addView(layout, params)
            volumeOverlayView = layout
            VolumeMediaService.start(this)
        } catch (e: Exception) {
            Log.w("OverlayService", "Failed to show volume overlay: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** 移除音量调节悬浮窗 */
    private fun removeVolumeOverlay() {
        val wasShown = volumeOverlayView != null
        volumeOverlayView?.let {
            try { wm.removeView(it) } catch (_: Exception) {}
        }
        volumeOverlayView = null
        if (wasShown) VolumeMediaService.stop(this)
        handler.post { refreshOverlay() }
    }

    /** 设置警报音量，携带 FLAG_SHOW_UI 确保更改生效并回显系统音量面板。 */
    private fun setAlarmVolume(am: AudioManager, vol: Int) {
        val safe = vol.coerceIn(0, am.getStreamMaxVolume(AudioManager.STREAM_ALARM))
        Log.d("OverlayVol", "setAlarmVolume vol=$vol safe=$safe mainFg=$isMainActivityForeground")
        try {
            am.setStreamVolume(
                AudioManager.STREAM_ALARM,
                safe,
                AudioManager.FLAG_SHOW_UI
            )
        } catch (e: Exception) {
            Log.w("OverlayService", "setStreamVolume failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun openDataSettings() {
        val fallback = Intent(Settings.ACTION_DATA_USAGE_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        for (intent in simSettingsIntents(includePrivilegedExplicit = true) + fallback) {
            try {
                startActivity(intent)
                return
            } catch (_: Exception) {
            }
        }
    }

    private val updateRunnable = object : Runnable {
        override fun run() {
            // Self-heal missing overlays, but do not immediately run a heavy refresh after re-adding.
            if (overlayView == null && !overlayHiddenByUser && PermUtil.canDrawOverlays(this@OverlayService)) {
                addOverlay()
            } else {
                refreshOverlay()
            }
            try { AlarmKeeper.scheduleRolling(this@OverlayService) } catch (_: Exception) {}
            handler.postDelayed(this, 3000L)
        }
    }

    /** Update the warning state on the main thread, then dispatch slower work to a single worker. */
    private fun refreshOverlay() {
        val on = DataMonitor.isMobileDataEnabled(this)
        updateOverlayState(on, lastWlanAssistantOn ?: false)
        requestOverlayDetailsRefresh(on)
    }

    private fun requestOverlayDetailsRefresh(mobileOnHint: Boolean) {
        // Clear the dirty bit before claiming the worker slot. Requests racing with an active
        // worker set it back to true and are replayed from the completion callback.
        overlayStatsNeedsRefresh.set(false)
        if (!overlayStatsPending.compareAndSet(false, true)) {
            overlayStatsNeedsRefresh.set(true)
            return
        }
        try {
            overlayStatsExecutor.execute {
                var latestMobileOn = mobileOnHint
                try {
                    latestMobileOn = DataMonitor.isMobileDataEnabled(this@OverlayService)
                    DataSwitchLog.note(this@OverlayService, latestMobileOn)
                    val autoMobileOn = WlanAssistantMonitor.shouldFlashOverlay(this@OverlayService)
                    DataMonitor.autoUpdateCumulative(this@OverlayService)
                    val scope = Prefs.getTrafficScope(this@OverlayService)
                    val cumulative = Prefs.getCumulativeUsage(this@OverlayService)
                    val usageText = getString(
                        R.string.overlay_traffic_fmt,
                        scope.overlayPrefix,
                        DataMonitor.formatCompact(cumulative)
                    )
                    val renderedMobileOn = latestMobileOn
                    handler.post {
                        overlayStatsPending.set(false)
                        if (isRunning) {
                            lastWlanAssistantOn = autoMobileOn
                            updateOverlayState(renderedMobileOn, autoMobileOn)
                            // 「查询流量」显示期间不覆盖余量行/不恢复累计流量行
                            if (!marginOverlayShowing) {
                                overlayView?.findViewById<TextView>(R.id.tv_usage)?.text = usageText
                            }
                        }
                        if (isRunning && overlayStatsNeedsRefresh.get()) {
                            requestOverlayDetailsRefresh(lastMobileDataState ?: renderedMobileOn)
                        }
                    }
                } catch (e: Exception) {
                    Log.w("OverlayService", "Asynchronous overlay detail refresh failed", e)
                    handler.post {
                        overlayStatsPending.set(false)
                        // Do not spin in a loop on a persistent exception; the next normal refresh retries.
                    }
                }
            }
        } catch (e: Exception) {
            overlayStatsPending.set(false)
            Log.e("OverlayService", "Could not queue overlay detail refresh", e)
        }
    }

    /**
     * Poll the switch on a dedicated thread so UI rendering, UsageStats and traffic
     * accounting cannot delay detection. Start/stop audio directly on this transition,
     * then post only the view/log work back to the main thread.
     */
    private fun startMobileDataFastPoll() {
        if (mobileDataMonitorThread != null) return
        val thread = HandlerThread("MobileDataStateMonitor").apply { start() }
        mobileDataMonitorThread = thread
        val monitorHandler = Handler(thread.looper)
        mobileDataMonitorHandler = monitorHandler
        monitorHandler.post(mobileDataFastPollRunnable)
    }

    private fun stopMobileDataFastPoll() {
        val thread = mobileDataMonitorThread
        val monitorHandler = mobileDataMonitorHandler
        mobileDataMonitorThread = null
        mobileDataMonitorHandler = null
        monitorHandler?.removeCallbacks(mobileDataFastPollRunnable)
        thread?.quitSafely()
    }

    private val mobileDataFastPollRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            try {
                val on = DataMonitor.isMobileDataEnabled(this@OverlayService)
                val previous = lastMobileDataState
                if (previous == null || on != previous) {
                    // Publish the new state before posting to main, so concurrent system
                    // callbacks do not enqueue duplicate transition work.
                    lastMobileDataState = on
                    Log.i(
                        "OverlayService",
                        "Background poll detected mobile-data transition: $previous -> $on"
                    )

                    val shouldFlash = on || (lastWlanAssistantOn == true)
                    when {
                        !shouldFlash -> {
                            userMutedWarning = false
                            warningAudioPlayer.stop()
                        }
                        overlayHiddenByUser || overlayView == null ->
                            warningAudioPlayer.stop()
                        !userMutedWarning ->
                            warningAudioPlayer.start()
                    }

                    handler.post {
                        if (!isRunning) return@post
                        updateOverlayState(on, lastWlanAssistantOn ?: false)
                        requestOverlayDetailsRefresh(on)
                    }
                }
            } catch (e: Exception) {
                Log.w("OverlayService", "Background mobile-data state poll failed", e)
            }
            if (isRunning) {
                mobileDataMonitorHandler?.postDelayed(this, MOBILE_DATA_FAST_POLL_MS)
            }
        }
    }

    private fun updateOverlayState(on: Boolean, autoMobileOn: Boolean) {
        lastMobileDataState = on
        lastWlanAssistantOn = autoMobileOn
        val v = overlayView ?: return
        val tvState = v.findViewById<TextView>(R.id.tv_state)
        tvState.text = when {
            on -> getString(R.string.overlay_data_on)
            autoMobileOn -> getString(R.string.overlay_assistant_on)
            else -> getString(R.string.overlay_data_off)
        }
        if (on || autoMobileOn) startColorAnimation(tvState) else stopColorAnimation(tvState)
    }

    /** 数据开启时：状态文字红绿硬切闪烁。用 Handler 定时切色，不受系统动画缩放影响。 */
    private fun startColorAnimation(tv: TextView) {
        if (colorFlashing) {
            // refreshOverlay runs periodically; it also repairs the sound loop when
            // the Activity transitions from foreground to background.
            startSoundLoop()
            return
        }
        colorFlashing = true
        flashOnRed = true
        tv.setTextColor(0xFFFF0000.toInt())
        tv.invalidate()
        handler.postDelayed(flashRunnable, 400L)
        startSoundLoop()
    }

    private fun stopColorAnimation(tv: TextView) {
        handler.removeCallbacks(flashRunnable)
        colorFlashing = false
        userMutedWarning = false
        tv.setTextColor(0xFFCCCCCC.toInt())
        stopSoundLoop()
    }

    /** 闪烁期间在独立线程连续播放；重复调用是幂等的，不会重启音频。 */
    private fun startSoundLoop() {
        if (userMutedWarning || !colorFlashing) return
        warningAudioPlayer.start()
    }

    /** 停止播放并释放独立音频工作线程；停止警告时保留文字闪烁。 */
    private fun stopSoundLoop() {
        warningAudioPlayer.stop()
    }

    /** 停止警告：本轮数据开启期间静音，保留红绿闪烁提示。 */
    private fun stopWarningAudio() {
        userMutedWarning = true
        stopSoundLoop()
    }

    // ---------- 悬浮窗「查询流量」 ----------

    /** 点击「查询流量」：隐藏累计流量行、显示余量行（先占位“刷新中”），并触发一轮网络刷新。 */
    private fun showMarginInOverlay() {
        val v = overlayView ?: return
        val tvUsage = v.findViewById<TextView>(R.id.tv_usage)
        val tvMargin = v.findViewById<TextView>(R.id.tv_margin)
        marginOverlayShowing = true
        marginOverlayFailed = false
        tvUsage.visibility = View.GONE
        tvMargin.visibility = View.VISIBLE
        tvMargin.text = getString(R.string.margin_refreshing_suffix)
        sessionKeeper?.abortRound()
        startMarginFetch()
        // 重复点击：重置恢复计时（最后点击起算）
        scheduleMarginRestore()
    }

    /** 按主界面设置的秒数调度恢复；0 = 一直显示。 */
    private fun scheduleMarginRestore() {
        val sec = Prefs.getOverlayMarginShowSec(this)
        if (sec <= 0) return
        handler.removeCallbacks(marginOverlayRestoreRunnable)
        handler.postDelayed(marginOverlayRestoreRunnable, sec * 1000L)
    }

    /**
     * 恢复计时到点：查询尚未结束则先不恢复（恢复会取消查询，把结果/失败提示一并吞掉），
     * 由 [renderMarginFromFetch] 拿到结果后再决定重新计时或保留提示。
     */
    private fun onMarginRestoreDue() {
        if (!marginOverlayShowing) return
        if (marginFetcher != null) return
        restoreMarginOverlay()
    }

    /** 超过显示时长：恢复之前的悬浮窗显示（tv_state 不变，tv_usage 重新可见并刷新）。 */
    private fun restoreMarginOverlay() {
        if (!marginOverlayShowing) return
        marginOverlayShowing = false
        marginOverlayFailed = false
        cancelMarginFetch()
        val v = overlayView ?: return
        v.findViewById<TextView>(R.id.tv_margin)?.apply {
            visibility = View.GONE
            text = ""
        }
        v.findViewById<TextView>(R.id.tv_usage)?.visibility = View.VISIBLE
        refreshOverlay()
    }

    /**
     * Service 内静默刷新：临时创建一个 1×1 透明 overlay 窗口挂载隐藏 WebView
     * （避免 WebView 未被挂载时被系统降频导致 20085 请求不发），抓取完成后移除。
     */
    private fun startMarginFetch() {
        cancelMarginFetch()
        val container = LinearLayout(this)
        val params = WindowManager.LayoutParams(
            1, 1,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        try {
            wm.addView(container, params)
        } catch (e: Exception) {
            Log.w("OverlayService", "Failed to add margin fetch window: ${e.javaClass.simpleName}: ${e.message}")
            renderMarginFromFetch(null)
            return
        }
        marginFetchContainer = container
        val fetcher = MarginSilentFetcher(this, container)
        marginFetcher = fetcher
        fetcher.start(Prefs.getWebStorage(this)) { outcome ->
            marginFetcher = null
            renderMarginFromFetch(outcome)
        }
    }

    private fun cancelMarginFetch() {
        marginFetcher?.destroy()
        marginFetcher = null
        removeMarginFetchWindow()
    }

    private fun removeMarginFetchWindow() {
        marginFetchContainer?.let {
            try { wm.removeView(it) } catch (_: Exception) {}
        }
        marginFetchContainer = null
    }

    /** 刷新完成回调：先把结果落盘（与主界面同一份缓存），显示中则更新余量行。 */
    private fun renderMarginFromFetch(outcome: MarginParser.Outcome?) {
        if (outcome != null) {
            if (!outcome.success) {
                Prefs.setMarginStale(this, true)
            } else {
                val r = outcome.result
                Prefs.saveMargin(this, r?.used, r?.remain, r?.unit, outcome.raw)
                Prefs.setMarginStale(this, false)
            }
        }
        removeMarginFetchWindow()
        if (!marginOverlayShowing) return
        val tvMargin = overlayView?.findViewById<TextView>(R.id.tv_margin) ?: return
        val good = outcome?.success == true &&
            !Prefs.getMarginUsed(this).isNullOrBlank() &&
            !Prefs.getMarginRemain(this).isNullOrBlank()
        if (good) {
            // 查询成功：正常显示余量（含时间戳），并从拿到结果的时刻起重新计时恢复
            marginOverlayFailed = false
            tvMargin.text = buildMarginText()
            scheduleMarginRestore()
        } else {
            // 查询失败（超时/登录失效/解析缺字段）：只显示简短失败信息，点击可打开主界面查看。
            // 不参与自动恢复，否则提示会被恢复计时器立刻擦掉
            marginOverlayFailed = true
            tvMargin.text = getString(R.string.margin_overlay_failed)
            handler.removeCallbacks(marginOverlayRestoreRunnable)
        }
    }

    /** 点击失败信息：恢复悬浮窗原状，并打开主界面进入余量查询页。 */
    private fun openMainAndMarginQuery() {
        restoreMarginOverlay()
        try {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(MainActivity.EXTRA_OPEN_MARGIN_QUERY, true)
            )
        } catch (e: Exception) {
            Log.w("OverlayService", "打开余量查询页失败: ${e.message}")
        }
    }

    /** 与主界面 refreshMarginDisplay 同源同格式的余量文本。 */
    private fun buildMarginText(): String {
        val raw = Prefs.getMarginRaw(this)
        if (raw.isNullOrBlank()) return getString(R.string.margin_not_fetched)
        if (Prefs.isMarginStale(this)) return getString(R.string.margin_stale)
        val unit = Prefs.getMarginUnit(this)
        val used = Prefs.getMarginUsed(this)?.let { withMarginUnit(it, unit) }
        val remain = Prefs.getMarginRemain(this)?.let { withMarginUnit(it, unit) }
        if (used.isNullOrBlank() || remain.isNullOrBlank()) return getString(R.string.margin_fetch_pending)
        val time = Prefs.getMarginUpdateTime(this)
        val date = if (time > 0L)
            SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(time))
        else null
        return buildString {
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

    // ---------- 动态注册系统事件（Android 8.0+ 这些广播无法 Manifest 静态接收） ----------
    private fun registerSystemEvents() {
        if (systemEventReceiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> ensureKeepAlive()
                    Intent.ACTION_USER_PRESENT -> {
                        // 用户解锁时重试显示悬浮窗
                        ensureKeepAlive()
                        handler.post {
                            if (overlayView == null && !overlayHiddenByUser && PermUtil.canDrawOverlays(this@OverlayService)) {
                                Log.i("OverlayService", "USER_PRESENT: retrying addOverlay")
                                overlayRetryCount = 0
                                addOverlay()
                            } else {
                                refreshOverlay()
                            }
                        }
                    }
                    Intent.ACTION_SCREEN_ON,
                    Intent.ACTION_TIME_TICK -> {
                        ensureKeepAlive()
                        handler.post { refreshOverlay() }
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_TIME_TICK)
        }
        try {
            registerReceiver(r, filter)
            systemEventReceiver = r
        } catch (_: Exception) {}
    }

    private fun unregisterSystemEvents() {
        systemEventReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
        systemEventReceiver = null
    }

    // ---------- 监听网络变化 ----------
    private fun registerConnectivity() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val req = NetworkRequest.Builder().build()
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { handler.post { refreshOverlay() } }
                override fun onLost(network: Network) { handler.post { refreshOverlay() } }
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { handler.post { refreshOverlay() } }
            }
            cm.registerNetworkCallback(req, cb)
            connectivityCallback = cb
        } catch (_: Exception) {}
    }

    private fun unregisterConnectivity() {
        connectivityCallback?.let {
            try {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                cm.unregisterNetworkCallback(it)
            } catch (_: Exception) {}
        }
        connectivityCallback = null
    }

    private fun registerWlanObserver() {
        if (wlanSettingsObserver != null) return
        wlanSettingsObserver = WlanAssistantMonitor.registerObserver(this, handler) {
            handler.post { refreshOverlay() }
        }
    }

    private fun unregisterWlanObserver() {
        WlanAssistantMonitor.unregisterObserver(this, wlanSettingsObserver)
        wlanSettingsObserver = null
    }

    private fun registerDataEnabledListener() {
        registerMobileDataObserver()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        if (dataEnabledCallback != null) return
        try {
            val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val cb = object : TelephonyCallback(), TelephonyCallback.UserMobileDataStateListener {
                override fun onUserMobileDataStateChanged(enabled: Boolean) {
                    // The registered Executor already dispatches this callback on handler.
                    refreshOverlay()
                }
            }
            val executor = Executor { command -> handler.post(command) }
            tm.registerTelephonyCallback(executor, cb)
            dataEnabledCallback = cb
        } catch (_: Exception) {
        }
    }

    private fun unregisterDataEnabledListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            dataEnabledCallback?.let { cb ->
                try {
                    val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                    tm.unregisterTelephonyCallback(cb)
                } catch (_: Exception) {
                }
            }
        }
        dataEnabledCallback = null
        mobileDataObserver?.let {
            try {
                contentResolver.unregisterContentObserver(it)
            } catch (_: Exception) {
            }
        }
        mobileDataObserver = null
    }

    private fun registerMobileDataObserver() {
        if (mobileDataObserver != null) return
        val obs = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                refreshOverlay()
            }

            override fun onChange(selfChange: Boolean, uri: Uri?) {
                refreshOverlay()
            }
        }
        try {
            val uri = Settings.Global.getUriFor("mobile_data")
            contentResolver.registerContentObserver(uri, false, obs)
            mobileDataObserver = obs
        } catch (_: Exception) {
        }
    }

    private fun registerPhoneState() {
        try {
            val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val listener = object : PhoneStateListener() {
                override fun onServiceStateChanged(state: ServiceState?) {
                    ensureKeepAlive()
                    handler.post { refreshOverlay() }
                }

                override fun onDataActivity(direction: Int) {
                    ensureKeepAlive()
                    if (direction != TelephonyManager.DATA_ACTIVITY_NONE) {
                        handler.post { refreshOverlay() }
                    }
                }

                override fun onDataConnectionStateChanged(state: Int, reason: Int) {
                    ensureKeepAlive()
                    handler.post { refreshOverlay() }
                }
            }
            tm.listen(listener,
                PhoneStateListener.LISTEN_SERVICE_STATE or
                    PhoneStateListener.LISTEN_DATA_ACTIVITY or
                    PhoneStateListener.LISTEN_DATA_CONNECTION_STATE)
            phoneStateListener = listener
        } catch (_: Exception) {}
    }

    private fun unregisterPhoneState() {
        phoneStateListener?.let {
            try {
                val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                tm.listen(it, PhoneStateListener.LISTEN_NONE)
            } catch (_: Exception) {}
        }
        phoneStateListener = null
    }
}

package com.example.mobiledatanotifier

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import org.json.JSONObject

/**
 * 余量会话保活 + 自动重登状态机（主线程，由 OverlayService 持有）。
 *
 * 每轮：MarginSilentFetcher 静默刷新（这本身就是在给服务端会话续命）；
 * 失败/超时按默认网络自动重登——蜂窝复刻页面的 gateWayLogin <img> 免登，
 * WiFi 走 SSOCheck 静默换新；重登后再刷一次验证。重登失败指数退避，
 * WiFi SSO 也失败（必须人工短信）时发一次通知。
 * 全部过程写 [MarginLog] 持久日志（脱敏）。
 */
class MarginSessionKeeper(
    private val ctx: Context,
    /** 用户正在悬浮窗手点「查询流量」时让路（OverlayService.marginFetcher != null）。 */
    private val isUserFetchBusy: () -> Boolean
) {
    companion object {
        private const val TAG = "MarginSessionKeeper"
        private const val MARGIN_URL = "https://wx.10086.cn/cmcc-service/app-pages/main/marginQuery"
        private const val APP_URL = "https://app.10086.cn"
        private const val CHANNEL = "margin_session"
        private const val NOTIFY_ID = 0x4D5353
        private const val FIRST_TICK_DELAY_MS = 5_000L
        private const val BUSY_POSTPONE_MS = 30_000L
        private const val CELL_POLL_MS = 500L
        private const val CELL_TIMEOUT_MS = 10_000L
        private const val SSO_TIMEOUT_MS = 20_000L
        private const val BACKOFF_START_SEC = 600L
        private const val BACKOFF_MAX_SEC = 1_800L
        /** 重登动作完成但 verify 连续失败这么多次 → 判定必须人工登录。 */
        private const val VERIFY_FAIL_TO_MANUAL = 3
    }

    /** 重登动作结果：PENDING = 动作已走完（如 SSO 已回跳），会话有效与否要由 follow-up verify 确认。 */
    private enum class Relogin { DONE, PENDING, FAILED }

    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var roundActive = false
    /** 每轮自增：abort/stop 后使上一轮的残留回调（WebViewClient/轮询）失效。 */
    private var roundId = 0

    private var roundStartMs = 0L
    private var roundNet = "?"
    private var roundContainer: ViewGroup? = null
    private var fetcher: MarginSilentFetcher? = null
    private var reloginView: WebView? = null
    private var reloginKind = "?"

    private var backoffSec = 0L
    private var nextReloginAtMs = 0L
    private var notified = false
    /** 重登后 verify 连续失败次数：连续多次说明自动重登已无效，需要人工登录。 */
    private var verifyFailStreak = 0
    /** 已判定必须人工登录：暂停自动重登（避免无意义请求与 420000 互踢），仅继续轮询等人工恢复。 */
    private var suppressRelogin = false

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            // 上一轮还没结束：重排而不是静默 return，否则任何一次异常都会让循环永久停摆。
            if (roundActive) {
                handler.postDelayed(this, BUSY_POSTPONE_MS)
                return
            }
            val sec = Prefs.getMarginKeepAliveSec(ctx)
            if (sec <= 0) {
                running = false
                MarginLog.log(ctx, "action=loop_stop reason=disabled")
                return
            }
            if (isUserFetchBusy()) {
                handler.postDelayed(this, BUSY_POSTPONE_MS)
                return
            }
            startRound()
        }
    }

    fun start() {
        if (running) return
        running = true
        MarginLog.log(ctx, "action=loop_start interval=${Prefs.getMarginKeepAliveSec(ctx)} overlayPerm=${PermUtil.canDrawOverlays(ctx)}")
        handler.postDelayed(tickRunnable, FIRST_TICK_DELAY_MS)
    }

    fun stop() {
        if (!running && !roundActive) return
        running = false
        handler.removeCallbacksAndMessages(null)
        endRound()
        MarginLog.log(ctx, "action=loop_stop reason=service_stop")
    }

    /** 设置变更：立即按新间隔重排（当前轮不打断，结束后自然按新间隔排）。 */
    fun reschedule() {
        handler.removeCallbacks(tickRunnable)
        if (roundActive) return
        running = true
        handler.postDelayed(tickRunnable, 1_000L)
    }

    /** 用户手点「查询流量」：中止当前轮让路，避免两个隐藏 WebView 并发。 */
    fun abortRound() {
        if (!roundActive) return
        MarginLog.log(ctx, "action=abort reason=user_fetch")
        handler.removeCallbacksAndMessages(null)
        endRound()
        if (running) handler.postDelayed(tickRunnable, Prefs.getMarginKeepAliveSec(ctx) * 1000L)
    }

    // ---------- 轮次 ----------

    private fun startRound() {
        roundActive = true
        roundId++
        roundStartMs = SystemClock.elapsedRealtime()
        roundNet = netType()
        roundContainer = createContainer()
        runFetch(verify = false)
    }

    private fun runFetch(verify: Boolean) {
        val f = MarginSilentFetcher(ctx, roundContainer)
        fetcher = f
        f.start(Prefs.getWebStorage(ctx)) { outcome ->
            fetcher = null
            onFetchDone(outcome, verify)
        }
    }

    private fun onFetchDone(outcome: MarginParser.Outcome?, verify: Boolean) {
        val action = if (verify) "verify" else "fetch"
        val ret = retCodeOf(outcome)
        // 与 OverlayService 对齐：必须真的拿到 used/remain 才算刷新成功，
        // 否则 retCode 看似成功却无数据时会误重置 backoff。
        val ok = outcome != null && outcome.success &&
            !outcome.result?.used.isNullOrBlank() &&
            !outcome.result?.remain.isNullOrBlank()
        val dur = SystemClock.elapsedRealtime() - roundStartMs
        if (ok) {
            val r = outcome!!.result
            Prefs.saveMargin(ctx, r?.used, r?.remain, r?.unit, outcome.raw)
            Prefs.setMarginStale(ctx, false)
            backoffSec = 0L
            nextReloginAtMs = 0L
            notified = false
            verifyFailStreak = 0
            suppressRelogin = false
            MarginLog.log(ctx, "action=$action ok=true net=$roundNet ret=$ret dur=${dur}ms cookies=${MarginLog.cookieNames(APP_URL)}")
            finishRound()
            return
        }
        Prefs.setMarginStale(ctx, true)
        MarginLog.log(ctx, "action=$action ok=false net=$roundNet ret=$ret dur=${dur}ms cookies=${MarginLog.cookieNames(APP_URL)}")
        // 420000 = 同号码在其他设备登录被踢；这边重登会把对方踢回去，只标记不重登。
        if (ret == "420000") {
            MarginLog.log(ctx, "action=relogin_skip reason=420000_kicked")
            finishRound()
            return
        }
        if (verify) {
            // 重登后验证仍失败：SSO 回跳只代表“登录流程走完”，不代表会话真有效。
            // 连续多次仍失败就不再无意义重登，直接转人工。
            verifyFailStreak++
            if (verifyFailStreak >= VERIFY_FAIL_TO_MANUAL) {
                MarginLog.log(ctx, "action=relogin_give_up reason=verify_streak_${verifyFailStreak}")
                onReloginFailed(manualRequired = true, kind = reloginKind)
            } else {
                onReloginFailed()
            }
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (suppressRelogin) {
            // 已通知用户需人工登录：不再重登，只继续按间隔轮询（人工登录后下一轮会自动恢复）。
            MarginLog.log(ctx, "action=relogin_skip reason=manual_required")
            finishRound()
            return
        }
        if (now < nextReloginAtMs) {
            MarginLog.log(ctx, "action=relogin_skip reason=backoff remain=$((nextReloginAtMs - now) / 1000)s")
            finishRound()
            return
        }
        when (roundNet) {
            "cellular" -> reloginCell { r -> afterRelogin(r) }
            "wifi" -> reloginSso { r -> afterRelogin(r) }
            else -> {
                MarginLog.log(ctx, "action=relogin_skip reason=no_network")
                onReloginFailed()
            }
        }
    }

    private fun afterRelogin(result: Relogin) {
        if (!roundActive) return
        when (result) {
            // SSO 回跳到 marginQuery 只说明登录流程走完，会话是否有效交给 verify 判定
            Relogin.DONE, Relogin.PENDING -> {
                CookieManager.getInstance().flush()
                runFetch(verify = true)
            }
            Relogin.FAILED -> onReloginFailed()
        }
    }

    /**
     * @param manualRequired 明确需要人工短信登录（如 SSO 回跳后验证连续失败、
     *   WiFi 下 SSO 也失效）。此时无论网络类型都发通知。
     * @param kind 触发失败的重登方式，未传时用当前 [reloginKind]。
     */
    private fun onReloginFailed(manualRequired: Boolean = false, kind: String = reloginKind) {
        backoffSec = if (backoffSec == 0L) BACKOFF_START_SEC else (backoffSec * 2).coerceAtMost(BACKOFF_MAX_SEC)
        nextReloginAtMs = SystemClock.elapsedRealtime() + backoffSec * 1000L
        Prefs.setMarginStale(ctx, true)
        MarginLog.log(ctx, "action=backoff kind=$kind next=${backoffSec}s manual=$manualRequired")
        // WiFi SSO 失效，或确认必须人工登录：一个失败 streak 内只通知一次。
        if ((manualRequired || kind == "wifi_sso") && !notified) {
            notified = true
            postReloginNotification(manualRequired)
        }
        if (manualRequired) suppressRelogin = true
        finishRound()
    }

    private fun finishRound() {
        endRound()
        if (running) {
            val sec = Prefs.getMarginKeepAliveSec(ctx)
            if (sec > 0) handler.postDelayed(tickRunnable, sec * 1000L)
        }
    }

    /** 释放本轮 WebView/窗口并复位标记（幂等）。 */
    private fun endRound() {
        roundActive = false
        roundId++
        fetcher?.destroy()
        fetcher = null
        destroyReloginView()
        roundContainer?.let {
            try { wm().removeView(it) } catch (_: Exception) {}
        }
        roundContainer = null
    }

    // ---------- 蜂窝：gateWayLogin 免登 ----------

    /**
     * 复刻 leadeon4GLogin.fourGlogin：向 gateWayLogin 发 <img>，响应 Set-Cookie 写
     * JSESSIONID/UID/telNum/is4GLogin。请求走蜂窝时运营商按 SIM 识别，无需短信。
     *
     * 注意：该请求是明文 HTTP，必须依赖 res/xml/network_security_config.xml 中
     * 对 app.10086.cn 的 cleartext 放行；仅靠 mixedContentMode 不够（实测会报
     * ERR_CLEARTEXT_NOT_PERMITTED，导致免登 100% 失败）。
     */
    private fun reloginCell(done: (Relogin) -> Unit) {
        reloginKind = "cell"
        val myRound = roundId
        val start = SystemClock.elapsedRealtime()
        val wv = WebView(ctx)
        reloginView = wv
        wv.settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(wv, true)
        }
        mount(wv)
        val html = "<html><body><img src=\"http://app.10086.cn/biz-sso/autoLogin/gateWayLogin?date=${System.currentTimeMillis()}\"></body></html>"
        wv.loadDataWithBaseURL("$APP_URL/", html, "text/html", "utf-8", null)
        val poll = object : Runnable {
            override fun run() {
                if (roundId != myRound || !roundActive) return
                val cookies = cookieMap("$APP_URL/")
                val elapsed = SystemClock.elapsedRealtime() - start
                when {
                    cookies["is4GLogin"] == "true" &&
                        !cookies["telNum"].isNullOrEmpty() &&
                        !cookies["JSESSIONID"].isNullOrEmpty() -> {
                        MarginLog.log(ctx, "action=relogin_cell ok=true net=$roundNet dur=${elapsed}ms cookies=${MarginLog.cookieNames(APP_URL)}")
                        destroyReloginView()
                        done(Relogin.DONE)
                    }
                    cookies["is4GLogin"] == "false" -> {
                        MarginLog.log(ctx, "action=relogin_cell ok=false net=$roundNet dur=${elapsed}ms reason=is4GLogin_false")
                        destroyReloginView()
                        done(Relogin.FAILED)
                    }
                    elapsed >= CELL_TIMEOUT_MS -> {
                        MarginLog.log(ctx, "action=relogin_cell ok=false net=$roundNet dur=${elapsed}ms reason=timeout")
                        destroyReloginView()
                        done(Relogin.FAILED)
                    }
                    else -> handler.postDelayed(this, CELL_POLL_MS)
                }
            }
        }
        handler.postDelayed(poll, CELL_POLL_MS)
    }

    // ---------- WiFi：SSOCheck 静默换新 ----------

    @SuppressLint("SetJavaScriptEnabled")
    private fun reloginSso(done: (Relogin) -> Unit) {
        reloginKind = "wifi_sso"
        val myRound = roundId
        val start = SystemClock.elapsedRealtime()
        var settled = false
        val wv = WebView(ctx)
        reloginView = wv
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        wv.settings.cacheMode = WebSettings.LOAD_DEFAULT
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(wv, true)
        }
        fun settle(result: Relogin, url: String?, reason: String) {
            if (settled || roundId != myRound || !roundActive) return
            settled = true
            val dur = SystemClock.elapsedRealtime() - start
            val okText = result == Relogin.DONE || result == Relogin.PENDING
            MarginLog.log(ctx, "action=relogin_sso ok=$okText net=$roundNet dur=${dur}ms url=${MarginLog.safeUrl(url)} reason=$reason")
            destroyReloginView()
            done(result)
        }
        // settle 幂等：超时 Runnable 晚触发会被 settled/roundId 挡掉，无需单独移除。
        val timeout = Runnable { settle(Relogin.FAILED, wv.url, "timeout") }
        wv.webViewClient = object : WebViewClient() {
            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                checkUrl(url, "visited")
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                checkUrl(url, "finished")
            }

            private fun checkUrl(url: String?, via: String) {
                if (settled || url.isNullOrBlank()) return
                val u = try { Uri.parse(url) } catch (e: Exception) { return }
                val host = u.host ?: return
                when {
                    // 回跳只代表 SSO 流程走完，会话有效与否交给后续 verify（runFetch(verify=true)）判定。
                    // 以前直接当成功会把无效会话误判为已续期，导致永不退避。
                    host == "wx.10086.cn" && (u.path ?: "").contains("marginQuery") ->
                        settle(Relogin.PENDING, url, "back_to_margin($via)")
                    host == "login.10086.cn" && (u.path ?: "").contains("touch") ->
                        settle(Relogin.FAILED, url, "sso_expired_landing_login($via)")
                }
            }
        }
        mount(wv)
        handler.postDelayed(timeout, SSO_TIMEOUT_MS)
        val ssoUrl = "https://login.10086.cn/SSOCheck.action?channelID=12006&backUrl=${Uri.encode(MARGIN_URL)}&timestamp=${System.currentTimeMillis()}"
        MarginLog.log(ctx, "action=relogin_sso_start net=$roundNet")
        wv.loadUrl(ssoUrl)
    }

    // ---------- 设施 ----------

    private fun wm(): WindowManager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    /** 1×1 透明 overlay 窗口挂载 WebView，避免未挂载被系统降频；无权限降级不挂载。 */
    private fun createContainer(): ViewGroup? {
        if (!PermUtil.canDrawOverlays(ctx)) {
            MarginLog.log(ctx, "action=mount degraded=no_overlay_perm")
            return null
        }
        val container = LinearLayout(ctx)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val params = WindowManager.LayoutParams(
            1, 1, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        return try {
            wm().addView(container, params)
            container
        } catch (e: Exception) {
            Log.w(TAG, "挂载保活窗口失败", e)
            MarginLog.log(ctx, "action=mount degraded=addView_failed")
            null
        }
    }

    private fun mount(wv: WebView) {
        val c = roundContainer ?: return
        try {
            c.addView(wv, ViewGroup.LayoutParams(1, 1))
            wv.alpha = 0f
        } catch (e: Exception) {
            Log.w(TAG, "挂载重登 WebView 失败", e)
        }
    }

    private fun destroyReloginView() {
        val wv = reloginView ?: return
        reloginView = null
        try {
            (wv.parent as? ViewGroup)?.removeView(wv)
            wv.destroy()
        } catch (_: Exception) {}
    }

    private fun cookieMap(url: String): Map<String, String> {
        return try {
            val raw = CookieManager.getInstance().getCookie(url) ?: return emptyMap()
            raw.split(";").mapNotNull {
                val k = it.substringBefore('=').trim()
                val v = it.substringAfter('=', "").trim()
                if (k.isEmpty()) null else k to v
            }.toMap()
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun retCodeOf(outcome: MarginParser.Outcome?): String {
        val raw = outcome?.raw ?: return "timeout"
        return try {
            JSONObject(raw).optString("retCode", "?")
        } catch (e: Exception) {
            "?"
        }
    }

    private fun netType(): String {
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            when {
                caps == null -> "none"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                else -> "other"
            }
        } catch (e: Exception) {
            "none"
        }
    }

    private fun postReloginNotification(manualRequired: Boolean = false) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, ctx.getString(R.string.margin_keepalive_notify_channel), NotificationManager.IMPORTANCE_HIGH)
                )
            }
            val launch = Intent(ctx, MarginQueryActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val pi = PendingIntent.getActivity(
                ctx, NOTIFY_ID, launch,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val text = if (manualRequired) {
                ctx.getString(R.string.margin_keepalive_notify_text_manual)
            } else {
                ctx.getString(R.string.margin_keepalive_notify_text)
            }
            val n = Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(ctx.getString(R.string.margin_keepalive_notify_title))
                .setContentText(text)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .build()
            nm.notify(NOTIFY_ID, n)
            MarginLog.log(ctx, "action=notify reason=sso_expired manual=$manualRequired")
        } catch (e: SecurityException) {
            MarginLog.log(ctx, "action=notify ok=false reason=no_permission")
        } catch (e: Exception) {
            Log.w(TAG, "发重登通知失败", e)
        }
    }
}
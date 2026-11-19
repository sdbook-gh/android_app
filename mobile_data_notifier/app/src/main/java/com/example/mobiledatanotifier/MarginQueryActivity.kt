package com.example.mobiledatanotifier

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast

/**
 * 中国移动「余量查询」H5 页面（marginQuery）内嵌 WebView。
 *
 * 协议背景（详见 /mnt/e/temp/cmcc_protocol.md）：
 * - 页面 URL：https://wx.10086.cn/cmcc-service/app-pages/main/marginQuery
 * - 登录态：首次由用户在 WebView 内手动登录；cookie 与 localStorage/sessionStorage
 *   都会在离开页面时落盘（WebView 自己的写入是异步的，进程被杀会丢），下次进入时恢复。
 * - 页面内嵌的混淆 JS（secureCore）负责请求签名/加密，无需本 App 参与；这里只包装
 *   公开 API window.BasicService_H5module.getNewPlanRemainQry 的 success 回调拿明文。
 */
class MarginQueryActivity : Activity() {

    companion object {
        // H5 页面线上域名（见协议报告「URL 构造」节：getHost() 默认分支）
        private const val MARGIN_QUERY_URL = "https://wx.10086.cn/cmcc-service/app-pages/main/marginQuery"
        // logcat 标签（诊断日志统一前缀）
        private const val TAG = "MarginQuery"
        private const val BRIDGE_NAME = "AndroidBridge"
        // hook 注入重试：每 400ms 一次，最多 40 次（约 16s）
        private const val HOOK_RETRY_MS = 400L
        private const val HOOK_MAX_ATTEMPTS = 40
        // 20085 success 之后页面渲染需要时间，延迟取一次页面文本
        private const val PAGE_TEXT_DELAY_MS = 1200L

        /**
         * 注入到页面的 hook JS：包装公开 API window.BasicService_H5module.getNewPlanRemainQry，
         * 在 success 回调（参数即 secureCore 解密 + JSON.parse 后的明文对象）里把完整明文
         * 传给 Android 桥，并在 1.2s 后回传页面渲染出来的文本兜底。原回调原样透传。
         *
         * 返回值："true" = 已确认包装生效；"false" = 模块还没出现，需要重试。
         * 用 window 属性 setter 兜住「晚于本脚本才挂载模块」的情况。
         */
        private const val HOOK_JS = """
            (function () {
              function wrap(bs) {
                if (!bs || typeof bs.getNewPlanRemainQry !== 'function') return false;
                var orig = bs.getNewPlanRemainQry;
                if (orig.__cmccWrapped) return true;
                var wrapped = function (reqParam) {
                  var rp = reqParam || {};
                  var origSuccess = rp.success;
                  rp.success = function (data) {
                    try { window.AndroidBridge.onMarginData(JSON.stringify(data || {})); } catch (e) {}
                    if (typeof origSuccess === 'function') origSuccess(data);
                    try {
                      setTimeout(function () {
                        var t = document.body ? document.body.innerText : '';
                        window.AndroidBridge.onMarginPageText(t || '');
                      }, $PAGE_TEXT_DELAY_MS);
                    } catch (e) {}
                  };
                  return orig.call(this, rp);
                };
                wrapped.__cmccWrapped = true;
                try { bs.getNewPlanRemainQry = wrapped; } catch (e) { return false; }
                return bs.getNewPlanRemainQry.__cmccWrapped === true;
              }
              if (wrap(window.BasicService_H5module)) { window.__cmccMarginHooked = true; return true; }
              try {
                if (!window.__cmccPropertyTrapped) {
                  var stored = window.BasicService_H5module;
                  Object.defineProperty(window, 'BasicService_H5module', {
                    configurable: true,
                    get: function () { return stored; },
                    set: function (v) { stored = v; try { wrap(v); } catch (e) {} }
                  });
                  window.__cmccPropertyTrapped = true;
                }
              } catch (e) {}
              return false;
            })();
        """

        /**
         * 把 localStorage / sessionStorage 快照回传给原生（离开页面时落盘）。
         * sessionStorage 不跨 WebView 实例，是实现「不用每次收验证码」的关键。
         */
        private const val STORAGE_DUMP_JS = """
            (function () {
              function dump(s) {
                var o = {};
                try {
                  for (var i = 0; i < s.length; i++) {
                    var k = s.key(i);
                    o[k] = s.getItem(k);
                  }
                } catch (e) {}
                return o;
              }
              try {
                window.AndroidBridge.onStorage(JSON.stringify({
                  ls: dump(window.localStorage),
                  ss: dump(window.sessionStorage)
                }));
              } catch (e) {}
            })();
        """
    }

    private lateinit var webView: WebView
    private val handler = Handler(Looper.getMainLooper())
    private var hookAttempts = 0
    private var hookInstalled = false
    /** 20085 明文里没找到可信的流量项，等页面文本兜底。 */
    private var awaitingPageText = false
    private var lastRaw: String? = null

    private val hookInjector = Runnable { injectHookOnce() }
    private val hookVerifier = Runnable { verifyHookOnce() }

    /** 重试注入 hook，直到页面里真的包装成功。 */
    private fun injectHookOnce() {
        if (isFinishing || isDestroyed) return
        if (hookAttempts >= HOOK_MAX_ATTEMPTS) {
            Log.w(TAG, "hook 注入放弃：$HOOK_MAX_ATTEMPTS 次仍未发现 BasicService_H5module，url=${webView.url}")
            return
        }
        hookAttempts++
        webView.evaluateJavascript(HOOK_JS) { result ->
            val ok = result?.trim()?.trim('"') == "true"
            if (ok) {
                if (!hookInstalled) {
                    hookInstalled = true
                    Log.d(TAG, "hook 已安装（第 $hookAttempts 次），url=${webView.url}")
                }
                // 页面可能稍后重新定义该模块，2.5s 后复核一次
                handler.postDelayed(hookVerifier, 2500)
            } else if (!hookInstalled) {
                handler.postDelayed(hookInjector, HOOK_RETRY_MS)
            }
        }
    }

    /** 复核 hook 是否还在（页面重新赋值会覆盖包装）。 */
    private fun verifyHookOnce() {
        if (isFinishing || isDestroyed) return
        webView.evaluateJavascript(HOOK_JS) { result ->
            val ok = result?.trim()?.trim('"') == "true"
            if (!ok) {
                Log.w(TAG, "hook 被页面覆盖，重启注入，url=${webView.url}")
                hookInstalled = false
                hookAttempts = 0
                handler.post(hookInjector)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_margin_query)

        webView = findViewById(R.id.wv_margin)

        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        // 页面含混合内容（H5 素材可能 http/https 混用）
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.displayZoomControls = false
        settings.setSupportZoom(true)
        settings.loadsImagesAutomatically = true
        settings.cacheMode = WebSettings.LOAD_DEFAULT

        // 登录 cookie 持久化（com.android.webview 数据库）+ 允许第三方 cookie
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        // 页内跳转（含登录页 login.10086.cn）全部留在本 WebView，不交系统浏览器
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                // 仅留在本 WebView 内处理 http/https；其余 scheme（intent:// 等）不响应
                val u = url ?: return true
                return !(u.startsWith("http://") || u.startsWith("https://"))
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                findViewById<View>(R.id.pb_margin_progress).visibility = View.VISIBLE
                // 新文档：hook 需要重装；同时尽早把上次的 storage 快照写回
                hookInstalled = false
                hookAttempts = 0
                awaitingPageText = false
                handler.removeCallbacks(hookInjector)
                handler.removeCallbacks(hookVerifier)
                restoreStorage(delayMs = 0L)
            }

            override fun onPageCommitVisible(view: WebView?, url: String?) {
                // 文档已提交、页面脚本可能刚落域：再补一次快照恢复
                restoreStorage(delayMs = 0L)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                findViewById<View>(R.id.pb_margin_progress).visibility = View.GONE
                logCookieNames()
                // 页面反复重绘/前端路由时也持续尝试，直到包装成功（重置重试预算）
                if (!hookInstalled) {
                    hookAttempts = 0
                    handler.post(hookInjector)
                }
                // 兜底再补一次恢复（有些页面在 onPageFinished 之前不会读 storage）
                restoreStorage(delayMs = 300L)
                // 页面已经稳定，把 cookie / storage 落盘，避免进程被杀时丢登录态
                handler.postDelayed({ persistWebState() }, 1000L)
            }

            // API 23+ 主 frame 错误走新签名，旧签名不再触发
            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                val desc = error?.description?.toString() ?: "?"
                val code = error?.errorCode ?: -1
                Log.d(TAG, "onReceivedError code=$code desc=$desc url=${request?.url} main=${request?.isForMainFrame}")
                if (!isFinishing && request?.isForMainFrame == true) {
                    Toast.makeText(
                        this@MarginQueryActivity,
                        getString(R.string.margin_query_net_error),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

            // 记录所有 HTTP 4xx/5xx（含子资源）——定位 TAC /genCaptcha、接口被拒等
            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?
            ) {
                super.onReceivedHttpError(view, request, errorResponse)
                val status = errorResponse?.statusCode ?: -1
                Log.d(TAG, "onReceivedHttpError status=$status url=${request?.url} main=${request?.isForMainFrame}")
            }

            // 中国移动部分 H5 资源存在证书校验问题（抓包环境 legacy renegotiation，
            // 用户手机直连一般无此问题）；为兼容旧证书链，这里放行证书错误但不放行其它。
            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                // 默认策略：拒绝（安全优先）。若手机直连时页面打不开且确认是证书问题，
                // 可改为 handler.proceed() —— 见计划「风险与说明」。
                Log.d(TAG, "onReceivedSslError url=${view?.url} primary=${error?.primaryError}")
                handler?.cancel()
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                findViewById<View>(R.id.pb_margin_progress).apply {
                    visibility = if (newProgress < 100) View.VISIBLE else View.GONE
                }
                // 页面刚开始渲染就抢先注入，避免首页 20085 请求早于 onPageFinished
                if (newProgress in 10..95 && !hookInstalled && hookAttempts == 0) {
                    handler.post(hookInjector)
                }
                if (newProgress >= 30) restoreStorage(delayMs = 0L)
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                if (!title.isNullOrBlank()) {
                    findViewById<android.widget.TextView>(R.id.tv_margin_title).text = title
                }
            }

            // 输出页面 JS console/错误到 logcat —— 定位页面脚本异常/接口失败
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                val msg = consoleMessage ?: return false
                Log.d(TAG, "JS[${msg.messageLevel()}] ${msg.message()} @${msg.sourceId()}:${msg.lineNumber()}")
                return true
            }
        }

        findViewById<android.widget.Button>(R.id.btn_margin_back).setOnClickListener { finish() }
        findViewById<android.widget.Button>(R.id.btn_margin_refresh).setOnClickListener {
            hookInstalled = false
            hookAttempts = 0
            webView.reload()
        }

        // JS 桥：接收页面注入 hook 捕获的 20085 明文（先注册再 loadUrl，避免首帧请求漏掉）
        webView.addJavascriptInterface(MarginJsBridge(), BRIDGE_NAME)

        webView.loadUrl(MARGIN_QUERY_URL)
    }

    override fun onPause() {
        persistWebState()
        super.onPause()
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else finish()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        // 离开前把 cookie 与前端存储落盘：WebView 自己的写入是异步的，
        // 进程随后被杀会丢掉刚登录的会话。
        persistWebState()
        CookieManager.getInstance().flush()
        // WebView 官方建议：destroy 前先从父容器移除，避免“Attempt to remove WebView
        // from parent”崩溃与内存泄漏
        (webView.parent as? android.view.ViewGroup)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }

    /** 把 cookie 与 localStorage/sessionStorage 快照写盘。 */
    private fun persistWebState() {
        // 注意不能判 isFinishing：用户按返回时 onPause/onDestroy 里 isFinishing=true，
        // 正是最需要落盘的时刻。
        if (isDestroyed) return
        try {
            webView.evaluateJavascript(STORAGE_DUMP_JS, null)
            CookieManager.getInstance().flush()
        } catch (e: Exception) {
            Log.w(TAG, "persistWebState 失败", e)
        }
    }

    /** 只打印 cookie 名，不打印值（避免把登录票据写进日志）。 */
    private fun logCookieNames() {
        try {
            val cookie = CookieManager.getInstance().getCookie(MARGIN_QUERY_URL) ?: return
            val names = cookie.split(";").mapNotNull { it.substringBefore('=').trim().takeIf(String::isNotEmpty) }
            Log.d(TAG, "cookie 名=$names（共 ${names.size} 个）")
        } catch (e: Exception) {
            Log.w(TAG, "读取 cookie 失败", e)
        }
    }

    /**
     * 把上次落盘的 localStorage/sessionStorage 写回页面（只补缺失的 key，不覆盖页面新写的值）。
     * 注入时机受 WebView 限制（无 document-start 注入 API），因此在页面开始/提交/结束时各尝试一次。
     */
    private fun restoreStorage(delayMs: Long) {
        val snapshot = Prefs.getWebStorage(this) ?: return
        if (snapshot.length > 200_000) return
        val js = """
            (function () {
              var d = $snapshot;
              function fill(s, o) {
                var n = 0;
                if (!o) return n;
                for (var k in o) {
                  try {
                    if (s.getItem(k) === null) { s.setItem(k, o[k]); n++; }
                  } catch (e) {}
                }
                return n;
              }
              try {
                var n = fill(window.localStorage, d.ls) + fill(window.sessionStorage, d.ss);
                if (n > 0) console.log('[cmcc] storage restored ' + n);
              } catch (e) {}
            })();
        """
        val run = Runnable {
            if (isFinishing || isDestroyed) return@Runnable
            try {
                webView.evaluateJavascript(js, null)
            } catch (e: Exception) {
                Log.w(TAG, "restoreStorage 失败", e)
            }
        }
        if (delayMs <= 0L) handler.post(run) else handler.postDelayed(run, delayMs)
    }

    /**
     * 页面 hook 回调桥。收到 20085 解密后完整明文 JSON。
     * 解析策略：遍历全部节点收集「已用/剩余」候选，按单位与名称打分，
     * 只认流量项（MB/GB 或名称含流量/通用），避免像以前那样把「剩余 30 天」当成余量。
     */
    private inner class MarginJsBridge {
        @JavascriptInterface
        fun onMarginData(json: String) {
            Log.d(TAG, ">>> 20085 明文（完整）:\n$json")
            lastRaw = json
            val outcome = MarginParser.parse(json)
            if (!outcome.success) {
                // 登录失效/接口失败：保留旧数据但标记陈旧，主界面提示重新登录
                Prefs.setMarginStale(this@MarginQueryActivity, true)
                Log.d(TAG, "retCode 非成功，已标记陈旧")
                return
            }
            val best = outcome.result
            if (best != null) {
                Prefs.saveMargin(this@MarginQueryActivity, best.used, best.remain, best.unit, json)
                Prefs.setMarginStale(this@MarginQueryActivity, false)
                awaitingPageText = false
                Log.d(TAG, "解析结果: used=${best.used} remain=${best.remain} unit=${best.unit} 来源=${best.source}")
            } else {
                // 明文里没有可信的流量项：先存原文，等页面文本兜底
                awaitingPageText = true
                Prefs.saveMargin(this@MarginQueryActivity, null, null, null, json)
                Prefs.setMarginStale(this@MarginQueryActivity, false)
                Log.w(TAG, "明文里未找到可信流量项，等页面文本兜底")
            }
        }

        /** 兜底：从页面渲染出来的文本里抠「已用 / 剩余」的带单位数字。 */
        @JavascriptInterface
        fun onMarginPageText(text: String) {
            if (text.isBlank()) return
            Log.d(TAG, "页面文本(${text.length} 字): ${text.take(300).replace('\n', ' ')}")
            if (!awaitingPageText) return
            val parsed = MarginParser.parsePageText(text)
            if (parsed == null) {
                Log.w(TAG, "页面文本里也没找到带单位的余量数字")
                return
            }
            Prefs.saveMargin(
                this@MarginQueryActivity,
                parsed.used,
                parsed.remain,
                null,
                lastRaw
            )
            Prefs.setMarginStale(this@MarginQueryActivity, false)
            awaitingPageText = false
            Log.d(TAG, "页面文本解析结果: used=${parsed.used} remain=${parsed.remain}")
        }

        @JavascriptInterface
        fun onStorage(json: String) {
            if (json.isBlank() || json.length > 200_000) return
            Prefs.saveWebStorage(this@MarginQueryActivity, json)
            Log.d(TAG, "storage 快照已保存（${json.length} 字）")
        }
    }

}

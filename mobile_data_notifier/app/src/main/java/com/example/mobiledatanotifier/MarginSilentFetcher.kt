package com.example.mobiledatanotifier

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * 主界面的「静默刷新」：在不可见的 WebView 里加载余量查询页，
 * 借用页面自己的签名/解密逻辑触发 20085，抓 success 明文交给 [MarginParser]。
 *
 * 与余量查询页共用同一 WebView 数据目录，cookie / localStorage 是同一份登录态，
 * 因此不需要用户重新登录。页面挂 1dp 透明实例而不是完全不挂载，避免被系统降频导致请求不发。
 *
 * 挂载容器由调用方传入：主界面传 activity 内容根；OverlayService 传临时 overlay 窗口容器。
 */
class MarginSilentFetcher(private val context: Context, private val container: ViewGroup?) {

    companion object {
        private const val TAG = "MarginQuery"
        private const val BRIDGE_NAME = "AndroidBridge"
        private const val URL = "https://wx.10086.cn/cmcc-service/app-pages/main/marginQuery"
        private const val RETRY_MS = 400L
        private const val MAX_ATTEMPTS = 40
        private const val TIMEOUT_MS = 25_000L

        /** 包装 getNewPlanRemainQry，success 时把明文回传原生；未挂载时用属性 setter 兜住。 */
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
                  };
                  return orig.call(this, rp);
                };
                wrapped.__cmccWrapped = true;
                try { bs.getNewPlanRemainQry = wrapped; } catch (e) { return false; }
                return bs.getNewPlanRemainQry.__cmccWrapped === true;
              }
              if (wrap(window.BasicService_H5module)) return true;
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
    }

    private val handler = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    private var callback: ((MarginParser.Outcome?) -> Unit)? = null
    private var storageJson: String? = null
    private var attempts = 0
    private var installed = false
    private var finished = false

    private val timeoutRunnable = Runnable {
        Log.w(TAG, "静默刷新超时（${TIMEOUT_MS}ms），url=${webView?.url}")
        finishAndRelease(null)
    }

    /** 开始一次静默刷新。onDone(null) 表示超时/失败到没有回调数据。 */
    fun start(storageSnapshot: String?, onDone: (MarginParser.Outcome?) -> Unit) {
        if (webView != null) return
        finished = false
        attempts = 0
        installed = false
        storageJson = storageSnapshot
        callback = onDone
        val wv = create()
        webView = wv
        handler.postDelayed(timeoutRunnable, TIMEOUT_MS)
        wv.loadUrl(URL)
    }

    /** 离开主界面时调用：放弃当前请求，不再回调。 */
    fun destroy() {
        if (webView == null) return
        callback = null
        finishAndRelease(null)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun create(): WebView {
        val wv = WebView(context)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.cacheMode = WebSettings.LOAD_DEFAULT
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(wv, true)
        }
        wv.addJavascriptInterface(Bridge(), BRIDGE_NAME)
        wv.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                restoreStorage()
                inject()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                restoreStorage()
                inject()
            }
        }
        try {
            container?.addView(
                wv,
                ViewGroup.LayoutParams(1, 1)
            )
            wv.alpha = 0f
        } catch (e: Exception) {
            Log.w(TAG, "挂载隐藏 WebView 失败", e)
        }
        return wv
    }

    /** 把上次落盘的 localStorage/sessionStorage 补回页面（只补缺失的 key）。 */
    private fun restoreStorage() {
        val snapshot = storageJson ?: return
        if (snapshot.length > 200_000) return
        val js = """
            (function () {
              var d = $snapshot;
              function fill(s, o) {
                if (!o) return;
                for (var k in o) {
                  try { if (s.getItem(k) === null) s.setItem(k, o[k]); } catch (e) {}
                }
              }
              try { fill(window.localStorage, d.ls); fill(window.sessionStorage, d.ss); } catch (e) {}
            })();
        """
        webView?.evaluateJavascript(js, null)
    }

    /** 重试注入 hook，直到确认包装生效。 */
    private fun inject() {
        if (finished || installed || attempts >= MAX_ATTEMPTS) return
        val wv = webView ?: return
        attempts++
        wv.evaluateJavascript(HOOK_JS) { result ->
            if (finished) return@evaluateJavascript
            if (result?.trim()?.trim('"') == "true") {
                if (!installed) {
                    installed = true
                    Log.d(TAG, "静默刷新 hook 已安装（第 $attempts 次）")
                }
            } else if (!installed) {
                handler.postDelayed({ inject() }, RETRY_MS)
            }
        }
    }

    private fun finishAndRelease(outcome: MarginParser.Outcome?) {
        finished = true
        handler.removeCallbacksAndMessages(null)
        val cb = callback
        callback = null
        val wv = webView
        webView = null
        try {
            (wv?.parent as? ViewGroup)?.removeView(wv)
            wv?.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "释放隐藏 WebView 失败", e)
        }
        cb?.invoke(outcome)
    }

    private inner class Bridge {
        @JavascriptInterface
        fun onMarginData(json: String) {
            Log.d(TAG, "静默刷新 20085: ${json.take(200)}")
            val outcome = MarginParser.parse(json)
            handler.post { finishAndRelease(outcome) }
        }
    }
}

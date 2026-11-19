package com.example.mobiledatanotifier

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.webkit.CookieManager
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 余量会话保活/自动重登的持久化日志：filesDir/logs/margin.log，
 * 超过 512KB 滚动为 margin.log.1（共约 1MB）。独立线程串行追加、每条 sync，
 * 进程被杀最多丢当前一条；同时镜像到 logcat（tag=MarginLog）。
 *
 * 脱敏红线：只允许记 cookie 名、URL 的 scheme+host+path；
 * 不记 cookie 值/token/raw JSON/完整手机号/URL query（SSO 回跳带 token）。
 */
object MarginLog {
    private const val TAG = "MarginLog"
    private const val MAX_BYTES = 512 * 1024L

    private val thread = HandlerThread("MarginLog").apply { start() }
    private val handler = Handler(thread.looper)
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun log(ctx: Context, msg: String) {
        val line = "${fmt.format(Date())} $msg"
        Log.d(TAG, line)
        val dir = File(ctx.applicationContext.filesDir, "logs")
        handler.post {
            try {
                if (!dir.exists()) dir.mkdirs()
                val f = File(dir, "margin.log")
                if (f.length() > MAX_BYTES) {
                    val old = File(dir, "margin.log.1")
                    if (old.exists()) old.delete()
                    f.renameTo(old)
                }
                FileOutputStream(f, true).use { out ->
                    out.write((line + "\n").toByteArray(Charsets.UTF_8))
                    out.fd.sync()
                }
            } catch (e: Exception) {
                Log.w(TAG, "写日志文件失败", e)
            }
        }
    }

    /** 只取 cookie 名（仿 MarginQueryActivity.logCookieNames），绝不取值。 */
    fun cookieNames(url: String): String {
        return try {
            val cookie = CookieManager.getInstance().getCookie(url) ?: return "[]"
            cookie.split(";").mapNotNull {
                it.substringBefore('=').trim().takeIf(String::isNotEmpty)
            }.toString()
        } catch (e: Exception) {
            "[?]"
        }
    }

    /** URL 只留 scheme+host+path，去掉 query/fragment（可能含 token/ticket）。 */
    fun safeUrl(url: String?): String {
        if (url.isNullOrBlank()) return "?"
        return try {
            val u = Uri.parse(url)
            "${u.scheme}://${u.host}${u.path}"
        } catch (e: Exception) {
            "?"
        }
    }
}
package com.example.mobiledatanotifier

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * 音量调节期间临时启动的 mediaPlayback 类型前台服务。
 *
 * 用途：ColorOS / Android 15+ 的"后台音频强化"要求应用处于"有效音频生命周期"
 * （前台可见，或绑定具备"使用时"权限的媒体前台服务）才允许 setStreamVolume 生效。
 * OverlayService 是 specialUse 前台服务，不满足该条件，故后台调音量被静默拦截。
 *
 * 本服务在音量悬浮窗显示期间运行：
 *  - 前台服务类型 mediaPlayback（manifest 声明）；
 *  - 活跃 MediaSession，PlaybackState = STATE_PLAYING；
 *  - AudioTrack 以 USAGE_MEDIA 持续播放静音 PCM，真正占用音频输出路径。
 * 调节完毕由 OverlayService 关闭悬浮窗时 stop 本服务。
 *
 * 仅服务于音量调节功能，不影响保活链路与主悬浮窗。
 */
class VolumeMediaService : Service() {

    companion object {
        private const val TAG = "VolumeMediaService"
        private const val NOTIF_ID = 2
        const val ACTION_START = "com.example.mobiledatanotifier.VOL_MEDIA_START"
        const val ACTION_STOP = "com.example.mobiledatanotifier.VOL_MEDIA_STOP"

        fun start(ctx: Context) {
            val i = Intent(ctx, VolumeMediaService::class.java).setAction(ACTION_START)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
                else ctx.startService(i)
            } catch (e: Exception) {
                Log.w(TAG, "start failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }

        fun stop(ctx: Context) {
            // 直接 stopService，不通过 startService(ACTION_STOP) 启动服务只为停止它，
            // 避免与随后的 startForegroundService 交叉导致
            // ForegroundServiceDidNotStartInTimeException。
            try { ctx.stopService(Intent(ctx, VolumeMediaService::class.java)) } catch (_: Exception) {}
        }
    }

    @Volatile private var running = false
    private var thread: Thread? = null
    private var track: AudioTrack? = null
    private var session: MediaSession? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 无论何种 action，先履行前台服务契约，
        // 避免 ForegroundServiceDidNotStartInTimeException。
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIF_ID,
                    buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                @Suppress("DEPRECATION")
                startForeground(NOTIF_ID, buildNotification())
            }
        } catch (e: Exception) {
            Log.w(TAG, "startForeground failed: ${e.javaClass.simpleName}: ${e.message}")
            shutdown()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown()
                return START_NOT_STICKY
            }
        }
        if (running) return START_NOT_STICKY
        startPlayback()
        return START_NOT_STICKY
    }

    private fun startPlayback() {
        try {
            val s = MediaSession(this, "volume_media")
            s.setPlaybackState(
                PlaybackState.Builder()
                    .setState(PlaybackState.STATE_PLAYING, 0L, 1f)
                    .build()
            )
            s.isActive = true
            session = s
        } catch (e: Exception) {
            Log.w(TAG, "MediaSession failed: ${e.javaClass.simpleName}: ${e.message}")
        }

        try {
            val sampleRate = 44100
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(1024)
            val t = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minBuf)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            t.play()
            track = t
            running = true
            thread = Thread {
                val silence = ByteArray(minBuf) // 全 0 = 静音
                while (running && !Thread.interrupted()) {
                    try {
                        val r = t.write(silence, 0, silence.size)
                        if (r <= 0) break
                    } catch (_: Throwable) {
                        break
                    }
                }
            }.also { it.start() }
        } catch (e: Exception) {
            Log.w(TAG, "AudioTrack failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun shutdown() {
        running = false
        try { thread?.interrupt() } catch (_: Exception) {}
        thread = null
        try { track?.stop() } catch (_: Exception) {}
        try { track?.release() } catch (_: Exception) {}
        track = null
        try { session?.isActive = false } catch (_: Exception) {}
        try { session?.release() } catch (_: Exception) {}
        session = null
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
        stopSelf()
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        return Notification.Builder(this, OverlayService.CH_FG)
            .setContentTitle("音量调节中")
            .setContentText("正在调节媒体音量")
            .setSmallIcon(R.drawable.ic_notify)
            .setOngoing(true)
            .build()
    }
}

package com.example.mobiledatanotifier

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.util.Log

/**
 * Plays the selected alarm sound continuously on a dedicated worker thread.
 *
 * MediaPlayer handles looping in the audio engine; the worker thread owns all player
 * creation, configuration, callbacks and release. start()/stop() are idempotent, so
 * periodic UI refreshes do not restart the audio or introduce gaps.
 */
internal class LoopingWarningAudioPlayer(context: Context) {
    private val appContext = context.applicationContext
    private val lock = Any()
    private var currentSession: Session? = null

    private class Session(
        val thread: HandlerThread,
        val handler: Handler,
        val sources: List<Uri>
    ) {
        @Volatile var active: Boolean = true
        var player: MediaPlayer? = null
        var toneGenerator: ToneGenerator? = null
        var toneRunnable: Runnable? = null
    }

    /** Start playback immediately; repeated calls while playing do nothing. */
    fun start() {
        val session = synchronized(lock) {
            if (currentSession?.active == true) return
            val sources = buildSources()
            if (sources.isEmpty()) {
                Log.e(TAG, "No alarm audio URI is available")
                return
            }
            val thread = HandlerThread("MobileDataWarningAudio").apply { start() }
            Session(thread, Handler(thread.looper), sources).also { currentSession = it }
        }
        session.handler.post { prepareSource(session, 0) }
    }

    /** Stop the sound and its worker thread promptly. */
    fun stop() {
        val session = synchronized(lock) {
            val active = currentSession ?: return
            currentSession = null
            active.active = false
            active
        }
        // All MediaPlayer/ToneGenerator operations stay on their owner thread.
        session.handler.post {
            cleanup(session)
            session.thread.quitSafely()
        }
    }

    private fun buildSources(): List<Uri> {
        val candidates = mutableListOf<Uri>()
        Prefs.getWarningMusicUri(appContext)?.let { saved ->
            try { candidates.add(Uri.parse(saved)) }
            catch (e: Exception) { Log.w(TAG, "Invalid saved warning URI", e) }
        }
        listOf(
            RingtoneManager.TYPE_ALARM,
            RingtoneManager.TYPE_RINGTONE,
            RingtoneManager.TYPE_NOTIFICATION
        ).forEach { type ->
            try {
                RingtoneManager.getDefaultUri(type)?.let { candidates.add(it) }
            } catch (e: Exception) {
                Log.w(TAG, "Cannot resolve default sound type=$type", e)
            }
        }
        return candidates.distinctBy { it.toString() }
    }

    /** Called on the dedicated HandlerThread. Tries the selected sound, then system defaults. */
    private fun prepareSource(session: Session, index: Int) {
        if (!session.active) {
            cleanup(session)
            session.thread.quitSafely()
            return
        }
        if (index >= session.sources.size) {
            startToneFallback(session)
            return
        }

        val uri = session.sources[index]
        val player = MediaPlayer()
        session.player = player
        try {
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            player.isLooping = true
            player.setOnPreparedListener { ready ->
                if (!session.active || session.player !== ready) {
                    safeRelease(ready)
                    return@setOnPreparedListener
                }
                try {
                    ready.start()
                    Log.i(TAG, "Looping warning audio started")
                } catch (e: Exception) {
                    Log.w(TAG, "Cannot start warning URI=$uri; trying fallback", e)
                    session.player = null
                    safeRelease(ready)
                    prepareSource(session, index + 1)
                }
            }
            player.setOnErrorListener { failed, what, extra ->
                // Ignore delayed callbacks from a player that has already been replaced
                // or released during stop/retry. Otherwise a stale error can spawn a
                // second fallback player and make the sound appear to restart.
                if (session.player !== failed) return@setOnErrorListener true
                Log.e(TAG, "Warning audio error uri=$uri what=$what extra=$extra")
                session.player = null
                safeRelease(failed)
                if (session.active) prepareSource(session, index + 1)
                true
            }
            player.setDataSource(appContext, uri)
            player.prepareAsync()
        } catch (e: Exception) {
            Log.w(TAG, "Cannot prepare warning URI=$uri; trying fallback", e)
            if (session.player === player) session.player = null
            safeRelease(player)
            prepareSource(session, index + 1)
        }
    }

    /** Last-resort audible feedback if every ringtone URI is inaccessible. */
    private fun startToneFallback(session: Session) {
        if (!session.active) return
        try {
            val generator = ToneGenerator(AudioManager.STREAM_ALARM, 85)
            session.toneGenerator = generator
            val beeper = object : Runnable {
                override fun run() {
                    if (!session.active || session.toneGenerator !== generator) return
                    try {
                        val started = generator.startTone(ToneGenerator.TONE_PROP_BEEP, 900)
                        Log.i(TAG, "Warning tone fallback requested: started=$started")
                    } catch (e: Exception) {
                        Log.e(TAG, "Warning tone fallback failed", e)
                    }
                    session.handler.postDelayed(this, 1100L)
                }
            }
            session.toneRunnable = beeper
            session.handler.post(beeper)
        } catch (e: Exception) {
            Log.e(TAG, "Could not create warning tone fallback", e)
        }
    }

    /** Must run on the session worker thread. */
    private fun cleanup(session: Session) {
        session.toneRunnable?.let { session.handler.removeCallbacks(it) }
        session.toneRunnable = null
        try { session.toneGenerator?.stopTone() } catch (_: Exception) {}
        try { session.toneGenerator?.release() } catch (_: Exception) {}
        session.toneGenerator = null

        session.player?.let { player ->
            session.player = null
            safeRelease(player)
        }
    }

    private fun safeRelease(player: MediaPlayer) {
        try { player.setOnPreparedListener(null) } catch (_: Exception) {}
        try { player.setOnErrorListener(null) } catch (_: Exception) {}
        try { if (player.isPlaying) player.stop() } catch (_: Exception) {}
        try { player.reset() } catch (_: Exception) {}
        try { player.release() } catch (_: Exception) {}
    }

    private companion object {
        const val TAG = "LoopingWarningAudio"
    }
}

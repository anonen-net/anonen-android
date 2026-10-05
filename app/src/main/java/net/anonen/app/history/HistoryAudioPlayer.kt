package net.anonen.app.history

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import net.anonen.app.core.DiagnosticsLog
import java.io.File

class HistoryAudioPlayer(private val context: Context) {
    private var player: MediaPlayer? = null
    private var focusRequest: AudioFocusRequest? = null

    var durationMs: Int = 0
        private set

    val positionMs: Int
        get() = player?.let { mp -> runCatching { mp.currentPosition }.getOrDefault(0) } ?: 0

    fun seekTo(ms: Int) {
        val mp = player ?: return
        runCatching { mp.seekTo(ms.coerceIn(0, durationMs)) }
    }

    private val audioManager: AudioManager
        get() = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun play(
        file: File,
        onFinished: () -> Unit,
    ): Boolean {
        stop()
        if (!file.isFile) {
            DiagnosticsLog.log("履歴の音声が見つからない")
            return false
        }

        val mp = MediaPlayer()
        player = mp
        return runCatching {
            requestFocus()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            mp.setDataSource(file.absolutePath)
            mp.setOnCompletionListener {
                stop()
                onFinished()
            }
            mp.setOnErrorListener { _, what, extra ->
                DiagnosticsLog.log("履歴の音声を再生できない: what=$what extra=$extra")
                stop()
                onFinished()
                true
            }
            mp.prepare()

            durationMs = mp.duration.coerceAtLeast(0)
            mp.start()
            true
        }.getOrElse {
            DiagnosticsLog.log("履歴の音声を再生できない: ${it.javaClass.simpleName}")
            stop()
            false
        }
    }

    fun stop() {
        player?.let { mp ->
            runCatching { if (mp.isPlaying) mp.stop() }
            runCatching { mp.release() }
        }
        player = null
        durationMs = 0
        abandonFocus()
    }

    fun release() = stop()

    private fun requestFocus() {
        val req =
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .build()

        runCatching { audioManager.requestAudioFocus(req) }
        focusRequest = req
    }

    private fun abandonFocus() {
        focusRequest?.let { req -> runCatching { audioManager.abandonAudioFocusRequest(req) } }
        focusRequest = null
    }
}

package net.anonen.app.overlay

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import net.anonen.app.R

class FeedbackSounds(context: Context) {
    private val loaded = mutableSetOf<Int>()

    private val soundPool =
        SoundPool.Builder()
            .setMaxStreams(2)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .build()
            .also { pool ->
                pool.setOnLoadCompleteListener { _, sampleId, status ->
                    if (status == 0) loaded.add(sampleId)
                }
            }

    private val startId = soundPool.load(context, R.raw.rec_start, 1)
    private val stopId = soundPool.load(context, R.raw.rec_stop, 1)

    fun playStart(volume: Float) = play(startId, volume)

    fun playStop(volume: Float) = play(stopId, volume)

    private fun play(
        soundId: Int,
        volume: Float,
    ) {
        val v = volume.coerceIn(0f, 1f)
        if (v <= 0f || soundId !in loaded) return
        soundPool.play(soundId, v, v, 1, 0, 1f)
    }

    fun release() = soundPool.release()
}

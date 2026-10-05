package net.anonen.app.record

interface VoiceActivityDetector {
    fun isVoice(frame: ShortArray): Boolean

    fun reset() {}
}

object PassThroughVad : VoiceActivityDetector {
    override fun isVoice(frame: ShortArray): Boolean = true
}

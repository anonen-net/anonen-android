package net.anonen.app.record

class SmoothedVad(
    private val inner: VoiceActivityDetector,
    private val prefillFrames: Int = DEFAULT_PREFILL,
    private val hangoverFrames: Int = DEFAULT_HANGOVER,
    private val onsetFrames: Int = DEFAULT_ONSET,
) {
    private val buffer = ArrayDeque<ShortArray>()
    private var hangover = 0
    private var onset = 0
    private var inSpeech = false

    fun reset() {
        inner.reset()
        buffer.clear()
        hangover = 0
        onset = 0
        inSpeech = false
    }

    fun feedFrame(frame: ShortArray): List<ShortArray> {
        buffer.addLast(frame)
        while (buffer.size > prefillFrames + 1) {
            buffer.removeFirst()
        }

        val voice = inner.isVoice(frame)
        return when {
            !inSpeech && voice -> {
                onset += 1
                if (onset >= onsetFrames) {
                    inSpeech = true
                    hangover = hangoverFrames
                    onset = 0
                    ArrayList(buffer)
                } else {
                    emptyList()
                }
            }
            inSpeech && voice -> {
                hangover = hangoverFrames
                listOf(frame)
            }
            inSpeech && !voice -> {
                if (hangover > 0) {
                    hangover -= 1
                    listOf(frame)
                } else {
                    inSpeech = false
                    emptyList()
                }
            }
            else -> {
                onset = 0
                emptyList()
            }
        }
    }

    fun filter(frames: List<ShortArray>): List<ShortArray> {
        reset()
        val output = ArrayList<ShortArray>()
        for (frame in frames) {
            output.addAll(feedFrame(frame))
        }
        return output
    }

    companion object {
        const val DEFAULT_PREFILL = 15
        const val DEFAULT_HANGOVER = 15
        const val DEFAULT_ONSET = 2
    }
}

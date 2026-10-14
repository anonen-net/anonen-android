package net.anonen.app.record

object WavEncoder {
    fun pcm16ToWav(
        pcmData: ByteArray,
        sampleRate: Int,
        channels: Int,
    ): ByteArray {
        val byteRate = sampleRate * channels * 2
        val blockAlign = channels * 2
        val out = ByteArray(HEADER_SIZE + pcmData.size)

        out.putAscii(0, "RIFF")
        out.putIntLe(4, 36 + pcmData.size)
        out.putAscii(8, "WAVE")

        out.putAscii(12, "fmt ")
        out.putIntLe(16, 16)
        out.putShortLe(20, 1)
        out.putShortLe(22, channels)
        out.putIntLe(24, sampleRate)
        out.putIntLe(28, byteRate)
        out.putShortLe(32, blockAlign)
        out.putShortLe(34, 16)

        out.putAscii(36, "data")
        out.putIntLe(40, pcmData.size)
        pcmData.copyInto(out, destinationOffset = HEADER_SIZE)

        return out
    }

    const val HEADER_SIZE = 44

    fun durationMs(wav: ByteArray): Long {
        if (wav.size < HEADER_SIZE) return 0L
        if (String(wav, 0, 4, Charsets.US_ASCII) != "RIFF" || String(wav, 8, 4, Charsets.US_ASCII) != "WAVE") return 0L
        val byteRate =
            (wav[28].toInt() and 0xFF) or
                ((wav[29].toInt() and 0xFF) shl 8) or
                ((wav[30].toInt() and 0xFF) shl 16) or
                ((wav[31].toInt() and 0xFF) shl 24)
        if (byteRate <= 0) return 0L
        return (wav.size - HEADER_SIZE).toLong() * 1000L / byteRate
    }

    private fun ByteArray.putAscii(
        at: Int,
        s: String,
    ) {
        for ((i, c) in s.withIndex()) this[at + i] = c.code.toByte()
    }

    private fun ByteArray.putIntLe(
        at: Int,
        v: Int,
    ) {
        this[at] = (v and 0xFF).toByte()
        this[at + 1] = ((v shr 8) and 0xFF).toByte()
        this[at + 2] = ((v shr 16) and 0xFF).toByte()
        this[at + 3] = ((v shr 24) and 0xFF).toByte()
    }

    private fun ByteArray.putShortLe(
        at: Int,
        v: Int,
    ) {
        this[at] = (v and 0xFF).toByte()
        this[at + 1] = ((v shr 8) and 0xFF).toByte()
    }
}

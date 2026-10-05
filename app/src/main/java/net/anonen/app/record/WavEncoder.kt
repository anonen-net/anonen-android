package net.anonen.app.record

import java.io.ByteArrayOutputStream

object WavEncoder {
    fun pcm16ToWav(
        pcmData: ByteArray,
        sampleRate: Int,
        channels: Int,
    ): ByteArray {
        val byteRate = sampleRate * channels * 2
        val blockAlign = channels * 2
        val out = ByteArrayOutputStream(HEADER_SIZE + pcmData.size)

        out.writeAscii("RIFF")
        out.writeIntLe(36 + pcmData.size)
        out.writeAscii("WAVE")

        out.writeAscii("fmt ")
        out.writeIntLe(16)
        out.writeShortLe(1)
        out.writeShortLe(channels)
        out.writeIntLe(sampleRate)
        out.writeIntLe(byteRate)
        out.writeShortLe(blockAlign)
        out.writeShortLe(16)

        out.writeAscii("data")
        out.writeIntLe(pcmData.size)
        out.write(pcmData)

        return out.toByteArray()
    }

    const val HEADER_SIZE = 44

    private fun ByteArrayOutputStream.writeAscii(s: String) {
        for (c in s) write(c.code)
    }

    private fun ByteArrayOutputStream.writeIntLe(v: Int) {
        write(v and 0xFF)
        write((v shr 8) and 0xFF)
        write((v shr 16) and 0xFF)
        write((v shr 24) and 0xFF)
    }

    private fun ByteArrayOutputStream.writeShortLe(v: Int) {
        write(v and 0xFF)
        write((v shr 8) and 0xFF)
    }
}

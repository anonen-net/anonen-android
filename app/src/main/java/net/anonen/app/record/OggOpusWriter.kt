package net.anonen.app.record

import java.io.ByteArrayOutputStream

internal object OggOpusWriter {
    const val DEFAULT_PRE_SKIP = 312

    private const val SERIAL = 1
    private const val MAX_SEGMENTS = 255

    private const val OPUS_RATE = 48_000L

    private const val PAGE_BODY_TARGET = 4096

    private const val FLAG_BOS = 0x02
    private const val FLAG_EOS = 0x04
    private const val HEADER_SIZE = 27
    private const val CRC_OFFSET = 22

    fun write(
        packets: List<ByteArray>,
        preSkip: Int,
        inputSampleRate: Int,
        inputSamples: Long,
    ): ByteArray {
        require(packets.isNotEmpty()) { "no Opus packets" }
        require(packets.none { it.isEmpty() }) { "empty Opus packet" }
        require(inputSamples >= 0) { "negative input length" }
        val out = ByteArrayOutputStream()
        var seq = 0
        out.write(page(listOf(opusHead(preSkip, inputSampleRate)), granule = 0, flags = FLAG_BOS, seq = seq++))
        out.write(page(listOf(opusTags()), granule = 0, flags = 0, seq = seq++))

        var granule = 0L
        var onPage = mutableListOf<ByteArray>()
        var segments = 0
        var body = 0
        for (p in packets) {
            val need = p.size / 255 + 1
            require(need <= MAX_SEGMENTS) { "Opus packet too large: ${p.size}B" }
            if (onPage.isNotEmpty() && (segments + need > MAX_SEGMENTS || body + p.size > PAGE_BODY_TARGET)) {
                out.write(page(onPage, granule, flags = 0, seq = seq++))
                onPage = mutableListOf()
                segments = 0
                body = 0
            }
            onPage += p
            segments += need
            body += p.size
            granule += packetSamples48k(p)
        }
        val end = endGranule(granule, packetSamples48k(packets.last()), preSkip, inputSamples, inputSampleRate)
        out.write(page(onPage, end, flags = FLAG_EOS, seq = seq))
        return out.toByteArray()
    }

    internal fun endGranule(
        total: Long,
        lastPacketSamples: Int,
        preSkip: Int,
        inputSamples: Long,
        inputSampleRate: Int,
    ): Long {
        val input48k = (inputSamples * OPUS_RATE + inputSampleRate - 1) / inputSampleRate
        return (preSkip + input48k).coerceIn(total - lastPacketSamples, total)
    }

    internal fun packetSamples48k(packet: ByteArray): Int {
        val toc = packet[0].toInt() and 0xFF
        val frames =
            when (toc and 3) {
                0 -> 1
                1, 2 -> 2
                else -> {
                    require(packet.size >= 2) { "truncated Opus packet" }
                    (packet[1].toInt() and 0x3F).also { require(it >= 1) { "Opus packet declares no frames" } }
                }
            }
        val config = toc shr 3
        val frameSamples =
            when {
                config < 12 -> SILK_SAMPLES[config and 3]
                config < 16 -> HYBRID_SAMPLES[config and 1]
                else -> CELT_SAMPLES[config and 3]
            }
        return frames * frameSamples
    }

    internal fun preSkipFromOpusHead(csd0: ByteArray?): Int? {
        if (csd0 == null || csd0.size < 19 || String(csd0, 0, 8, Charsets.US_ASCII) != "OpusHead") return null
        return (csd0[10].toInt() and 0xFF) or ((csd0[11].toInt() and 0xFF) shl 8)
    }

    internal fun crc32(data: ByteArray): Int {
        var crc = 0
        for (b in data) crc = (crc shl 8) xor CRC_TABLE[((crc ushr 24) xor (b.toInt() and 0xFF)) and 0xFF]
        return crc
    }

    private fun page(
        packets: List<ByteArray>,
        granule: Long,
        flags: Int,
        seq: Int,
    ): ByteArray {
        val lacing = ByteArrayOutputStream()
        for (p in packets) {
            repeat(p.size / 255) { lacing.write(255) }
            lacing.write(p.size % 255)
        }
        val table = lacing.toByteArray()
        val bodySize = packets.sumOf { it.size }
        val buf = ByteArray(HEADER_SIZE + table.size + bodySize)
        "OggS".toByteArray(Charsets.US_ASCII).copyInto(buf, 0)
        buf[4] = 0
        buf[5] = flags.toByte()
        putLong(buf, 6, granule)
        putInt(buf, 14, SERIAL)
        putInt(buf, 18, seq)

        buf[26] = table.size.toByte()
        table.copyInto(buf, HEADER_SIZE)
        var at = HEADER_SIZE + table.size
        for (p in packets) {
            p.copyInto(buf, at)
            at += p.size
        }
        putInt(buf, CRC_OFFSET, crc32(buf))
        return buf
    }

    private fun opusHead(
        preSkip: Int,
        inputSampleRate: Int,
    ): ByteArray {
        val h = ByteArray(19)
        "OpusHead".toByteArray(Charsets.US_ASCII).copyInto(h, 0)
        h[8] = 1
        h[9] = 1
        h[10] = (preSkip and 0xFF).toByte()
        h[11] = ((preSkip shr 8) and 0xFF).toByte()
        putInt(h, 12, inputSampleRate)

        return h
    }

    private fun opusTags(): ByteArray {
        val vendor = "Anonen".toByteArray(Charsets.US_ASCII)
        val t = ByteArray(8 + 4 + vendor.size + 4)
        "OpusTags".toByteArray(Charsets.US_ASCII).copyInto(t, 0)
        putInt(t, 8, vendor.size)
        vendor.copyInto(t, 12)

        return t
    }

    private fun putInt(
        buf: ByteArray,
        at: Int,
        v: Int,
    ) {
        for (i in 0 until 4) buf[at + i] = (v ushr (8 * i)).toByte()
    }

    private fun putLong(
        buf: ByteArray,
        at: Int,
        v: Long,
    ) {
        for (i in 0 until 8) buf[at + i] = (v ushr (8 * i)).toByte()
    }

    private val SILK_SAMPLES = intArrayOf(480, 960, 1920, 2880)
    private val HYBRID_SAMPLES = intArrayOf(480, 960)
    private val CELT_SAMPLES = intArrayOf(120, 240, 480, 960)

    private val CRC_TABLE =
        IntArray(256) { i ->
            var r = i shl 24
            repeat(8) { r = if (r and Int.MIN_VALUE != 0) (r shl 1) xor 0x04C11DB7 else r shl 1 }
            r
        }
}

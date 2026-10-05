package net.anonen.app.ui

internal fun noticeChunks(
    text: String,
    linesPerChunk: Int = NOTICE_LINES_PER_CHUNK,
): List<String> {
    if (text.isEmpty()) return emptyList()
    return text.split('\n').chunked(linesPerChunk).map { it.joinToString("\n") }
}

internal const val NOTICE_LINES_PER_CHUNK = 40

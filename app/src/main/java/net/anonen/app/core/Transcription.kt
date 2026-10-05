package net.anonen.app.core

class TranscriptionRequest(
    val wavBytes: ByteArray,
    val languageHint: String = "",
    val isCancelled: () -> Boolean = { false },
)

sealed interface TranscriptionResult {
    data class Success(val text: String, val durationMs: Long) : TranscriptionResult

    data class Failure(val error: AsrError) : TranscriptionResult
}

data class AsrError(
    val kind: Kind,
    val userMessage: String,
    val detail: String,
    val httpStatus: Int? = null,
    val fallbackHint: String? = null,
    val capResetAt: String? = null,
) {
    enum class Kind { CONFIG, NETWORK, HTTP, PARSE, CANCELLED }

    val isCapExceeded: Boolean get() = fallbackHint != null || capResetAt != null
}

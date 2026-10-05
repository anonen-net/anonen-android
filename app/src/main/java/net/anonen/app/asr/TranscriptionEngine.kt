package net.anonen.app.asr

import net.anonen.app.core.TranscriptionRequest
import net.anonen.app.core.TranscriptionResult

interface TranscriptionEngine {
    fun transcribe(request: TranscriptionRequest): TranscriptionResult
}

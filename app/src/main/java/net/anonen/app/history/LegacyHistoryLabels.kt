package net.anonen.app.history

import net.anonen.app.pipeline.TranscriptionPipeline

internal object LegacyHistoryLabels {
    val TO_CURRENT: Map<String, String> =
        mapOf(
            "（1回の上限を超えた録音 — 未転写）" to TranscriptionPipeline.AUDIO_TOO_LONG_HISTORY_TEXT,
            "（上限に達した録音 — 未転写）" to TranscriptionPipeline.CAP_EXCEEDED_HISTORY_TEXT,
            "（転写に失敗した録音 — 未転写）" to TranscriptionPipeline.FAILED_HISTORY_TEXT,
            "（キャンセルした録音 — 未転写）" to TranscriptionPipeline.CANCELLED_HISTORY_TEXT,
            "（文字が取れなかった録音 — 未転写）" to TranscriptionPipeline.EMPTY_HISTORY_TEXT,
            "（発話を検出できなかった録音 — 未転写）" to TranscriptionPipeline.NO_SPEECH_HISTORY_TEXT,
            "（使える分を使いきったので、文字にしていません）" to TranscriptionPipeline.CAP_EXCEEDED_HISTORY_TEXT,
            "（長すぎて、文字にしていません）" to TranscriptionPipeline.AUDIO_TOO_LONG_HISTORY_TEXT,
            "（使える分を使い切ったので、文字にしていません）" to TranscriptionPipeline.CAP_EXCEEDED_HISTORY_TEXT,
            "（文字にできませんでした）" to TranscriptionPipeline.FAILED_HISTORY_TEXT,
            "（やめたので、文字にしていません）" to TranscriptionPipeline.CANCELLED_HISTORY_TEXT,
        )
}

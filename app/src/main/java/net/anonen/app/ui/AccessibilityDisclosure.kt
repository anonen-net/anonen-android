package net.anonen.app.ui

internal object AccessibilityDisclosure {
    const val READS_LABEL = "読み取るもの"
    const val READS = "入力中の欄の文字と選択範囲、キーボードが出ているか"
    const val USE_LABEL = "使い道"
    const val USE = "入力中だけボタンを出し、話した文字を入れる"
    const val KEEP_LABEL = "保存・送信"
    const val KEEP = "しません"

    val rows: List<Pair<String, String>> =
        listOf(
            READS_LABEL to READS,
            USE_LABEL to USE,
            KEEP_LABEL to KEEP,
        )
}

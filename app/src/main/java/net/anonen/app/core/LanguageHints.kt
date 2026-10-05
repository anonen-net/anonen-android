package net.anonen.app.core

import java.util.Locale

data class LanguageHintOption(
    val code: String,
    val label: String,
)

object LanguageHints {
    val options: List<LanguageHintOption> =
        listOf(
            LanguageHintOption("", "自動判定"),
            LanguageHintOption("ja", "日本語 (ja)"),
            LanguageHintOption("en", "English (en)"),
            LanguageHintOption("zh", "中文 (zh)"),
            LanguageHintOption("ko", "한국어 (ko)"),
        )

    private val codes = options.map { it.code }.toSet()
    private val aliases =
        mapOf(
            "auto" to "",
            "automatic" to "",
            "自動" to "",
            "自動判定" to "",
            "ja" to "ja",
            "jp" to "ja",
            "jpn" to "ja",
            "japanese" to "ja",
            "日本語" to "ja",
            "日本" to "ja",
            "にほんご" to "ja",
            "en" to "en",
            "eng" to "en",
            "english" to "en",
            "英語" to "en",
            "zh" to "zh",
            "zh-cn" to "zh",
            "zh_cn" to "zh",
            "cn" to "zh",
            "chinese" to "zh",
            "中国語" to "zh",
            "中文" to "zh",
            "ko" to "ko",
            "kor" to "ko",
            "korean" to "ko",
            "韓国語" to "ko",
            "朝鮮語" to "ko",
        )

    fun normalize(value: String): String {
        val key = value.trim().lowercase(Locale.ROOT)
        if (key in codes) return key
        return aliases[key] ?: ""
    }

    fun labelFor(code: String): String {
        val normalized = normalize(code)
        return options.firstOrNull { it.code == normalized }?.label ?: options.first().label
    }
}

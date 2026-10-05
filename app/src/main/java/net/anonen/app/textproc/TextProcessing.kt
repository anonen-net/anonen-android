package net.anonen.app.textproc

import kotlin.math.abs

private fun splitWhitespace(text: String): List<String> {
    val result = mutableListOf<String>()
    val sb = StringBuilder()
    for (c in text) {
        if (c.isWhitespace()) {
            if (sb.isNotEmpty()) {
                result.add(sb.toString())
                sb.clear()
            }
        } else {
            sb.append(c)
        }
    }
    if (sb.isNotEmpty()) result.add(sb.toString())
    return result
}

internal fun collapseMultiSpaces(text: String): String {
    val sb = StringBuilder(text.length)
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c.isWhitespace()) {
            var j = i + 1
            while (j < text.length && text[j].isWhitespace()) j++
            if (j - i >= 2) sb.append(' ') else sb.append(c)
            i = j
        } else {
            sb.append(c)
            i++
        }
    }
    return sb.toString()
}

internal fun buildNgram(words: List<String>): String =
    words.joinToString("") { w ->
        w.trim { !it.isLetterOrDigit() }.lowercase()
    }

internal fun extractPunctuation(word: String): Pair<String, String> {
    val prefixEnd = word.takeWhile { !it.isLetterOrDigit() }.length
    val suffixStart = word.takeLastWhile { !it.isLetterOrDigit() }.length
    val prefix = if (prefixEnd > 0) word.substring(0, prefixEnd) else ""
    val suffix = if (suffixStart > 0) word.substring(word.length - suffixStart) else ""
    return prefix to suffix
}

internal fun preserveCasePattern(
    original: String,
    replacement: String,
): String =
    when {
        original.all { it.isUpperCase() } -> replacement.uppercase()
        original.firstOrNull()?.isUpperCase() == true ->
            replacement.replaceFirstChar { it.uppercaseChar() }
        else -> replacement
    }

internal fun findBestMatch(
    candidate: String,
    customWords: List<String>,
    customWordsNospace: List<String>,
    threshold: Double,
): String? {
    if (candidate.isEmpty() || candidate.length > 50) return null

    var bestMatch: String? = null
    var bestScore = Double.MAX_VALUE

    for (i in customWordsNospace.indices) {
        val customWordNospace = customWordsNospace[i]
        val lenDiff = abs(candidate.length - customWordNospace.length).toDouble()
        val maxLen = maxOf(candidate.length, customWordNospace.length).toDouble()
        val maxAllowedDiff = maxOf(maxLen * 0.25, 2.0)
        if (lenDiff > maxAllowedDiff) continue

        val levenshteinScore =
            if (maxLen > 0.0) levenshtein(candidate, customWordNospace) / maxLen else 1.0
        val combinedScore =
            if (soundexMatch(candidate, customWordNospace)) levenshteinScore * 0.3 else levenshteinScore

        if (combinedScore < threshold && combinedScore < bestScore) {
            bestMatch = customWords[i]
            bestScore = combinedScore
        }
    }
    return bestMatch
}

fun applyCustomWords(
    text: String,
    customWords: List<String>,
    threshold: Double,
): String {
    if (customWords.isEmpty()) return text

    val customWordsLower = customWords.map { it.lowercase() }
    val customWordsNospace = customWordsLower.map { it.replace(" ", "") }

    val words = splitWhitespace(text)
    val result = mutableListOf<String>()
    var i = 0

    while (i < words.size) {
        var matched = false
        for (n in 3 downTo 1) {
            if (i + n > words.size) continue
            val ngramWords = words.subList(i, i + n)
            val ngram = buildNgram(ngramWords)
            val replacement = findBestMatch(ngram, customWords, customWordsNospace, threshold)
            if (replacement != null) {
                val (prefix, _) = extractPunctuation(ngramWords[0])
                val (_, suffix) = extractPunctuation(ngramWords[n - 1])
                val corrected = preserveCasePattern(ngramWords[0], replacement)
                result.add("$prefix$corrected$suffix")
                i += n
                matched = true
                break
            }
        }
        if (!matched) {
            result.add(words[i])
            i++
        }
    }
    return result.joinToString(" ")
}

internal fun fillerWordsForLanguage(lang: String): List<String> {
    val baseLang = lang.split('-', '_').first()
    return when (baseLang) {
        "en" ->
            listOf(
                "uh", "um", "uhm", "umm", "uhh", "uhhh", "ah", "hmm", "hm", "mmm", "mm", "mh",
                "eh", "ehh", "ha",
            )
        "es" -> listOf("ehm", "mmm", "hmm", "hm")
        "pt" -> listOf("ahm", "hmm", "mmm", "hm")
        "fr" -> listOf("euh", "hmm", "hm", "mmm")
        "de" -> listOf("äh", "ähm", "hmm", "hm", "mmm")
        "it" -> listOf("ehm", "hmm", "mmm", "hm")
        "cs" -> listOf("ehm", "hmm", "mmm", "hm")
        "pl" -> listOf("hmm", "mmm", "hm")
        "tr" -> listOf("hmm", "mmm", "hm")
        "ru" -> listOf("хм", "ммм", "hmm", "mmm")
        "uk" -> listOf("хм", "ммм", "hmm", "mmm")
        "ar" -> listOf("hmm", "mmm")
        "ja" -> listOf("hmm", "mmm")
        "ko" -> listOf("hmm", "mmm")
        "vi" -> listOf("hmm", "mmm", "hm")
        "zh" -> listOf("hmm", "mmm")
        else ->
            listOf(
                "uh", "uhm", "umm", "uhh", "uhhh", "ah", "hmm", "hm", "mmm", "mm", "mh", "ehh",
            )
    }
}

internal fun collapseStutters(text: String): String {
    val words = splitWhitespace(text)
    if (words.isEmpty()) return text

    val result = mutableListOf<String>()
    var i = 0
    while (i < words.size) {
        val word = words[i]
        val wordLower = word.lowercase()
        if (wordLower.all { it.isLetter() }) {
            var count = 1
            while (i + count < words.size && words[i + count].lowercase() == wordLower) {
                count++
            }
            result.add(word)
            i += if (count >= 3) count else 1
        } else {
            result.add(word)
            i++
        }
    }
    return result.joinToString(" ")
}

fun filterTranscriptionOutput(
    text: String,
    lang: String,
    customFillerWords: List<String>?,
): String {
    var filtered = text

    val fillerWords = customFillerWords ?: fillerWordsForLanguage(lang)

    val patterns =
        fillerWords.map { word ->
            Regex("\\b${Regex.escape(word)}\\b[,.]?", RegexOption.IGNORE_CASE)
        }

    for (pattern in patterns) {
        filtered = pattern.replace(filtered, "")
    }

    filtered = collapseStutters(filtered)
    filtered = collapseMultiSpaces(filtered)
    return filtered.trim()
}

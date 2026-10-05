package net.anonen.app.ui

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

private val JST: ZoneId = ZoneId.of("Asia/Tokyo")

private val JA_WEEKDAYS = arrayOf("月", "火", "水", "木", "金", "土", "日")

internal fun formatUsageDuration(seconds: Int): String {
    val totalMin = (seconds / 60).coerceAtLeast(0)
    val h = totalMin / 60
    val m = totalMin % 60
    return if (h > 0) "${h}h ${m.toString().padStart(2, '0')}m" else "${m}m"
}

internal fun formatWeekResetJst(resetsAt: String?): String? {
    val t = parseToJst(resetsAt) ?: return null
    val weekday = JA_WEEKDAYS[t.dayOfWeek.value - 1]
    return "%d/%d/%d（%s）%02d:%02d".format(t.year, t.monthValue, t.dayOfMonth, weekday, t.hour, t.minute)
}

internal fun formatMonthResetJst(resetsAt: String?): String? {
    val t = parseToJst(resetsAt) ?: return null
    return "${t.year}/${t.monthValue}/${t.dayOfMonth}"
}

private fun parseToJst(resetsAt: String?): ZonedDateTime? {
    if (resetsAt.isNullOrBlank()) return null
    val instant =
        runCatching { Instant.parse(resetsAt) }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(resetsAt).toInstant() }.getOrNull()
            ?: return null
    return instant.atZone(JST)
}

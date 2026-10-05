package net.anonen.app.ui

internal data class UsageBarGeometry(
    val usableFraction: Float,
    val usedFraction: Float,
    val isTrial: Boolean,
    val usedRatio: Float,
) {
    val remainingPercent: Int get() = ((1f - usedRatio).coerceIn(0f, 1f) * 100).toInt()

    val isLow: Boolean get() = usedRatio > LOW_USED_RATIO
}

private const val LOW_USED_RATIO = 0.9f

internal fun usageBarGeometry(
    usedS: Int,
    capS: Int,
    fullCapS: Int,
): UsageBarGeometry {
    val usedRatio = if (capS > 0) usedS.toFloat() / capS else 0f
    if (capS <= 0 || fullCapS <= capS) {
        return UsageBarGeometry(
            usableFraction = 1f,
            usedFraction = usedRatio.coerceIn(0f, 1f),
            isTrial = false,
            usedRatio = usedRatio,
        )
    }
    val usable = capS.toFloat() / fullCapS
    return UsageBarGeometry(
        usableFraction = usable,
        usedFraction = (usedS.toFloat() / fullCapS).coerceIn(0f, usable),
        isTrial = true,
        usedRatio = usedRatio,
    )
}

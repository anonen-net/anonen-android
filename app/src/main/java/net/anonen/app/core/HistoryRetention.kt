package net.anonen.app.core

enum class HistoryRetention {
    NONE,

    RECENT,

    UNLIMITED,
    ;

    val enabled: Boolean get() = this != NONE

    val limit: Int get() = if (this == RECENT) RECENT_LIMIT else 0

    val audioLimit: Int get() = if (this == UNLIMITED) UNLIMITED_AUDIO_LIMIT else 0

    companion object {
        const val RECENT_LIMIT = 20

        const val UNLIMITED_AUDIO_LIMIT = 100

        fun fromName(name: String?): HistoryRetention? = entries.firstOrNull { it.name == name }

        fun fromLegacyEnabled(enabled: Boolean?): HistoryRetention? =
            when (enabled) {
                true -> RECENT
                false -> NONE
                null -> null
            }
    }
}

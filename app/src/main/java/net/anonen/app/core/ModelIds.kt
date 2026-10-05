package net.anonen.app.core

object ModelIds {
    const val ANONEN_CLOUD_PREFIX = "anonen-cloud:"

    const val LEGACY_CLOUD_PREFIX = "handy-cloud:"

    fun normalizeSelected(selectedModel: String): String =
        if (selectedModel.startsWith(LEGACY_CLOUD_PREFIX)) {
            ANONEN_CLOUD_PREFIX + selectedModel.removePrefix(LEGACY_CLOUD_PREFIX)
        } else {
            selectedModel
        }

    const val LOCAL_SENSEVOICE = "local-sensevoice"

    const val NONE = "none"

    fun isNoneSelected(selectedModel: String): Boolean = selectedModel == NONE || selectedModel.isBlank()
}

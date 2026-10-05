package net.anonen.app.inject

import android.text.InputType

internal fun isBlockedPasswordInputType(inputType: Int): Boolean {
    val variation = inputType and InputType.TYPE_MASK_VARIATION
    return when (inputType and InputType.TYPE_MASK_CLASS) {
        InputType.TYPE_CLASS_TEXT ->
            variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
        InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        else -> false
    }
}

internal fun isVisiblePasswordInputType(inputType: Int): Boolean =
    (inputType and InputType.TYPE_MASK_VARIATION) ==
        InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD

internal fun isBlockedPasswordNode(
    isPassword: Boolean,
    inputType: Int,
): Boolean {
    if (isBlockedPasswordInputType(inputType)) return true
    if (!isPassword) return false
    return !isVisiblePasswordInputType(inputType)
}

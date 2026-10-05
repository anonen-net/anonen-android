package net.anonen.app.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object OverlayVisibility {
    private val _inputActive = MutableStateFlow(false)
    val inputActive: StateFlow<Boolean> = _inputActive

    private val _accessibilityConnected = MutableStateFlow(false)
    val accessibilityConnected: StateFlow<Boolean> = _accessibilityConnected

    fun setInputActive(value: Boolean) {
        _inputActive.value = value
    }

    fun setAccessibilityConnected(value: Boolean) {
        _accessibilityConnected.value = value
    }

    fun buttonVisible(
        entitled: Boolean,
        idle: Boolean,
        inputActive: Boolean,
        accessibilityConnected: Boolean,
    ): Boolean = entitled && (!idle || inputActive || !accessibilityConnected)
}

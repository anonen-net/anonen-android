package net.anonen.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity

@Composable
internal fun isLargeText(): Boolean = LocalDensity.current.fontScale > 1.3f

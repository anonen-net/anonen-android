package net.anonen.app.core

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.os.PersistableBundle

fun ClipboardManager.setSensitivePlainText(
    label: String,
    text: String,
) {
    val clip = ClipData.newPlainText(label, text)
    clip.description.extras =
        PersistableBundle().apply {
            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
    setPrimaryClip(clip)
}

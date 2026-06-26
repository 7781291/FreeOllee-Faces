package com.blizzardcaron.freeolleefaces.notify

import android.app.Notification
import androidx.annotation.RequiresApi

/**
 * Maps the platform-agnostic [SemanticStyle] to Android 17's framework semantic-color constants.
 * Caller MUST guard with Build.VERSION.SDK_INT >= 37 — these constants do not exist below android-37.
 */
@RequiresApi(37)
@Suppress("MagicNumber")
fun SemanticStyle.toAndroidSemanticStyle(): Int = when (this) {
    SemanticStyle.INFO -> Notification.SEMANTIC_STYLE_INFO
    SemanticStyle.CAUTION -> Notification.SEMANTIC_STYLE_CAUTION
    SemanticStyle.DANGER -> Notification.SEMANTIC_STYLE_DANGER
}

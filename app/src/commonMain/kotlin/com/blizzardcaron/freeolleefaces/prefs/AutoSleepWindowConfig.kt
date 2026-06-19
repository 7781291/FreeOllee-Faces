package com.blizzardcaron.freeolleefaces.prefs

/** One auto-sleep state: whether the screen may sleep, and after how many idle seconds. */
data class AutoSleepProfile(val autoSleepOn: Boolean, val periodSec: Int)

/**
 * Daily schedule for the watch's auto-sleep register. Inside [startMin, endMin) (minute-of-day,
 * local; start > end wraps midnight) the [inWindow] profile applies, otherwise [outWindow].
 * When [enabled] is false the reconciler is inert and never touches the watch.
 */
data class AutoSleepWindowConfig(
    val enabled: Boolean,
    val startMin: Int,
    val endMin: Int,
    val inWindow: AutoSleepProfile,
    val outWindow: AutoSleepProfile,
)

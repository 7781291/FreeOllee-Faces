package com.blizzardcaron.freeolleefaces.prefs

/** The shared update-interval choices (minutes) offered in Settings. Pure, no Android deps. */
object IntervalOptions {
    val ALLOWED = listOf(15, 30, 45, 60)
    const val DEFAULT = 15

    /** Snap any stored/legacy value to a valid preset; anything off-list falls back to [DEFAULT]. */
    fun coerce(raw: Int): Int = if (raw in ALLOWED) raw else DEFAULT
}

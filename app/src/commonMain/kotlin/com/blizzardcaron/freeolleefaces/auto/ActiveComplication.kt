package com.blizzardcaron.freeolleefaces.auto

/**
 * The single **name-tag** complication the watch shows / auto-updates (writes the `0x2F` nameplate /
 * main digits). Exactly one is active. The notification count is *not* a complication — it's an
 * independent overlay on the `0x34` weekday slot (toggled via
 * [com.blizzardcaron.freeolleefaces.prefs.Prefs.notificationsEnabled]) that coexists with
 * whichever name-tag complication is active.
 */
enum class ActiveComplication {
    TEMPERATURE,

    /** Today's step count, read from Health Connect. */
    STEPS,

    /** Watch battery voltage, read over BLE from the watch's 0x4A version reply. */
    BATTERY,

    /** Surface pressure from the weather network (hPa/inHg), polled like TEMPERATURE. */
    PRESSURE,

    /** Terrain elevation at the saved coords from the weather network (ft/m), polled like TEMPERATURE. */
    ALTITUDE,

    CUSTOM,
}

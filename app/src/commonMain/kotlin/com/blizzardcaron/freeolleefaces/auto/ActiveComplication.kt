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
    SUN,

    /** Today's step count, read from Health Connect. */
    STEPS,

    /** Surface pressure from the weather network (hPa/inHg), polled like TEMPERATURE. */
    PRESSURE,

    /** Not reachable via [fromLegacyAutoSource] — the legacy AutoSource enum had no CUSTOM value. */
    CUSTOM;

    companion object {
        /** Map the legacy `AutoSource` pref name to an [ActiveComplication]. "OFF"/null/unknown -> TEMPERATURE. */
        fun fromLegacyAutoSource(name: String?): ActiveComplication = when (name) {
            "SUN" -> SUN
            "TEMPERATURE" -> TEMPERATURE
            else -> TEMPERATURE
        }
    }
}

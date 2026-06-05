package com.blizzardcaron.freeolleefaces.auto

/**
 * The single **name-tag** face the watch shows / auto-updates (writes the `0x2F` nameplate /
 * main digits). Exactly one is active. The notification count is *not* a face — it's an
 * independent overlay on the `0x34` weekday slot (toggled via
 * [com.blizzardcaron.freeolleefaces.prefs.Prefs.notificationsEnabled]) that coexists with
 * whichever name-tag face is active.
 */
enum class ActiveFace {
    TEMPERATURE,
    SUN,

    /** Today's step count, read from Health Connect. */
    STEPS,

    /** Not reachable via [fromLegacyAutoSource] — the legacy AutoSource enum had no CUSTOM value. */
    CUSTOM;

    companion object {
        /** Map the legacy `AutoSource` pref name to an [ActiveFace]. "OFF"/null/unknown -> TEMPERATURE. */
        fun fromLegacyAutoSource(name: String?): ActiveFace = when (name) {
            "SUN" -> SUN
            "TEMPERATURE" -> TEMPERATURE
            else -> TEMPERATURE
        }
    }
}

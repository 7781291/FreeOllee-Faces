package com.blizzardcaron.freeolleefaces.auto

/** The single face the watch currently shows / auto-updates. Exactly one is active. */
enum class ActiveFace {
    TEMPERATURE,
    SUN,
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

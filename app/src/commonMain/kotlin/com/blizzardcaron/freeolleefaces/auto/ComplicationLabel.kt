package com.blizzardcaron.freeolleefaces.auto

/** The human-readable name shown for each complication, used by both the picker and the Home "Active:" header. */
fun ActiveComplication.displayLabel(): String = when (this) {
    ActiveComplication.TEMPERATURE -> "Temperature"
    ActiveComplication.STEPS -> "Steps"
    ActiveComplication.PRESSURE -> "Pressure"
    ActiveComplication.ALTITUDE -> "Altitude"
    ActiveComplication.CUSTOM -> "Custom"
}

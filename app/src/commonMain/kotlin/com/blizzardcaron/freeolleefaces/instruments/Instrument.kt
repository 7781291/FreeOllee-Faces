package com.blizzardcaron.freeolleefaces.instruments

import com.blizzardcaron.freeolleefaces.activity.ActivityUnit
import com.blizzardcaron.freeolleefaces.format.DisplayFormatter
import com.blizzardcaron.freeolleefaces.format.TempUnit
import com.blizzardcaron.freeolleefaces.format.formatDecimal
import kotlin.math.roundToInt

/**
 * The instrument currently shown on the watch name-tag. `render` is the only path that produces the
 * 6-char wire string (sibling of ActivityMetric). All branches keep glyphs legible by construction;
 * NameplateSanitizer is the runtime backstop.
 */
enum class Instrument {
    COMPASS, ALTITUDE, PRESSURE, TEMPERATURE;

    fun next(): Instrument = entries[(ordinal + 1) % entries.size]

    fun render(state: InstrumentsState, unit: ActivityUnit, tempUnit: TempUnit): String = when (this) {
        COMPASS -> renderCompass(state.headingDeg)
        ALTITUDE -> renderAltitude(state.altitudeM, unit)
        PRESSURE -> renderPressure(state.pressureHpa, unit)
        TEMPERATURE -> renderTemperature(state.onboardTempF, tempUnit)
    }

    private companion object {
        const val FEET_PER_METER = 3.28084
        const val INHG_PER_HPA = 0.0295299830714
        const val FULL_CIRCLE = 360
        const val NAMEPLATE_WIDTH = 6
        const val COMPASS_DIGIT_WIDTH = 3
        const val FAHRENHEIT_OFFSET = 32.0
        const val F_TO_C_NUM = 5.0
        const val F_TO_C_DEN = 9.0
        const val INHG_DECIMALS = 2

        fun renderCompass(headingDeg: Float?): String {
            if (headingDeg == null) return "---#"
            val deg = (headingDeg.roundToInt() % FULL_CIRCLE + FULL_CIRCLE) % FULL_CIRCLE
            return "${deg.toString().padStart(COMPASS_DIGIT_WIDTH, '0')}#${cardinal8(headingDeg)}"
        }

        fun renderAltitude(altM: Double?, unit: ActivityUnit): String {
            if (altM == null) return "---"
            val (value, suffix) =
                if (unit == ActivityUnit.IMPERIAL) (altM * FEET_PER_METER) to 'f' else altM to 'm'
            val num = value.roundToInt().toString()
            return if (num.length >= NAMEPLATE_WIDTH) {
                num.take(NAMEPLATE_WIDTH)
            } else {
                "$num$suffix"
            }
        }

        fun renderPressure(hpa: Double?, unit: ActivityUnit): String {
            if (hpa == null) return "----"
            return if (unit == ActivityUnit.IMPERIAL) {
                formatDecimal(hpa * INHG_PER_HPA, INHG_DECIMALS) // "29.91" ('.' renders as dash)
            } else {
                hpa.roundToInt().toString() // hPa, e.g. "1013"
            }
        }

        fun renderTemperature(tempF: Int?, tempUnit: TempUnit): String {
            if (tempF == null) return "----"
            val value =
                if (tempUnit == TempUnit.CELSIUS) {
                    (tempF - FAHRENHEIT_OFFSET) * F_TO_C_NUM / F_TO_C_DEN
                } else {
                    tempF.toDouble()
                }
            return DisplayFormatter.temperature(value, tempUnit)
        }
    }
}

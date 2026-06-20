package com.blizzardcaron.freeolleefaces.vm

import com.blizzardcaron.freeolleefaces.format.formatDecimal
import com.blizzardcaron.freeolleefaces.format.groupThousands
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format.DateTimeFormat
import kotlinx.datetime.format.Padding
import kotlinx.datetime.format.char
import kotlinx.datetime.toLocalDateTime

/** Number of decimal places shown for displayed lat/lng coordinates. */
internal const val COORD_DECIMALS = 4

/** Valid latitude range bound (degrees), shared by the VM and [ComplicationController]. */
internal const val LAT_ABS_MAX = 90.0

/** Valid longitude range bound (degrees), shared by the VM and [ComplicationController]. */
internal const val LNG_ABS_MAX = 180.0

/** Whether [lat]/[lng] both fall within their valid ranges; shared by [ComplicationController]
 *  and [SettingsController] so the 4-condition coordinate check isn't duplicated. */
internal fun coordsInRange(lat: Double, lng: Double): Boolean =
    lat in -LAT_ABS_MAX..LAT_ABS_MAX && lng in -LNG_ABS_MAX..LNG_ABS_MAX

internal val CLOCK: DateTimeFormat<LocalTime> = LocalTime.Format {
    amPmHour(Padding.NONE)
    char(':')
    minute()
    char(' ')
    amPmMarker("AM", "PM")
}

/**
 * Shared display-string helpers used by both [com.blizzardcaron.freeolleefaces.AppViewModel] and
 * [ComplicationController] — extracted here so the two don't carry duplicate copies (they were
 * verbatim duplicates left over from the [ComplicationController] extraction).
 */
internal fun clockTime(ms: Long): String =
    CLOCK.format(
        Instant.fromEpochMilliseconds(ms)
            .toLocalDateTime(TimeZone.currentSystemDefault())
            .time
    )

internal fun stepsHuman(count: Long): String = "Today: ${groupThousands(count)} steps"

internal fun locLabel(lat: Double?, lng: Double?): String {
    return if (lat != null && lng != null) {
        "Location: ${formatDecimal(lat, COORD_DECIMALS)}, ${formatDecimal(lng, COORD_DECIMALS)}"
    } else {
        "Location: not set"
    }
}

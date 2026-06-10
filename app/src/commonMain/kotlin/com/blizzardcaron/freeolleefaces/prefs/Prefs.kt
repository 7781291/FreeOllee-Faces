package com.blizzardcaron.freeolleefaces.prefs

import com.blizzardcaron.freeolleefaces.auto.ActiveComplication
import com.blizzardcaron.freeolleefaces.format.TempUnit
import com.blizzardcaron.freeolleefaces.notify.FailureKind
import com.russhwolf.settings.Settings
import kotlinx.datetime.Clock

class Prefs(
    private val settings: Settings,
    private val clock: Clock = Clock.System,
) {

    var lastLat: Double?
        get() = if (settings.hasKey(KEY_LAT)) settings.getFloat(KEY_LAT, 0f).toDouble() else null
        set(value) = if (value == null) settings.remove(KEY_LAT) else settings.putFloat(KEY_LAT, value.toFloat())

    var lastLng: Double?
        get() = if (settings.hasKey(KEY_LNG)) settings.getFloat(KEY_LNG, 0f).toDouble() else null
        set(value) = if (value == null) settings.remove(KEY_LNG) else settings.putFloat(KEY_LNG, value.toFloat())

    var watchAddress: String?
        get() = settings.getStringOrNull(KEY_WATCH)
        set(value) = if (value == null) settings.remove(KEY_WATCH) else settings.putString(KEY_WATCH, value)

    var tempUnit: TempUnit
        get() = settings.getStringOrNull(KEY_TEMP_UNIT)
            ?.let { runCatching { TempUnit.valueOf(it) }.getOrNull() }
            ?: TempUnit.FAHRENHEIT
        set(value) = settings.putString(KEY_TEMP_UNIT, value.name)

    var activeFace: ActiveComplication
        get() {
            val stored = settings.getStringOrNull(KEY_ACTIVE_FACE)
            // Legacy: NOTIFICATIONS used to be a mutually-exclusive face. It's now an independent
            // weekday-slot overlay, so migrate it to "the count overlay is on, name-tag face is
            // the default" — once, in place.
            if (stored == LEGACY_NOTIFICATIONS_FACE) {
                notificationsEnabled = true
                settings.putString(KEY_ACTIVE_FACE, ActiveComplication.TEMPERATURE.name)
                return ActiveComplication.TEMPERATURE
            }
            stored?.let {
                runCatching { ActiveComplication.valueOf(it) }.getOrNull()?.let { face -> return face }
            }
            val migrated = ActiveComplication.fromLegacyAutoSource(settings.getStringOrNull(KEY_AUTO_SOURCE))
            settings.putString(KEY_ACTIVE_FACE, migrated.name)
            return migrated
        }
        set(value) = settings.putString(KEY_ACTIVE_FACE, value.name)

    /**
     * Whether the notification count rides the watch's weekday slot (`0x34`). Independent of
     * [activeFace] — the count overlay coexists with whichever name-tag face is active.
     */
    var notificationsEnabled: Boolean
        get() = settings.getBoolean(KEY_NOTIFICATIONS_ENABLED, false)
        set(value) = settings.putBoolean(KEY_NOTIFICATIONS_ENABLED, value)

    var tempValue: Double?
        get() = if (settings.hasKey(KEY_TEMP_VALUE)) settings.getFloat(KEY_TEMP_VALUE, 0f).toDouble() else null
        set(value) = if (value == null) settings.remove(KEY_TEMP_VALUE) else settings.putFloat(KEY_TEMP_VALUE, value.toFloat())

    var tempCacheUnit: TempUnit?
        get() = settings.getStringOrNull(KEY_TEMP_CACHE_UNIT)
            ?.let { runCatching { TempUnit.valueOf(it) }.getOrNull() }
        set(value) = if (value == null) settings.remove(KEY_TEMP_CACHE_UNIT) else settings.putString(KEY_TEMP_CACHE_UNIT, value.name)

    var tempFetchedMs: Long?
        get() = if (settings.hasKey(KEY_TEMP_FETCHED_MS)) settings.getLong(KEY_TEMP_FETCHED_MS, 0L) else null
        set(value) = if (value == null) settings.remove(KEY_TEMP_FETCHED_MS) else settings.putLong(KEY_TEMP_FETCHED_MS, value)

    var lastLocationFetchedMs: Long?
        get() = if (settings.hasKey(KEY_LOCATION_FETCHED_MS)) settings.getLong(KEY_LOCATION_FETCHED_MS, 0L) else null
        set(value) = if (value == null) settings.remove(KEY_LOCATION_FETCHED_MS) else settings.putLong(KEY_LOCATION_FETCHED_MS, value)

    var customText: String
        get() = settings.getString(KEY_CUSTOM_TEXT, "")
        set(value) = settings.putString(KEY_CUSTOM_TEXT, value)

    var customSentMs: Long?
        get() = if (settings.hasKey(KEY_CUSTOM_SENT_MS)) settings.getLong(KEY_CUSTOM_SENT_MS, 0L) else null
        set(value) = if (value == null) settings.remove(KEY_CUSTOM_SENT_MS) else settings.putLong(KEY_CUSTOM_SENT_MS, value)

    /** Live count of undismissed, non-persistent notifications, kept by the listener service. */
    var notificationCount: Int
        get() = settings.getInt(KEY_NOTIFICATION_COUNT, 0)
        set(value) = settings.putInt(KEY_NOTIFICATION_COUNT, value)

    /** Stamp the cached temperature value, the unit it was fetched in, and the fetch time. */
    fun recordTempFetch(value: Double, unit: TempUnit) {
        tempValue = value
        tempCacheUnit = unit
        tempFetchedMs = clock.now().toEpochMilliseconds()
    }

    /** Shared push cadence (minutes) for the interval-driven faces; one of [IntervalOptions.ALLOWED]. */
    var updateIntervalMinutes: Int
        get() = IntervalOptions.coerce(settings.getInt(KEY_UPDATE_INTERVAL, IntervalOptions.DEFAULT))
        set(value) = settings.putInt(KEY_UPDATE_INTERVAL, IntervalOptions.coerce(value))

    var lastStepCount: Long?
        get() = if (settings.hasKey(KEY_STEPS_COUNT)) settings.getLong(KEY_STEPS_COUNT, 0L) else null
        set(value) = if (value == null) settings.remove(KEY_STEPS_COUNT) else settings.putLong(KEY_STEPS_COUNT, value)

    var stepsFetchedMs: Long?
        get() = if (settings.hasKey(KEY_STEPS_FETCHED_MS)) settings.getLong(KEY_STEPS_FETCHED_MS, 0L) else null
        set(value) = if (value == null) settings.remove(KEY_STEPS_FETCHED_MS) else settings.putLong(KEY_STEPS_FETCHED_MS, value)

    /** Stamp the cached step count and the time it was read from Health Connect. */
    fun recordStepsFetch(count: Long) {
        lastStepCount = count
        stepsFetchedMs = clock.now().toEpochMilliseconds()
    }

    var sleepEnabled: Boolean
        get() = settings.getBoolean(KEY_SLEEP_ENABLED, true)
        set(value) = settings.putBoolean(KEY_SLEEP_ENABLED, value)

    var sleepStartMin: Int
        get() = settings.getInt(KEY_SLEEP_START, 22 * 60)
        set(value) = settings.putInt(KEY_SLEEP_START, value)

    var sleepEndMin: Int
        get() = settings.getInt(KEY_SLEEP_END, 6 * 60)
        set(value) = settings.putInt(KEY_SLEEP_END, value)

    var lastAutoSendMs: Long?
        get() = if (settings.hasKey(KEY_LAST_SEND_MS)) settings.getLong(KEY_LAST_SEND_MS, 0L) else null
        set(value) = if (value == null) settings.remove(KEY_LAST_SEND_MS) else settings.putLong(KEY_LAST_SEND_MS, value)

    var lastAutoSendSummary: String?
        get() = settings.getStringOrNull(KEY_LAST_SEND_SUMMARY)
        set(value) = if (value == null) settings.remove(KEY_LAST_SEND_SUMMARY) else settings.putString(KEY_LAST_SEND_SUMMARY, value)

    var lastNotifiedKind: FailureKind?
        get() = settings.getStringOrNull(KEY_LAST_NOTIFIED_KIND)
            ?.let { runCatching { FailureKind.valueOf(it) }.getOrNull() }
        set(value) = if (value == null) settings.remove(KEY_LAST_NOTIFIED_KIND) else settings.putString(KEY_LAST_NOTIFIED_KIND, value.name)

    /** Convenience: stamp the time and summary of the most recent background send attempt. */
    fun recordAutoSend(summary: String) {
        lastAutoSendMs = clock.now().toEpochMilliseconds()
        lastAutoSendSummary = summary
    }

    companion object {
        private const val KEY_LAT = "last_lat"
        private const val KEY_LNG = "last_lng"
        private const val KEY_WATCH = "watch_address"
        private const val KEY_TEMP_UNIT = "temp_unit"
        private const val KEY_AUTO_SOURCE = "auto_source"
        private const val KEY_ACTIVE_FACE = "active_face"
        private const val KEY_TEMP_VALUE = "temp_value"
        private const val KEY_TEMP_CACHE_UNIT = "temp_cache_unit"
        private const val KEY_TEMP_FETCHED_MS = "temp_fetched_ms"
        private const val KEY_LOCATION_FETCHED_MS = "location_fetched_ms"
        private const val KEY_CUSTOM_TEXT = "custom_text"
        private const val KEY_CUSTOM_SENT_MS = "custom_sent_ms"
        private const val KEY_NOTIFICATION_COUNT = "notification_count"
        private const val KEY_NOTIFICATIONS_ENABLED = "notifications_enabled"
        /** Legacy `active_face` value from when notifications was a mutually-exclusive face. */
        private const val LEGACY_NOTIFICATIONS_FACE = "NOTIFICATIONS"
        private const val KEY_UPDATE_INTERVAL = "update_interval_min"
        private const val KEY_STEPS_COUNT = "steps_last_count"
        private const val KEY_STEPS_FETCHED_MS = "steps_fetched_ms"
        private const val KEY_SLEEP_ENABLED = "sleep_enabled"
        private const val KEY_SLEEP_START = "sleep_start_min"
        private const val KEY_SLEEP_END = "sleep_end_min"
        private const val KEY_LAST_SEND_MS = "last_auto_send_ms"
        private const val KEY_LAST_SEND_SUMMARY = "last_auto_send_summary"
        private const val KEY_LAST_NOTIFIED_KIND = "last_notified_kind"
    }
}

package com.blizzardcaron.freeolleefaces.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.blizzardcaron.freeolleefaces.format.TempUnit
import com.blizzardcaron.freeolleefaces.auto.ActiveFace
import com.blizzardcaron.freeolleefaces.notify.FailureKind

class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var lastLat: Double?
        get() = if (sp.contains(KEY_LAT)) sp.getFloat(KEY_LAT, 0f).toDouble() else null
        set(value) = sp.edit { if (value == null) remove(KEY_LAT) else putFloat(KEY_LAT, value.toFloat()) }

    var lastLng: Double?
        get() = if (sp.contains(KEY_LNG)) sp.getFloat(KEY_LNG, 0f).toDouble() else null
        set(value) = sp.edit { if (value == null) remove(KEY_LNG) else putFloat(KEY_LNG, value.toFloat()) }

    var watchAddress: String?
        get() = sp.getString(KEY_WATCH, null)
        set(value) = sp.edit { if (value == null) remove(KEY_WATCH) else putString(KEY_WATCH, value) }

    var tempUnit: TempUnit
        get() = sp.getString(KEY_TEMP_UNIT, null)
            ?.let { runCatching { TempUnit.valueOf(it) }.getOrNull() }
            ?: TempUnit.FAHRENHEIT
        set(value) = sp.edit { putString(KEY_TEMP_UNIT, value.name) }

    var activeFace: ActiveFace
        get() {
            sp.getString(KEY_ACTIVE_FACE, null)?.let { stored ->
                runCatching { ActiveFace.valueOf(stored) }.getOrNull()?.let { return it }
            }
            val migrated = ActiveFace.fromLegacyAutoSource(sp.getString(KEY_AUTO_SOURCE, null))
            sp.edit { putString(KEY_ACTIVE_FACE, migrated.name) }
            return migrated
        }
        set(value) = sp.edit { putString(KEY_ACTIVE_FACE, value.name) }

    var tempValue: Double?
        get() = if (sp.contains(KEY_TEMP_VALUE)) sp.getFloat(KEY_TEMP_VALUE, 0f).toDouble() else null
        set(value) = sp.edit { if (value == null) remove(KEY_TEMP_VALUE) else putFloat(KEY_TEMP_VALUE, value.toFloat()) }

    var tempCacheUnit: TempUnit?
        get() = sp.getString(KEY_TEMP_CACHE_UNIT, null)
            ?.let { runCatching { TempUnit.valueOf(it) }.getOrNull() }
        set(value) = sp.edit { if (value == null) remove(KEY_TEMP_CACHE_UNIT) else putString(KEY_TEMP_CACHE_UNIT, value.name) }

    var tempFetchedMs: Long?
        get() = if (sp.contains(KEY_TEMP_FETCHED_MS)) sp.getLong(KEY_TEMP_FETCHED_MS, 0L) else null
        set(value) = sp.edit { if (value == null) remove(KEY_TEMP_FETCHED_MS) else putLong(KEY_TEMP_FETCHED_MS, value) }

    var lastLocationFetchedMs: Long?
        get() = if (sp.contains(KEY_LOCATION_FETCHED_MS)) sp.getLong(KEY_LOCATION_FETCHED_MS, 0L) else null
        set(value) = sp.edit { if (value == null) remove(KEY_LOCATION_FETCHED_MS) else putLong(KEY_LOCATION_FETCHED_MS, value) }

    var customText: String
        get() = sp.getString(KEY_CUSTOM_TEXT, "") ?: ""
        set(value) = sp.edit { putString(KEY_CUSTOM_TEXT, value) }

    var customSentMs: Long?
        get() = if (sp.contains(KEY_CUSTOM_SENT_MS)) sp.getLong(KEY_CUSTOM_SENT_MS, 0L) else null
        set(value) = sp.edit { if (value == null) remove(KEY_CUSTOM_SENT_MS) else putLong(KEY_CUSTOM_SENT_MS, value) }

    /** Stamp the cached temperature value, the unit it was fetched in, and the fetch time. */
    fun recordTempFetch(value: Double, unit: TempUnit) {
        tempValue = value
        tempCacheUnit = unit
        tempFetchedMs = System.currentTimeMillis()
    }

    /** Shared push cadence (minutes) for the interval-driven faces; one of [IntervalOptions.ALLOWED]. */
    var updateIntervalMinutes: Int
        get() = IntervalOptions.coerce(sp.getInt(KEY_UPDATE_INTERVAL, IntervalOptions.DEFAULT))
        set(value) = sp.edit { putInt(KEY_UPDATE_INTERVAL, IntervalOptions.coerce(value)) }

    var lastStepCount: Long?
        get() = if (sp.contains(KEY_STEPS_COUNT)) sp.getLong(KEY_STEPS_COUNT, 0L) else null
        set(value) = sp.edit { if (value == null) remove(KEY_STEPS_COUNT) else putLong(KEY_STEPS_COUNT, value) }

    var stepsFetchedMs: Long?
        get() = if (sp.contains(KEY_STEPS_FETCHED_MS)) sp.getLong(KEY_STEPS_FETCHED_MS, 0L) else null
        set(value) = sp.edit { if (value == null) remove(KEY_STEPS_FETCHED_MS) else putLong(KEY_STEPS_FETCHED_MS, value) }

    /** Stamp the cached step count and the time it was read from Health Connect. */
    fun recordStepsFetch(count: Long) {
        lastStepCount = count
        stepsFetchedMs = System.currentTimeMillis()
    }

    var sleepEnabled: Boolean
        get() = sp.getBoolean(KEY_SLEEP_ENABLED, true)
        set(value) = sp.edit { putBoolean(KEY_SLEEP_ENABLED, value) }

    var sleepStartMin: Int
        get() = sp.getInt(KEY_SLEEP_START, 22 * 60)
        set(value) = sp.edit { putInt(KEY_SLEEP_START, value) }

    var sleepEndMin: Int
        get() = sp.getInt(KEY_SLEEP_END, 6 * 60)
        set(value) = sp.edit { putInt(KEY_SLEEP_END, value) }

    var lastAutoSendMs: Long?
        get() = if (sp.contains(KEY_LAST_SEND_MS)) sp.getLong(KEY_LAST_SEND_MS, 0L) else null
        set(value) = sp.edit { if (value == null) remove(KEY_LAST_SEND_MS) else putLong(KEY_LAST_SEND_MS, value) }

    var lastAutoSendSummary: String?
        get() = sp.getString(KEY_LAST_SEND_SUMMARY, null)
        set(value) = sp.edit { if (value == null) remove(KEY_LAST_SEND_SUMMARY) else putString(KEY_LAST_SEND_SUMMARY, value) }

    var lastNotifiedKind: FailureKind?
        get() = sp.getString(KEY_LAST_NOTIFIED_KIND, null)
            ?.let { runCatching { FailureKind.valueOf(it) }.getOrNull() }
        set(value) = sp.edit { if (value == null) remove(KEY_LAST_NOTIFIED_KIND) else putString(KEY_LAST_NOTIFIED_KIND, value.name) }

    /** Convenience: stamp the time and summary of the most recent background send attempt. */
    fun recordAutoSend(summary: String) {
        lastAutoSendMs = System.currentTimeMillis()
        lastAutoSendSummary = summary
    }

    companion object {
        private const val FILE = "freeollee_faces_prefs"
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

package com.blizzardcaron.freeolleefaces.alarm

import com.russhwolf.settings.Settings

/**
 * Persists up to [MAX_ALARMS] alarms (JSON via [AlarmsJson]) in a dedicated [Settings] store.
 * Thin glue over the codec; mirrors [com.blizzardcaron.freeolleefaces.timer.TimerSetsRepository].
 */
class AlarmsRepository(private val settings: Settings) {

    fun getAll(): List<Alarm> = AlarmsJson.decode(settings.getStringOrNull(KEY_ALARMS))

    fun get(id: String): Alarm? = getAll().firstOrNull { it.id == id }

    /** Insert or replace [alarm] by id. Replace keeps position; insert appends (capped at [MAX_ALARMS]). */
    fun save(alarm: Alarm) {
        val existing = getAll()
        val merged = if (existing.any { it.id == alarm.id }) {
            existing.map { if (it.id == alarm.id) alarm else it }
        } else {
            (existing + alarm).take(MAX_ALARMS)
        }
        settings.putString(KEY_ALARMS, AlarmsJson.encode(merged))
    }

    fun delete(id: String) {
        val remaining = getAll().filter { it.id != id }
        settings.putString(KEY_ALARMS, AlarmsJson.encode(remaining))
    }

    companion object {
        const val MAX_ALARMS = 5
        private const val KEY_ALARMS = "alarms"
    }
}

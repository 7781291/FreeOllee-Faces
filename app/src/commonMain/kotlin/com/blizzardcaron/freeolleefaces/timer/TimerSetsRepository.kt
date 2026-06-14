package com.blizzardcaron.freeolleefaces.timer

import com.russhwolf.settings.Settings

/**
 * Persists up to [MAX_SETS] timer sets (JSON via [TimerSetsJson]) plus the active set id, in a
 * dedicated [Settings] store. Thin glue over the codec; mirrors the app's `Prefs` pattern.
 */
class TimerSetsRepository(private val settings: Settings) {

    fun getAll(): List<TimerSet> = TimerSetsJson.decode(settings.getStringOrNull(KEY_SETS))

    fun get(id: String): TimerSet? = getAll().firstOrNull { it.id == id }

    /** Insert or replace [set] by id. Replace keeps position; insert appends (capped at [MAX_SETS]). */
    fun save(set: TimerSet) {
        val existing = getAll()
        val merged = if (existing.any { it.id == set.id }) {
            existing.map { if (it.id == set.id) set else it }
        } else {
            (existing + set).take(MAX_SETS)
        }
        settings.putString(KEY_SETS, TimerSetsJson.encode(merged))
    }

    /**
     * Persist a new ordering of the existing sets. Sets are reordered by id following
     * [orderedIds]; any current set whose id is absent from [orderedIds] is appended in its
     * existing relative order (contents can never be lost). Unknown ids are ignored. The active
     * id is untouched.
     */
    fun reorder(orderedIds: List<String>) {
        val byId = getAll().associateBy { it.id }
        val ordered = orderedIds.mapNotNull { byId[it] }
        val remaining = byId.values.filter { it.id !in orderedIds }
        settings.putString(KEY_SETS, TimerSetsJson.encode(ordered + remaining))
    }

    fun delete(id: String) {
        val remaining = getAll().filter { it.id != id }
        settings.putString(KEY_SETS, TimerSetsJson.encode(remaining))
        if (settings.getStringOrNull(KEY_ACTIVE) == id) settings.remove(KEY_ACTIVE)
    }

    fun setActive(id: String) = settings.putString(KEY_ACTIVE, id)

    fun activeId(): String? = settings.getStringOrNull(KEY_ACTIVE)

    companion object {
        const val MAX_SETS = 10
        private const val KEY_SETS = "sets"
        private const val KEY_ACTIVE = "active_id"
    }
}

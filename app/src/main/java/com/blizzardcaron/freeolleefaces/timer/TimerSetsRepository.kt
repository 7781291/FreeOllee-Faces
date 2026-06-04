package com.blizzardcaron.freeolleefaces.timer

import android.content.Context
import androidx.core.content.edit

/**
 * Persists up to [MAX_SETS] timer sets (JSON via [TimerSetsJson]) plus the active set id, in a
 * dedicated SharedPreferences file. Thin glue over the codec; mirrors the app's `Prefs` pattern.
 */
class TimerSetsRepository(context: Context) {

    private val sp = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun getAll(): List<TimerSet> = TimerSetsJson.decode(sp.getString(KEY_SETS, null))

    fun get(id: String): TimerSet? = getAll().firstOrNull { it.id == id }

    /** Insert or replace [set] by id. Replace keeps position; insert appends (capped at [MAX_SETS]). */
    fun save(set: TimerSet) {
        val existing = getAll()
        val merged = if (existing.any { it.id == set.id }) {
            existing.map { if (it.id == set.id) set else it }
        } else {
            (existing + set).take(MAX_SETS)
        }
        sp.edit { putString(KEY_SETS, TimerSetsJson.encode(merged)) }
    }

    fun delete(id: String) {
        val remaining = getAll().filter { it.id != id }
        sp.edit {
            putString(KEY_SETS, TimerSetsJson.encode(remaining))
            if (sp.getString(KEY_ACTIVE, null) == id) remove(KEY_ACTIVE)
        }
    }

    fun setActive(id: String) = sp.edit { putString(KEY_ACTIVE, id) }

    fun activeId(): String? = sp.getString(KEY_ACTIVE, null)

    companion object {
        const val MAX_SETS = 10
        private const val FILE = "timer_sets"
        private const val KEY_SETS = "sets"
        private const val KEY_ACTIVE = "active_id"
    }
}

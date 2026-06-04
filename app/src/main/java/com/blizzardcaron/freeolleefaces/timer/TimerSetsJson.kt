package com.blizzardcaron.freeolleefaces.timer

import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure JSON codec for persisting timer sets. Decoding NEVER throws — malformed/missing input
 * yields an empty list — so corrupt prefs can never crash the UI. A set whose slot count is not
 * exactly 10 is skipped (it would violate the [TimerSet] invariant).
 */
object TimerSetsJson {

    fun encode(sets: List<TimerSet>): String {
        val arr = JSONArray()
        for (set in sets) {
            val slots = JSONArray()
            for (slot in set.slots) {
                slots.put(JSONObject().put("label", slot.label).put("dur", slot.durationSeconds))
            }
            arr.put(JSONObject().put("id", set.id).put("name", set.name).put("slots", slots))
        }
        return arr.toString()
    }

    fun decode(json: String?): List<TimerSet> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                val slotsArr = obj.optJSONArray("slots") ?: return@mapNotNull null
                if (slotsArr.length() != 10) return@mapNotNull null
                val slots = (0 until 10).map { j ->
                    val s = slotsArr.optJSONObject(j) ?: JSONObject()
                    TimerSlot(s.optString("label", ""), s.optInt("dur", 0))
                }
                TimerSet(obj.optString("id"), obj.optString("name", ""), slots)
            }
        }.getOrDefault(emptyList())
    }
}

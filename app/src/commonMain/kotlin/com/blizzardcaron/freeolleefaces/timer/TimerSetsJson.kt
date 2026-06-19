package com.blizzardcaron.freeolleefaces.timer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * Pure JSON codec for persisting timer sets. Decoding NEVER throws — malformed/missing input
 * yields an empty list — so corrupt prefs can never crash the UI. A set whose slot count is not
 * exactly 10 is skipped (it would violate the [TimerSet] invariant).
 */
object TimerSetsJson {

    private val json = Json { ignoreUnknownKeys = true }

    fun encode(sets: List<TimerSet>): String = buildJsonArray {
        for (set in sets) add(
            buildJsonObject {
                put("id", JsonPrimitive(set.id))
                put("name", JsonPrimitive(set.name))
                put(
                    "slots",
                    buildJsonArray {
                        for (slot in set.slots) add(
                            buildJsonObject {
                                put("label", JsonPrimitive(slot.label))
                                put("dur", JsonPrimitive(slot.durationSeconds))
                            }
                        )
                    }
                )
            }
        )
    }.toString()

    fun decode(json: String?): List<TimerSet> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            this.json.parseToJsonElement(json).jsonArray.mapNotNull { el ->
                val obj = el as? JsonObject ?: return@mapNotNull null
                val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: ""
                val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: ""
                val slots = obj["slots"]?.jsonArray?.map { s ->
                    val so = s as? JsonObject ?: JsonObject(emptyMap())
                    val label = so["label"]?.jsonPrimitive?.contentOrNull ?: ""
                    val dur = so["dur"]?.jsonPrimitive?.intOrNull ?: 0
                    TimerSlot(label = label, durationSeconds = dur)
                } ?: return@mapNotNull null
                if (slots.size != 10) return@mapNotNull null
                TimerSet(id = id, name = name, slots = slots)
            }
        }.getOrDefault(emptyList())
    }
}

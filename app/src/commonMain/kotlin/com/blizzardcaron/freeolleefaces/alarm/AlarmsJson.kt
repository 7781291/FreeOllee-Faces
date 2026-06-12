package com.blizzardcaron.freeolleefaces.alarm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * Pure JSON codec for persisting alarms. Decoding NEVER throws — malformed/missing input yields an
 * empty list, and any single entry that violates the [Alarm] invariants is skipped — so corrupt
 * prefs can never crash the UI. Mirrors [com.blizzardcaron.freeolleefaces.timer.TimerSetsJson].
 */
object AlarmsJson {

    private val json = Json { ignoreUnknownKeys = true }

    fun encode(alarms: List<Alarm>): String = buildJsonArray {
        for (a in alarms) add(buildJsonObject {
            put("id", JsonPrimitive(a.id))
            put("hour", JsonPrimitive(a.hour))
            put("minute", JsonPrimitive(a.minute))
            put("enabled", JsonPrimitive(a.enabled))
            put("daysMask", JsonPrimitive(a.daysMask))
            put("chime", JsonPrimitive(a.chimeIndex))
            put("label", JsonPrimitive(a.label))
        })
    }.toString()

    fun decode(raw: String?): List<Alarm> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            json.parseToJsonElement(raw).jsonArray.mapNotNull { el ->
                val obj = el as? JsonObject ?: return@mapNotNull null
                runCatching {
                    Alarm(
                        // No id -> would collide with other entries in save/delete-by-id; skip it.
                        id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                        hour = obj["hour"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null,
                        minute = obj["minute"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null,
                        enabled = obj["enabled"]?.jsonPrimitive?.booleanOrNull ?: true,
                        daysMask = obj["daysMask"]?.jsonPrimitive?.intOrNull ?: Alarm.ALL_DAYS,
                        chimeIndex = obj["chime"]?.jsonPrimitive?.intOrNull ?: 0,
                        label = obj["label"]?.jsonPrimitive?.contentOrNull ?: "",
                    )
                }.getOrNull()   // Alarm init{} threw on a bad range -> skip this entry
            }
        }.getOrDefault(emptyList())
    }
}

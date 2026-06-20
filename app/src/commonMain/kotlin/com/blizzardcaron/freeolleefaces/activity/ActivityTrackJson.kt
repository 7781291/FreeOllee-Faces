package com.blizzardcaron.freeolleefaces.activity

import kotlinx.serialization.json.Json

/** Pure JSON codec for one [ActivityTrack]. Decoding never throws — bad input yields null. */
object ActivityTrackJson {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(track: ActivityTrack): String = json.encodeToString(track)

    fun decode(text: String?): ActivityTrack? {
        if (text.isNullOrBlank()) return null
        return runCatching { json.decodeFromString<ActivityTrack>(text) }.getOrNull()
    }
}

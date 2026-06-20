package com.blizzardcaron.freeolleefaces.activity

import android.content.Context
import java.io.File

/** File-backed track store: one JSON file per activity under `filesDir/activities/`. */
class AndroidActivityTrackStore(context: Context) : ActivityTrackStore {

    private val dir: File = File(context.filesDir, "activities").apply { mkdirs() }

    override fun save(track: ActivityTrack) {
        File(dir, "${track.id}.json").writeText(ActivityTrackJson.encode(track))
    }

    override fun latest(): ActivityTrack? = list().firstOrNull()

    override fun list(): List<ActivityTrack> =
        (dir.listFiles { f -> f.extension == "json" } ?: emptyArray())
            .mapNotNull { ActivityTrackJson.decode(it.readText()) }
            .sortedByDescending { it.startedAtMs }
}

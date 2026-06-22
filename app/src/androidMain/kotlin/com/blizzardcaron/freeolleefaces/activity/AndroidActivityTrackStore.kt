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

    override fun delete(id: String) {
        File(dir, "$id.json").delete()
    }

    override fun prune(endedBeforeMs: Long): Int =
        list().count { track ->
            val ended = track.endedAtMs
            if (ended != null && ended < endedBeforeMs) {
                delete(track.id)
                true
            } else {
                false
            }
        }
}

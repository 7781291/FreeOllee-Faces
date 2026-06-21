package com.blizzardcaron.freeolleefaces.activity

/** Persists recorded activity tracks. Platform impl owns the file plumbing. */
interface ActivityTrackStore {
    /** Insert or replace by [ActivityTrack.id]. */
    fun save(track: ActivityTrack)

    /** The most recently started track, or null if none recorded. */
    fun latest(): ActivityTrack?

    /** All tracks, newest first — foundation for the future Strava/GPX exporter. */
    fun list(): List<ActivityTrack>
}

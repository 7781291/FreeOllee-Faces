package com.blizzardcaron.freeolleefaces.activity

import com.blizzardcaron.freeolleefaces.prefs.Prefs

/** Restores state left dangling by a session that died without a clean stop. */
object ActivityRecovery {

    suspend fun recoverIfStranded(
        prefs: Prefs,
        store: ActivityTrackStore,
        autoSleep: SessionAutoSleep,
        watchAddress: String?,
        sessionRunning: Boolean,
    ): Boolean {
        if (!prefs.activityActive || sessionRunning) return false
        store.latest()?.let { track ->
            if (track.endedAtMs == null) {
                store.save(
                    track.copy(
                        endedAbnormally = true,
                        endedAtMs = track.points.lastOrNull()?.tMs ?: track.startedAtMs,
                    ),
                )
            }
        }
        if (watchAddress != null) {
            autoSleep.restoreAfterActivity(watchAddress) // clears the breadcrumb on success
        } else {
            prefs.activityActive = false
        }
        return true
    }
}

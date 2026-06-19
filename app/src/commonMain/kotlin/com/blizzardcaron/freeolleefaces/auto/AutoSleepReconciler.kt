package com.blizzardcaron.freeolleefaces.auto

import com.blizzardcaron.freeolleefaces.prefs.AutoSleepProfile
import com.blizzardcaron.freeolleefaces.prefs.AutoSleepWindowConfig

/** Pure desired-state mapping for the auto-sleep schedule. No I/O — fully unit-testable. */
object AutoSleepReconciler {

    /**
     * The auto-sleep profile the watch should hold at [nowMinuteOfDay] (0..1439, local), or null
     * when the feature is disabled (caller must then leave the watch untouched). Window membership
     * reuses [AutoUpdateSchedule.isInSleepWindow] (inclusive start, exclusive end, wraps midnight).
     */
    fun desiredProfile(config: AutoSleepWindowConfig, nowMinuteOfDay: Int): AutoSleepProfile? {
        if (!config.enabled) return null
        return if (AutoUpdateSchedule.isInSleepWindow(nowMinuteOfDay, config.startMin, config.endMin)) {
            config.inWindow
        } else {
            config.outWindow
        }
    }
}

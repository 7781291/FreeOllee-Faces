package com.blizzardcaron.freeolleefaces.timer

/**
 * One of the watch's 10 timer slots. [label] is phone-side only (never sent to the watch);
 * [durationSeconds] is the countdown length in seconds (0 = blank/unused).
 */
data class TimerSlot(val label: String = "", val durationSeconds: Int = 0)

/** A named set of exactly 10 timer slots, stored on the phone. */
data class TimerSet(val id: String, val name: String, val slots: List<TimerSlot>) {

    init { require(slots.size == 10) { "a timer set has exactly 10 slots (got ${slots.size})" } }

    /** The 10 durations in slot order — the payload pushed to the watch. */
    fun durations(): List<Int> = slots.map { it.durationSeconds }

    companion object {
        /** A set of 10 blank slots. */
        fun blank(id: String, name: String): TimerSet =
            TimerSet(id, name, List(10) { TimerSlot() })
    }
}

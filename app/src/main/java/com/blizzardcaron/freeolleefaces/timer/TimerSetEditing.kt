package com.blizzardcaron.freeolleefaces.timer

/** Pure editor transforms for the Timer-set editor — no Android/UI dependencies. */
object TimerSetEditing {

    /**
     * Copy `slots[fromIndex].durationSeconds` into every slot below it; labels untouched.
     * The fast path for interval mode (fill 6 slots with one duration). Returns a new list.
     */
    fun fillDown(slots: List<TimerSlot>, fromIndex: Int): List<TimerSlot> {
        val d = slots[fromIndex].durationSeconds
        return slots.mapIndexed { i, slot ->
            if (i > fromIndex) slot.copy(durationSeconds = d) else slot
        }
    }

    /** Copy `slots[index]` (label + duration) into slot `index+1`; no-op if `index` is last. */
    fun duplicateToNext(slots: List<TimerSlot>, index: Int): List<TimerSlot> {
        if (index >= slots.lastIndex) return slots
        return slots.mapIndexed { i, slot -> if (i == index + 1) slots[index] else slot }
    }

    /** Combine an H:M:S triple into seconds (negatives clamped to 0). */
    fun hmsToSeconds(h: Int, m: Int, s: Int): Int =
        (h.coerceAtLeast(0) * 3600) + (m.coerceAtLeast(0) * 60) + s.coerceAtLeast(0)

    /** Split a seconds total into (hours, minutes, seconds). */
    fun secondsToHms(total: Int): Triple<Int, Int, Int> {
        val t = total.coerceAtLeast(0)
        return Triple(t / 3600, (t % 3600) / 60, t % 60)
    }

    /** "HH:MM:SS" for display. */
    fun formatHms(total: Int): String {
        val (h, m, s) = secondsToHms(total)
        return "%02d:%02d:%02d".format(h, m, s)
    }
}

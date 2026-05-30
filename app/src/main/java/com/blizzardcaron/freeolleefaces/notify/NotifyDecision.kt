package com.blizzardcaron.freeolleefaces.notify

/** A background failure worth notifying about. Null elsewhere means "healthy". */
enum class FailureKind { WATCH_UNREACHABLE, WEATHER_FETCH_FAILED, SETUP_INCOMPLETE, SUN_UNREACHABLE }

/** What to do with the single error notification after one worker outcome. */
sealed interface NotifyAction {
    data class Notify(val kind: FailureKind) : NotifyAction
    data object Clear : NotifyAction
    data object Nothing : NotifyAction
}

object NotifyDecision {
    /**
     * Transition-only firing with auto-clear:
     *  - success clears any showing notification, else nothing;
     *  - a failure during the sleep window is suppressed (state unchanged);
     *  - a new or changed failure notifies once; the same failure persisting does nothing.
     */
    fun decide(current: FailureKind?, lastNotified: FailureKind?, inSleep: Boolean): NotifyAction {
        if (current == null) {
            return if (lastNotified != null) NotifyAction.Clear else NotifyAction.Nothing
        }
        if (inSleep) return NotifyAction.Nothing
        return if (lastNotified != current) NotifyAction.Notify(current) else NotifyAction.Nothing
    }
}

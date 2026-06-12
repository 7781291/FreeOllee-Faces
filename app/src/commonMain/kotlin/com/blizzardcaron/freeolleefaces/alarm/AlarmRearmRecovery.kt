package com.blizzardcaron.freeolleefaces.alarm

import com.blizzardcaron.freeolleefaces.auto.AutoUpdateSchedule

/**
 * Pure policy for what the re-arm engine does after each watch push attempt. A failed push is
 * NOT eventually consistent: the engine's next scheduled pass runs only after the next alarm
 * should already have fired, so without a backstop a single failed push silently skips that
 * alarm. Mirrors the auto-update chain's backstop (same budget and 2/5/15-minute backoff via
 * [AutoUpdateSchedule]) with a terminal notification once the budget is spent.
 */
object AlarmRearmRecovery {

    sealed interface Action {
        /** Push landed: cancel any pending backstop retry and clear the failure notification. */
        data object ClearFailure : Action

        /** Push failed with budget left: run another re-arm pass after [delayMs]. */
        data class ScheduleRetry(val delayMs: Long, val nextAttempt: Int) : Action

        /** Push failed with the budget spent: surface the failure notification. */
        data object NotifyFailure : Action
    }

    /** [attempt] is the 0-based index of the push that just completed. */
    fun afterPush(pushSucceeded: Boolean, attempt: Int): Action = when {
        pushSucceeded -> Action.ClearFailure
        AutoUpdateSchedule.hasBackstopBudget(attempt) ->
            Action.ScheduleRetry(AutoUpdateSchedule.backstopDelayMs(attempt), attempt + 1)
        else -> Action.NotifyFailure
    }
}

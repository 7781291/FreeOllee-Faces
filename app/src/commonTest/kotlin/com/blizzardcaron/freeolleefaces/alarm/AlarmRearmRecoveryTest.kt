package com.blizzardcaron.freeolleefaces.alarm

import com.blizzardcaron.freeolleefaces.alarm.AlarmRearmRecovery.Action
import kotlin.test.Test
import kotlin.test.assertEquals

class AlarmRearmRecoveryTest {

    @Test fun successClearsFailureStateAtAnyAttempt() {
        assertEquals(Action.ClearFailure, AlarmRearmRecovery.afterPush(pushSucceeded = true, attempt = 0))
        assertEquals(Action.ClearFailure, AlarmRearmRecovery.afterPush(pushSucceeded = true, attempt = 3))
    }

    @Test fun failuresRetryWithEscalatingBackoff() {
        // Same 2 → 5 → 15 minute ladder as the auto-update backstop.
        assertEquals(
            Action.ScheduleRetry(delayMs = 2 * 60_000L, nextAttempt = 1),
            AlarmRearmRecovery.afterPush(pushSucceeded = false, attempt = 0),
        )
        assertEquals(
            Action.ScheduleRetry(delayMs = 5 * 60_000L, nextAttempt = 2),
            AlarmRearmRecovery.afterPush(pushSucceeded = false, attempt = 1),
        )
        assertEquals(
            Action.ScheduleRetry(delayMs = 15 * 60_000L, nextAttempt = 3),
            AlarmRearmRecovery.afterPush(pushSucceeded = false, attempt = 2),
        )
    }

    @Test fun fourthConsecutiveFailureNotifies() {
        assertEquals(Action.NotifyFailure, AlarmRearmRecovery.afterPush(pushSucceeded = false, attempt = 3))
    }
}

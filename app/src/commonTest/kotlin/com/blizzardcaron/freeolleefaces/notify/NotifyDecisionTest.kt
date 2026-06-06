package com.blizzardcaron.freeolleefaces.notify

import kotlin.test.Test
import kotlin.test.assertEquals

class NotifyDecisionTest {

    private val watch = FailureKind.WATCH_UNREACHABLE
    private val weather = FailureKind.WEATHER_FETCH_FAILED

    @Test fun healthyToFailureNotifies() {
        assertEquals(NotifyAction.Notify(watch), NotifyDecision.decide(watch, null, inSleep = false))
    }

    @Test fun sameFailurePersistsDoesNothing() {
        assertEquals(NotifyAction.Nothing, NotifyDecision.decide(watch, watch, inSleep = false))
    }

    @Test fun changedFailureKindNotifies() {
        assertEquals(NotifyAction.Notify(weather), NotifyDecision.decide(weather, watch, inSleep = false))
    }

    @Test fun failureToSuccessClears() {
        assertEquals(NotifyAction.Clear, NotifyDecision.decide(null, watch, inSleep = false))
    }

    @Test fun successToSuccessDoesNothing() {
        assertEquals(NotifyAction.Nothing, NotifyDecision.decide(null, null, inSleep = false))
    }

    @Test fun failureWhileAsleepDoesNothing() {
        assertEquals(NotifyAction.Nothing, NotifyDecision.decide(watch, null, inSleep = true))
    }

    @Test fun recoveryWhileAsleepStillClears() {
        assertEquals(NotifyAction.Clear, NotifyDecision.decide(null, watch, inSleep = true))
    }
}

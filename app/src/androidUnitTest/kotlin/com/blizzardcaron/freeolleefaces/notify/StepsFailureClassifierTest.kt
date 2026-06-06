package com.blizzardcaron.freeolleefaces.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

class StepsFailureClassifierTest {

    @Test fun permissionDenialMapsToHealthUnavailable() {
        // Covers both our own READ_STEPS pre-check and Health Connect's background-read denial.
        assertEquals(
            FailureKind.HEALTH_UNAVAILABLE,
            StepsFailureClassifier.kindFor(SecurityException("background read not allowed")),
        )
    }

    @Test fun unavailableMapsToHealthUnavailable() {
        assertEquals(
            FailureKind.HEALTH_UNAVAILABLE,
            StepsFailureClassifier.kindFor(IllegalStateException("Health Connect unavailable")),
        )
    }

    @Test fun transientReadErrorsAreNotNotified() {
        // A momentary IPC/transport hiccup with access intact must not nag "grant access".
        assertNull(StepsFailureClassifier.kindFor(IOException("transport closed")))
        assertNull(StepsFailureClassifier.kindFor(RuntimeException("unexpected")))
    }
}

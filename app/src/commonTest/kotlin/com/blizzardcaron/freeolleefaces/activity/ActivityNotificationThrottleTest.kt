package com.blizzardcaron.freeolleefaces.activity

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ActivityNotificationThrottleTest {

    @Test fun postsWhenIntervalElapsedAndTextChanged() {
        assertTrue(
            ActivityNotificationThrottle.shouldPost(
                nowMs = ActivityNotificationThrottle.MIN_INTERVAL_MS,
                lastPostMs = 0L,
                newText = "pace 2",
                lastText = "pace 1",
            ),
        )
    }

    @Test fun suppressesWithinCooldown() {
        assertFalse(
            ActivityNotificationThrottle.shouldPost(
                nowMs = ActivityNotificationThrottle.MIN_INTERVAL_MS - 1,
                lastPostMs = 0L,
                newText = "pace 2",
                lastText = "pace 1",
            ),
        )
    }

    @Test fun suppressesIdenticalTextEvenAfterCooldown() {
        assertFalse(
            ActivityNotificationThrottle.shouldPost(
                nowMs = 100_000L,
                lastPostMs = 0L,
                newText = "pace 1",
                lastText = "pace 1",
            ),
        )
    }

    @Test fun postsFirstTimeWhenNoPriorText() {
        assertTrue(
            ActivityNotificationThrottle.shouldPost(
                nowMs = ActivityNotificationThrottle.MIN_INTERVAL_MS,
                lastPostMs = 0L,
                newText = "starting",
                lastText = null,
            ),
        )
    }
}

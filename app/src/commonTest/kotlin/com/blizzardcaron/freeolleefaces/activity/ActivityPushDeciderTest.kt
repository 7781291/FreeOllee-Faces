package com.blizzardcaron.freeolleefaces.activity

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ActivityPushDeciderTest {

    @Test fun first_push_always_sends() {
        assertTrue(ActivityPushDecider.shouldPush(null, "P 8:30", 0L, forced = false))
    }

    @Test fun changed_text_sends() {
        assertTrue(ActivityPushDecider.shouldPush("P 8:31", "P 8:30", 500L, forced = false))
    }

    @Test fun unchanged_text_within_heartbeat_does_not_send() {
        assertFalse(ActivityPushDecider.shouldPush("P 8:30", "P 8:30", 1_000L, forced = false))
    }

    @Test fun unchanged_text_past_heartbeat_sends() {
        assertTrue(ActivityPushDecider.shouldPush("P 8:30", "P 8:30", 3_000L, forced = false))
    }

    @Test fun forced_always_sends() {
        assertTrue(ActivityPushDecider.shouldPush("P 8:30", "P 8:30", 0L, forced = true))
    }
}

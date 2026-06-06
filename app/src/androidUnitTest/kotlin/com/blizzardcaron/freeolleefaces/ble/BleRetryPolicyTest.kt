package com.blizzardcaron.freeolleefaces.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BleRetryPolicyTest {

    @Test
    fun `allows three attempts`() {
        assertEquals(3, BleRetryPolicy.MAX_ATTEMPTS)
    }

    @Test
    fun `backoff is 2s before the first retry and 4s before the second`() {
        assertEquals(2_000L, BleRetryPolicy.backoffForAttempt(0))
        assertEquals(4_000L, BleRetryPolicy.backoffForAttempt(1))
    }

    @Test
    fun `backoff reuses the last value past the defined schedule`() {
        assertEquals(4_000L, BleRetryPolicy.backoffForAttempt(2))
    }

    @Test
    fun `every BLE failure is treated as retryable`() {
        assertTrue(BleRetryPolicy.isRetryable(IllegalStateException("write failed: 13")))
        assertTrue(BleRetryPolicy.isRetryable(RuntimeException("connect timed out")))
    }
}

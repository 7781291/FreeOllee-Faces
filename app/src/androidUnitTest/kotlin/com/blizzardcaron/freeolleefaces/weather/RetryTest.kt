package com.blizzardcaron.freeolleefaces.weather

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RetryTest {

    // A RuntimeException is "transient" unless it's an IllegalArgumentException (treated permanent).
    private val isTransient: (Throwable) -> Boolean =
        { it is RuntimeException && it !is IllegalArgumentException }

    @Test
    fun `retries transient failures up to maxAttempts then fails`() = runBlocking {
        var calls = 0
        val policy = RetryPolicy(maxAttempts = 3, backoffMs = listOf(1, 2))
        val result = withRetry(policy, isTransient, delayFn = {}) {
            calls++
            throw RuntimeException("transient")
        }
        assertTrue(result.isFailure)
        assertEquals(3, calls)
    }

    @Test
    fun `stops immediately on a permanent failure`() = runBlocking {
        var calls = 0
        val policy = RetryPolicy(maxAttempts = 3, backoffMs = listOf(1, 2))
        val result = withRetry(policy, isTransient, delayFn = {}) {
            calls++
            throw IllegalArgumentException("permanent")
        }
        assertTrue(result.isFailure)
        assertEquals(1, calls)
    }

    @Test
    fun `returns success and stops once the block succeeds`() = runBlocking {
        var calls = 0
        val policy = RetryPolicy(maxAttempts = 3, backoffMs = listOf(1, 2))
        val result = withRetry(policy, isTransient, delayFn = {}) {
            calls++
            if (calls < 2) throw RuntimeException("transient")
            42
        }
        assertEquals(42, result.getOrNull())
        assertEquals(2, calls)
    }

    @Test
    fun `requests backoff values in order between transient retries`() = runBlocking {
        val waits = mutableListOf<Long>()
        val policy = RetryPolicy(maxAttempts = 3, backoffMs = listOf(1000, 2000))
        withRetry(policy, isTransient, delayFn = { waits.add(it) }) {
            throw RuntimeException("transient")
        }
        assertEquals(listOf(1000L, 2000L), waits)
    }

    @Test
    fun `Preview preset is two attempts with no backoff`() {
        assertEquals(2, RetryPolicy.Preview.maxAttempts)
        assertTrue(RetryPolicy.Preview.backoffMs.isEmpty())
    }

    @Test
    fun `Background preset is three attempts with 1s then 2s backoff`() {
        assertEquals(3, RetryPolicy.Background.maxAttempts)
        assertEquals(listOf(1000L, 2000L), RetryPolicy.Background.backoffMs)
    }
}

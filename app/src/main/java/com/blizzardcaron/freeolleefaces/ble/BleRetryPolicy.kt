package com.blizzardcaron.freeolleefaces.ble

/**
 * Pure Layer-1 retry policy for a single watch send. A cold connect to a sleeping/slow-advertising
 * watch often times out on the first try but succeeds seconds later, so [OlleeBleClient] reconnects
 * up to [MAX_ATTEMPTS] times with [backoffForAttempt] waits between tries. The GATT calls are not
 * unit-testable, so this policy is split out and tested on its own.
 */
object BleRetryPolicy {

    /** Total connect+write attempts before surfacing failure. */
    const val MAX_ATTEMPTS = 3

    /** Wait before the retry that follows the (0-based) failed [attempt]: 2s, then 4s. */
    private val BACKOFF_MS = listOf(2_000L, 4_000L)

    fun backoffForAttempt(attempt: Int): Long =
        BACKOFF_MS.getOrElse(attempt) { BACKOFF_MS.lastOrNull() ?: 0L }

    /**
     * Every connect/discover/write/timeout failure is retryable: a couple of extra cheap attempts
     * is preferable to a false "watch unreachable". (Kept as a predicate for symmetry with
     * `weather/Retry.kt` and to localize any future non-retryable cases.)
     */
    @Suppress("UNUSED_PARAMETER")
    fun isRetryable(error: Throwable): Boolean = true
}

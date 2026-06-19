package com.blizzardcaron.freeolleefaces.weather

/**
 * Retry configuration. [backoffMs] holds the wait before each retry; if it is shorter than the
 * number of retries the last value is reused, and an empty list means retry immediately.
 */
data class RetryPolicy(val maxAttempts: Int, val backoffMs: List<Long>) {
    companion object {
        /** Live preview: one immediate retry, fail fast. */
        val Preview = RetryPolicy(maxAttempts = 2, backoffMs = emptyList())

        /** Background worker: patient, exponential 1s then 2s. */
        val Background = RetryPolicy(maxAttempts = 3, backoffMs = listOf(1000L, 2000L))
    }
}

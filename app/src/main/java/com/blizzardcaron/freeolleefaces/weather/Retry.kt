package com.blizzardcaron.freeolleefaces.weather

import kotlinx.coroutines.delay

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

/**
 * Runs [block] up to [RetryPolicy.maxAttempts] times. Retries only when [isTransient] returns true
 * for the thrown error; a permanent error short-circuits. [delayFn] is injectable so tests run
 * without real delays.
 */
suspend fun <T> withRetry(
    policy: RetryPolicy,
    isTransient: (Throwable) -> Boolean,
    delayFn: suspend (Long) -> Unit = { delay(it) },
    block: suspend () -> T,
): Result<T> {
    var lastError: Throwable? = null
    repeat(policy.maxAttempts) { attempt ->
        val result = runCatching { block() }
        if (result.isSuccess) return result
        val error = result.exceptionOrNull()!!
        lastError = error
        val isLastAttempt = attempt == policy.maxAttempts - 1
        if (isLastAttempt || !isTransient(error)) return Result.failure(error)
        val waitMs = policy.backoffMs.getOrElse(attempt) { policy.backoffMs.lastOrNull() ?: 0L }
        if (waitMs > 0) delayFn(waitMs)
    }
    return Result.failure(lastError!!)
}

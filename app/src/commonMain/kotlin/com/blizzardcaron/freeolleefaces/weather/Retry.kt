package com.blizzardcaron.freeolleefaces.weather

import kotlinx.coroutines.delay

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
    for (attempt in 0 until policy.maxAttempts) {
        val result = runCatching { block() }
        if (result.isSuccess) return result
        val error = result.exceptionOrNull()!!
        lastError = error
        val isLastAttempt = attempt == policy.maxAttempts - 1
        if (isLastAttempt || !isTransient(error)) break
        val waitMs = policy.backoffMs.getOrElse(attempt) { policy.backoffMs.lastOrNull() ?: 0L }
        if (waitMs > 0) delayFn(waitMs)
    }
    return Result.failure(lastError!!)
}

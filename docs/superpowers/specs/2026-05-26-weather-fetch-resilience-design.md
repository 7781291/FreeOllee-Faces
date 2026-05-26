# Weather Fetch Resilience Design

**Date:** 2026-05-26
**Status:** Approved for planning

## Background

Open-Meteo (`api.open-meteo.com/v1/forecast`) suffers intermittent outages — confirmed
upstream incident <https://github.com/open-meteo/open-meteo/issues/1870>. A live check on
2026-05-26 returned HTTP 502/504 on every request, with 5–13s response latency before the
error came back.

The app handles these failures poorly today:

- **No retry anywhere.** A single transient blip loses the whole fetch. The background worker
  then waits a full `tempIntervalMinutes` before trying again.
- **Raw nginx HTML leaks into the UI.** `OpenMeteoClient` does
  `error("HTTP $code from Open-Meteo: $errBody")`, and the live-preview card interpolates
  `err.message` directly, so the user sees `...<html><head><title>502 Bad Gateway</title>...`.
- **No transient-vs-permanent distinction.** A 4xx (our bug) and a 5xx (their outage) are
  handled identically.

## Goals

1. Retry transient failures, with a retry budget appropriate to each call site.
2. Surface clean, classified error copy instead of leaking the upstream HTML body.
3. Distinguish transient (retry-worthy) from permanent (give-up) failures.

## Non-goals

- Changing the background worker's self-scheduling design (it self-enqueues the next fire and
  returns `Result.success()`; after exhausting retries it logs and waits for the next interval).
- Changing the 8s connect/read timeouts.
- Adding a caching/last-known-good fallback layer (possible future work, out of scope here).

## Design decisions (settled during brainstorming)

| Decision | Choice |
|----------|--------|
| Retry budget | Lean preview, patient worker |
| Preview retry | 2 attempts total (1 retry), no backoff |
| Worker retry | 3 attempts total, exponential backoff 1s → 2s |
| Per-attempt timeout | Unchanged (8s connect + 8s read) for both |
| Preview error copy | Cause + status code; transient vs permanent buckets |
| Structure | Approach A — typed errors + generic retry helper |
| Test dependency | None added; retry delay is injectable |

Note on preview timing: with the 8s timeout retained, the preview's 2 attempts can take up to
~16s during a full outage before showing the error. Accepted as a tradeoff to avoid adding a
timeout parameter.

## Components

### `weather/WeatherFetchError.kt` (new)

A sealed exception that replaces the current generic `error(...)` string. Each variant declares
whether it is transient (retry-worthy).

| Variant | `isTransient` | Carries | Cause |
|---------|---------------|---------|-------|
| `ServerError(statusCode)` | `true` | status code | HTTP 5xx |
| `Timeout` | `true` | — | `SocketTimeoutException` |
| `Network(detail)` | `true` | — | other `IOException` |
| `ClientError(statusCode)` | `false` | status code | HTTP 4xx (and any other non-2xx) |
| `Malformed(detail)` | `false` | — | invalid/incomplete JSON |

Members:

- `abstract val isTransient: Boolean`
- `val statusCode: Int?` — returns the code for `ServerError`/`ClientError`, else `null`
  (used for logging).
- `companion object fun fromHttpStatus(code: Int): WeatherFetchError?` —
  `2xx → null`, `5xx → ServerError(code)`, everything else → `ClientError(code)`.

The exception `message` carries a developer-facing string (e.g. `"Open-Meteo returned HTTP 502"`).
The user-facing copy is produced separately (see `WeatherErrorCopy`), so the raw upstream body is
never shown.

### `weather/Retry.kt` (new)

```kotlin
data class RetryPolicy(val maxAttempts: Int, val backoffMs: List<Long>) {
    companion object {
        val Preview = RetryPolicy(maxAttempts = 2, backoffMs = emptyList())        // 1 retry, immediate
        val Background = RetryPolicy(maxAttempts = 3, backoffMs = listOf(1000, 2000)) // 1s, 2s
    }
}

suspend fun <T> withRetry(
    policy: RetryPolicy,
    isTransient: (Throwable) -> Boolean,
    delayFn: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
    block: suspend () -> T,
): Result<T>
```

Behavior:

- Runs `block` up to `policy.maxAttempts` times.
- On success, returns immediately.
- On failure: if it is the last attempt **or** `isTransient(error)` is false, return the failure.
  Otherwise wait `backoffMs[attempt]` (using the last entry if the list is shorter, 0 if empty)
  via `delayFn`, then retry.
- `delayFn` defaults to the real `delay`; tests inject a no-op (or recording) function so they run
  instantly under `runBlocking` with no `kotlinx-coroutines-test` dependency.

### `weather/OpenMeteoClient.kt` (modified)

- `currentTemp` gains a `policy: RetryPolicy` parameter (no default required; both call sites pass
  one explicitly).
- The existing single-attempt body is extracted into a private `suspend fun fetchOnce(...)` that
  throws typed `WeatherFetchError`s:
  - non-2xx → `throw WeatherFetchError.fromHttpStatus(code)!!` (the error body is still read for the
    dev-facing message but never surfaced to the user)
  - `SocketTimeoutException` → `Timeout`
  - other `IOException` → `Network(message)`
- The parser (`parseCurrentTemperatureF`) throws `WeatherFetchError.Malformed` instead of
  `IllegalStateException`.
- `currentTemp` wraps `fetchOnce` in `withRetry(policy, isTransient = { it is WeatherFetchError && it.isTransient })`
  and returns its `Result<Double>`.

### `format/WeatherErrorCopy.kt` (new)

An object `WeatherErrorCopy` with `fun describe(error: Throwable): String`, mapping a failure to
user-facing copy (kept in the UI/format layer so no English strings live in the weather client).
It accepts `Throwable` because the call sites receive `Result.exceptionOrNull()`; non-`WeatherFetchError`
values fall through to the generic copy below:

| Error | Copy |
|-------|------|
| `ServerError(code)` | `"Weather service unavailable ($code) — try again shortly"` |
| `Timeout` | `"Weather service timed out — try again shortly"` |
| `Network` | `"No connection to weather service — try again shortly"` |
| `ClientError(code)` | `"Couldn't read weather ($code)"` |
| `Malformed` | `"Couldn't read weather (bad data)"` |
| other `Throwable` | `"Couldn't fetch weather"` |

### `MainActivity.kt` (modified, ~line 99)

- Calls `currentTemp(lat, lng, unit, RetryPolicy.Preview)`.
- `onFailure` sets `tempPreview = PreviewState.Error(WeatherErrorCopy.describe(err))` instead of
  interpolating `err.message`.

### `auto/AutoUpdateWorker.kt` (modified, ~line 65)

- Calls `currentTemp(lat, lng, prefs.tempUnit, RetryPolicy.Background)`.
- `onFailure` records `"Skipped: weather fetch failed"` plus the code when present
  (`err.statusCode?.let { " (HTTP $it)" }`).
- The surrounding flow (enqueue next fire, return `Result.success()`) is unchanged.

## Data flow

```
caller → currentTemp(policy) → withRetry(policy) ──loop──► fetchOnce()
                                     │                         │ success → Double
                                     │                         │ failure → WeatherFetchError
                                     │  isTransient && attempts left? → backoff via delayFn → retry
                                     ▼
                              Result<Double>
   success → format temperature for display/send
   failure → preview: WeatherErrorCopy.describe(err)
             worker:  "Skipped: weather fetch failed (HTTP nnn)"
```

## Testing

No new dependencies (delay is injected; suspend functions tested under `runBlocking`).

- **`RetryTest`** (new):
  - transient failures retry up to `maxAttempts` then return failure;
  - a permanent failure short-circuits after one attempt;
  - a block that succeeds on attempt N returns success and stops;
  - backoff values are requested in order (assert via a recording `delayFn`).
- **`WeatherFetchErrorTest`** (new): `fromHttpStatus` mapping (2xx/4xx/5xx), `isTransient` per
  variant, `statusCode` accessor.
- **`WeatherErrorCopyTest`** (new): each variant → expected string.
- **`OpenMeteoClientParserTest`** (update): expect `WeatherFetchError.Malformed` for invalid /
  incomplete JSON instead of `IllegalStateException`.
- **`OpenMeteoClientUrlTest`** (unchanged): `buildUrl` signature is untouched.

The HTTP wiring in `fetchOnce`/`currentTemp` is not unit-tested (no embedded server), consistent
with the current state.

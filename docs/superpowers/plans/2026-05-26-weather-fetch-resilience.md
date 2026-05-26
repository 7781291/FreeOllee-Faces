# Weather Fetch Resilience Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Retry transient Open-Meteo failures with per-call-site retry budgets and replace the leaked nginx HTML error with classified, user-facing copy.

**Architecture:** Introduce a sealed `WeatherFetchError` type (transient vs permanent), a generic `withRetry` helper with injectable delay, and two `RetryPolicy` presets. `OpenMeteoClient.currentTemp` wraps a single-attempt fetch in `withRetry`; the live preview uses a lean policy, the background worker a patient one. User-facing copy is produced by a pure `WeatherErrorCopy` mapper in the format layer.

**Tech Stack:** Kotlin, kotlinx-coroutines (already an `implementation` dep, available to unit tests), JUnit 4, org.json. No new dependencies.

**Spec:** `docs/superpowers/specs/2026-05-26-weather-fetch-resilience-design.md`

---

## File Structure

- Create: `app/src/main/java/com/blizzardcaron/freeolleefaces/weather/WeatherFetchError.kt` — sealed error type + classification.
- Create: `app/src/main/java/com/blizzardcaron/freeolleefaces/weather/Retry.kt` — `RetryPolicy` + generic `withRetry`.
- Create: `app/src/main/java/com/blizzardcaron/freeolleefaces/format/WeatherErrorCopy.kt` — error → user-facing string.
- Modify: `app/src/main/java/com/blizzardcaron/freeolleefaces/weather/OpenMeteoClient.kt` — typed errors, `fetchOnce`, retry wrapping, `policy` param.
- Modify: `app/src/main/java/com/blizzardcaron/freeolleefaces/MainActivity.kt:99` — preview call site + copy.
- Modify: `app/src/main/java/com/blizzardcaron/freeolleefaces/auto/AutoUpdateWorker.kt:65` — worker call site + log.
- Create test: `app/src/test/java/com/blizzardcaron/freeolleefaces/weather/WeatherFetchErrorTest.kt`
- Create test: `app/src/test/java/com/blizzardcaron/freeolleefaces/weather/RetryTest.kt`
- Create test: `app/src/test/java/com/blizzardcaron/freeolleefaces/format/WeatherErrorCopyTest.kt`
- Modify test: `app/src/test/java/com/blizzardcaron/freeolleefaces/weather/OpenMeteoClientParserTest.kt` — expect `Malformed`.

Tasks must be executed in order (1 → 2 → 3 → 4 → 5). Task 4 changes the `currentTemp` signature and both call sites together so every commit compiles.

---

## Task 1: WeatherFetchError sealed type

**Files:**
- Create: `app/src/main/java/com/blizzardcaron/freeolleefaces/weather/WeatherFetchError.kt`
- Test: `app/src/test/java/com/blizzardcaron/freeolleefaces/weather/WeatherFetchErrorTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/blizzardcaron/freeolleefaces/weather/WeatherFetchErrorTest.kt`:

```kotlin
package com.blizzardcaron.freeolleefaces.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherFetchErrorTest {

    @Test
    fun `fromHttpStatus returns null for 2xx`() {
        assertNull(WeatherFetchError.fromHttpStatus(200))
        assertNull(WeatherFetchError.fromHttpStatus(204))
    }

    @Test
    fun `fromHttpStatus maps 5xx to a transient ServerError`() {
        val e = WeatherFetchError.fromHttpStatus(502)
        assertTrue(e is WeatherFetchError.ServerError)
        assertEquals(502, e!!.statusCode)
        assertTrue(e.isTransient)
    }

    @Test
    fun `fromHttpStatus maps 4xx to a permanent ClientError`() {
        val e = WeatherFetchError.fromHttpStatus(400)
        assertTrue(e is WeatherFetchError.ClientError)
        assertEquals(400, e!!.statusCode)
        assertFalse(e.isTransient)
    }

    @Test
    fun `Timeout and Network are transient with no status code`() {
        assertTrue(WeatherFetchError.Timeout.isTransient)
        assertNull(WeatherFetchError.Timeout.statusCode)
        assertTrue(WeatherFetchError.Network("conn reset").isTransient)
        assertNull(WeatherFetchError.Network("conn reset").statusCode)
    }

    @Test
    fun `Malformed is permanent`() {
        assertFalse(WeatherFetchError.Malformed("no current block").isTransient)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.blizzardcaron.freeolleefaces.weather.WeatherFetchErrorTest"`
Expected: FAIL — compilation error, `WeatherFetchError` is unresolved.

- [ ] **Step 3: Write minimal implementation**

Create `app/src/main/java/com/blizzardcaron/freeolleefaces/weather/WeatherFetchError.kt`:

```kotlin
package com.blizzardcaron.freeolleefaces.weather

/** Typed failures from a weather fetch, classified as transient (retry-worthy) or permanent. */
sealed class WeatherFetchError(message: String) : Exception(message) {

    abstract val isTransient: Boolean

    /** HTTP status code when the failure carries one, else null. Used for logging. */
    open val statusCode: Int? = null

    /** HTTP 5xx — Open-Meteo is up but degraded. */
    data class ServerError(override val statusCode: Int) :
        WeatherFetchError("Open-Meteo returned HTTP $statusCode") {
        override val isTransient = true
    }

    /** Connect or read timed out. */
    object Timeout : WeatherFetchError("Open-Meteo request timed out") {
        override val isTransient = true
    }

    /** Other network-level failure (no route, connection reset, etc.). */
    data class Network(val detail: String) :
        WeatherFetchError("Network error contacting Open-Meteo: $detail") {
        override val isTransient = true
    }

    /** HTTP 4xx (or any other non-2xx) — our request is wrong; retrying won't help. */
    data class ClientError(override val statusCode: Int) :
        WeatherFetchError("Open-Meteo rejected request: HTTP $statusCode") {
        override val isTransient = false
    }

    /** Response body was not the JSON we expected. */
    data class Malformed(val detail: String) :
        WeatherFetchError("Malformed Open-Meteo response: $detail") {
        override val isTransient = false
    }

    companion object {
        /** Maps an HTTP status to an error, or null for 2xx. */
        fun fromHttpStatus(code: Int): WeatherFetchError? = when {
            code in 200..299 -> null
            code in 500..599 -> ServerError(code)
            else -> ClientError(code)
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.blizzardcaron.freeolleefaces.weather.WeatherFetchErrorTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/blizzardcaron/freeolleefaces/weather/WeatherFetchError.kt \
        app/src/test/java/com/blizzardcaron/freeolleefaces/weather/WeatherFetchErrorTest.kt
git commit -m "Add typed WeatherFetchError with transient/permanent classification"
```

---

## Task 2: RetryPolicy and withRetry helper

**Files:**
- Create: `app/src/main/java/com/blizzardcaron/freeolleefaces/weather/Retry.kt`
- Test: `app/src/test/java/com/blizzardcaron/freeolleefaces/weather/RetryTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/blizzardcaron/freeolleefaces/weather/RetryTest.kt`:

```kotlin
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.blizzardcaron.freeolleefaces.weather.RetryTest"`
Expected: FAIL — compilation error, `RetryPolicy`/`withRetry` unresolved.

- [ ] **Step 3: Write minimal implementation**

Create `app/src/main/java/com/blizzardcaron/freeolleefaces/weather/Retry.kt`:

```kotlin
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.blizzardcaron.freeolleefaces.weather.RetryTest"`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/blizzardcaron/freeolleefaces/weather/Retry.kt \
        app/src/test/java/com/blizzardcaron/freeolleefaces/weather/RetryTest.kt
git commit -m "Add RetryPolicy presets and generic withRetry helper"
```

---

## Task 3: WeatherErrorCopy mapper

**Files:**
- Create: `app/src/main/java/com/blizzardcaron/freeolleefaces/format/WeatherErrorCopy.kt`
- Test: `app/src/test/java/com/blizzardcaron/freeolleefaces/format/WeatherErrorCopyTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/blizzardcaron/freeolleefaces/format/WeatherErrorCopyTest.kt`:

```kotlin
package com.blizzardcaron.freeolleefaces.format

import com.blizzardcaron.freeolleefaces.weather.WeatherFetchError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherErrorCopyTest {

    @Test
    fun `ServerError shows status code and a retry hint`() {
        val msg = WeatherErrorCopy.describe(WeatherFetchError.ServerError(502))
        assertTrue(msg, msg.contains("502"))
        assertTrue(msg, msg.contains("unavailable"))
    }

    @Test
    fun `Timeout shows a retry hint without a code`() {
        val msg = WeatherErrorCopy.describe(WeatherFetchError.Timeout)
        assertTrue(msg, msg.contains("timed out"))
    }

    @Test
    fun `Network reports a connection problem`() {
        val msg = WeatherErrorCopy.describe(WeatherFetchError.Network("reset"))
        assertTrue(msg, msg.contains("connection", ignoreCase = true))
    }

    @Test
    fun `ClientError shows the status code`() {
        val msg = WeatherErrorCopy.describe(WeatherFetchError.ClientError(400))
        assertTrue(msg, msg.contains("400"))
    }

    @Test
    fun `Malformed reports bad data`() {
        assertEquals(
            "Couldn't read weather (bad data)",
            WeatherErrorCopy.describe(WeatherFetchError.Malformed("no current")),
        )
    }

    @Test
    fun `unknown throwable falls back to generic copy`() {
        assertEquals(
            "Couldn't fetch weather",
            WeatherErrorCopy.describe(RuntimeException("boom")),
        )
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.blizzardcaron.freeolleefaces.format.WeatherErrorCopyTest"`
Expected: FAIL — compilation error, `WeatherErrorCopy` unresolved.

- [ ] **Step 3: Write minimal implementation**

Create `app/src/main/java/com/blizzardcaron/freeolleefaces/format/WeatherErrorCopy.kt`:

```kotlin
package com.blizzardcaron.freeolleefaces.format

import com.blizzardcaron.freeolleefaces.weather.WeatherFetchError

/** Maps a weather-fetch failure to user-facing copy. Never exposes the raw upstream body. */
object WeatherErrorCopy {
    fun describe(error: Throwable): String = when (error) {
        is WeatherFetchError.ServerError ->
            "Weather service unavailable (${error.statusCode}) — try again shortly"
        is WeatherFetchError.Timeout ->
            "Weather service timed out — try again shortly"
        is WeatherFetchError.Network ->
            "No connection to weather service — try again shortly"
        is WeatherFetchError.ClientError ->
            "Couldn't read weather (${error.statusCode})"
        is WeatherFetchError.Malformed ->
            "Couldn't read weather (bad data)"
        else ->
            "Couldn't fetch weather"
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.blizzardcaron.freeolleefaces.format.WeatherErrorCopyTest"`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/blizzardcaron/freeolleefaces/format/WeatherErrorCopy.kt \
        app/src/test/java/com/blizzardcaron/freeolleefaces/format/WeatherErrorCopyTest.kt
git commit -m "Add WeatherErrorCopy mapper for user-facing fetch errors"
```

---

## Task 4: Wire retry + typed errors into OpenMeteoClient and both call sites

This task changes the `currentTemp` signature, so the parser test, the client, and both call
sites change together to keep the build green.

**Files:**
- Modify test: `app/src/test/java/com/blizzardcaron/freeolleefaces/weather/OpenMeteoClientParserTest.kt`
- Modify: `app/src/main/java/com/blizzardcaron/freeolleefaces/weather/OpenMeteoClient.kt`
- Modify: `app/src/main/java/com/blizzardcaron/freeolleefaces/MainActivity.kt`
- Modify: `app/src/main/java/com/blizzardcaron/freeolleefaces/auto/AutoUpdateWorker.kt`

- [ ] **Step 1: Update the parser tests to expect `Malformed` (failing)**

In `OpenMeteoClientParserTest.kt`, replace the two throwing tests (the `throws when current block is missing` and `throws when temperature_2m is missing` tests) so they catch `WeatherFetchError.Malformed` instead of `IllegalStateException`:

```kotlin
    @Test
    fun `throws Malformed when current block is missing`() {
        val json = """{"hourly":{"temperature_2m":[60]}}"""
        try {
            OpenMeteoClient.parseCurrentTemperatureF(json)
            error("expected to throw")
        } catch (e: WeatherFetchError.Malformed) {
            assertTrue(e.message?.contains("current") == true)
        }
    }

    @Test
    fun `throws Malformed when temperature_2m is missing`() {
        val json = """{"current":{"time":"2026-05-14T15:00"}}"""
        try {
            OpenMeteoClient.parseCurrentTemperatureF(json)
            error("expected to throw")
        } catch (e: WeatherFetchError.Malformed) {
            assertTrue(e.message?.contains("temperature_2m") == true)
        }
    }
```

The `assertEquals`/`assertTrue` imports already present are sufficient; no import change needed for the test (same package as `WeatherFetchError`).

- [ ] **Step 2: Run the parser test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.blizzardcaron.freeolleefaces.weather.OpenMeteoClientParserTest"`
Expected: FAIL — `parseCurrentTemperatureF` still throws `IllegalStateException`, which the new `catch (e: WeatherFetchError.Malformed)` does not catch, so `error("expected to throw")`’s exception is not the caught type and the test errors.

- [ ] **Step 3: Rewrite OpenMeteoClient with typed errors, fetchOnce, and retry**

Replace the entire contents of `app/src/main/java/com/blizzardcaron/freeolleefaces/weather/OpenMeteoClient.kt` with:

```kotlin
package com.blizzardcaron.freeolleefaces.weather

import com.blizzardcaron.freeolleefaces.format.TempUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

object OpenMeteoClient {

    private const val BASE = "https://api.open-meteo.com/v1/forecast"
    private const val CONNECT_TIMEOUT_MS = 8000
    private const val READ_TIMEOUT_MS = 8000

    /** Pure URL builder so the unit param is unit-testable without HTTP. */
    fun buildUrl(lat: Double, lng: Double, unit: TempUnit): URL =
        URL(
            "$BASE?latitude=$lat&longitude=$lng" +
                "&current=temperature_2m&temperature_unit=${unit.openMeteoParam}"
        )

    /** Fetches `current.temperature_2m` in the requested [unit], retrying per [policy]. */
    suspend fun currentTemp(
        lat: Double,
        lng: Double,
        unit: TempUnit,
        policy: RetryPolicy,
    ): Result<Double> =
        withContext(Dispatchers.IO) {
            withRetry(
                policy = policy,
                isTransient = { it is WeatherFetchError && it.isTransient },
            ) {
                fetchOnce(lat, lng, unit)
            }
        }

    /** A single HTTP attempt. Throws a [WeatherFetchError] on any failure. */
    private fun fetchOnce(lat: Double, lng: Double, unit: TempUnit): Double {
        val conn = buildUrl(lat, lng, unit).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.requestMethod = "GET"
            val code = try {
                conn.responseCode
            } catch (e: SocketTimeoutException) {
                throw WeatherFetchError.Timeout
            } catch (e: IOException) {
                throw WeatherFetchError.Network(e.message ?: e.javaClass.simpleName)
            }
            WeatherFetchError.fromHttpStatus(code)?.let { throw it }
            val body = try {
                conn.inputStream.bufferedReader().use { it.readText() }
            } catch (e: SocketTimeoutException) {
                throw WeatherFetchError.Timeout
            } catch (e: IOException) {
                throw WeatherFetchError.Network(e.message ?: e.javaClass.simpleName)
            }
            return parseCurrentTemperatureF(body)
        } finally {
            conn.disconnect()
        }
    }

    /** Extracts `current.temperature_2m` from the response JSON. Unit is whatever the URL requested. */
    fun parseCurrentTemperatureF(json: String): Double {
        val root = try {
            JSONObject(json)
        } catch (e: JSONException) {
            throw WeatherFetchError.Malformed("response is not valid JSON: ${e.message}")
        }
        val current = root.optJSONObject("current")
            ?: throw WeatherFetchError.Malformed("response missing 'current' block")
        if (!current.has("temperature_2m")) {
            throw WeatherFetchError.Malformed("response 'current' missing 'temperature_2m'")
        }
        return current.getDouble("temperature_2m")
    }
}
```

- [ ] **Step 4: Update the preview call site in MainActivity**

In `app/src/main/java/com/blizzardcaron/freeolleefaces/MainActivity.kt`, add two imports next to the existing `com.blizzardcaron.freeolleefaces.weather.OpenMeteoClient` import (line ~35) and `com.blizzardcaron.freeolleefaces.format.DisplayFormatter` import (line ~23):

```kotlin
import com.blizzardcaron.freeolleefaces.format.WeatherErrorCopy
import com.blizzardcaron.freeolleefaces.weather.RetryPolicy
```

Then change the temperature fetch block (lines ~99–107). Replace:

```kotlin
                OpenMeteoClient.currentTemp(lat, lng, unit)
                    .onSuccess { temp ->
                        val payload = DisplayFormatter.temperature(temp, unit)
                        val human = "Currently: %.1f°%s".format(Locale.US, temp, unit.symbol)
                        update { it.copy(tempPreview = PreviewState.Ready(payload, human)) }
                    }
                    .onFailure { err ->
                        update { it.copy(tempPreview = PreviewState.Error("Weather fetch failed: ${err.message}")) }
                    }
```

with:

```kotlin
                OpenMeteoClient.currentTemp(lat, lng, unit, RetryPolicy.Preview)
                    .onSuccess { temp ->
                        val payload = DisplayFormatter.temperature(temp, unit)
                        val human = "Currently: %.1f°%s".format(Locale.US, temp, unit.symbol)
                        update { it.copy(tempPreview = PreviewState.Ready(payload, human)) }
                    }
                    .onFailure { err ->
                        update { it.copy(tempPreview = PreviewState.Error(WeatherErrorCopy.describe(err))) }
                    }
```

- [ ] **Step 5: Update the worker call site in AutoUpdateWorker**

In `app/src/main/java/com/blizzardcaron/freeolleefaces/auto/AutoUpdateWorker.kt`, add two imports alongside the existing weather import:

```kotlin
import com.blizzardcaron.freeolleefaces.weather.RetryPolicy
import com.blizzardcaron.freeolleefaces.weather.WeatherFetchError
```

Then change the fetch block (lines ~65–72). Replace:

```kotlin
            OpenMeteoClient.currentTemp(lat, lng, prefs.tempUnit)
                .onSuccess { temp ->
                    val payload = DisplayFormatter.temperature(temp, prefs.tempUnit)
                    OlleeBleClient(ctx).send(address, payload)
                        .onSuccess { prefs.recordAutoSend("Sent '$payload'") }
                        .onFailure { prefs.recordAutoSend("Skipped: watch unreachable") }
                }
                .onFailure { prefs.recordAutoSend("Skipped: weather fetch failed") }
```

with:

```kotlin
            OpenMeteoClient.currentTemp(lat, lng, prefs.tempUnit, RetryPolicy.Background)
                .onSuccess { temp ->
                    val payload = DisplayFormatter.temperature(temp, prefs.tempUnit)
                    OlleeBleClient(ctx).send(address, payload)
                        .onSuccess { prefs.recordAutoSend("Sent '$payload'") }
                        .onFailure { prefs.recordAutoSend("Skipped: watch unreachable") }
                }
                .onFailure { err ->
                    val suffix = (err as? WeatherFetchError)?.statusCode?.let { " (HTTP $it)" } ?: ""
                    prefs.recordAutoSend("Skipped: weather fetch failed$suffix")
                }
```

- [ ] **Step 6: Run the full unit test suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS — all tests green (URL, parser-with-Malformed, WeatherFetchError, Retry, WeatherErrorCopy).

- [ ] **Step 7: Verify the whole app still compiles**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL — confirms both call sites and the new signature compile.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/blizzardcaron/freeolleefaces/weather/OpenMeteoClient.kt \
        app/src/main/java/com/blizzardcaron/freeolleefaces/MainActivity.kt \
        app/src/main/java/com/blizzardcaron/freeolleefaces/auto/AutoUpdateWorker.kt \
        app/src/test/java/com/blizzardcaron/freeolleefaces/weather/OpenMeteoClientParserTest.kt
git commit -m "Retry transient Open-Meteo failures and surface clean error copy"
```

---

## Task 5: Final verification

**Files:** none (verification only).

- [ ] **Step 1: Run the complete unit test suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS — all test classes green.

- [ ] **Step 2: Confirm a release-path build compiles**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Manual sanity check against the spec**

Confirm by reading the diff:
- `currentTemp` has a `policy` parameter and no default (both call sites pass one).
- The preview path no longer interpolates `err.message`.
- The worker path is otherwise unchanged (still enqueues the next fire and returns `Result.success()`).

No commit needed if Tasks 1–4 are already committed and the tree is clean.

---

## Self-Review Notes

- **Spec coverage:** typed errors (Task 1), retry helper + presets (Task 2), user copy (Task 3), client wiring + parser change + both call sites (Task 4), 8s timeouts and `buildUrl` left unchanged (Task 4 code), worker flow unchanged (Task 4 Step 5). All spec sections covered.
- **No new dependency:** `withRetry` delay is injected in tests; `runBlocking` comes from the existing `kotlinx-coroutines-android` implementation dependency, available on the unit-test classpath.
- **Type consistency:** `WeatherFetchError`, `isTransient`, `statusCode`, `fromHttpStatus`, `RetryPolicy.Preview`/`.Background`, `withRetry(policy, isTransient, delayFn, block)`, `WeatherErrorCopy.describe(Throwable)`, and `currentTemp(lat, lng, unit, policy)` are used identically across all tasks.

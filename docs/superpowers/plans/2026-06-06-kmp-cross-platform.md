# KMP + Compose Multiplatform Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Convert FreeOllee Faces to a Kotlin Multiplatform app with logic and Compose UI in `commonMain` and all Android I/O behind `expect`/`actual`, keeping the module path `:app`, the `applicationId`, and the native background engine unchanged.

**Architecture:** Single `:app` module converted in place to `kotlin("multiplatform")` + `com.android.application` + `org.jetbrains.compose`. Only `androidTarget()` is declared now (no Kotlin/Native, no iOS — fast Gradle on the Pi). Portable code moves to `commonMain`; Android-privileged I/O (BLE, Health Connect, Location, prefs, notification access) sits behind interfaces with Android `actual` implementations; the WorkManager background engine stays Android-only and calls shared orchestration.

**Tech Stack:** Kotlin 2.2.10, Compose Multiplatform, kotlinx-datetime, Ktor client (OkHttp engine on Android), kotlinx.serialization, multiplatform-settings, kotlinx-coroutines, JUnit→kotlin.test.

**Spec:** `docs/superpowers/specs/2026-06-06-kmp-cross-platform-design.md`

---

## Conventions for every task

- After any code change, the build must stay green before committing: `./gradlew :app:assembleDebug` and the unit-test task pass.
- Source-set layout after Phase 1: `app/src/commonMain/kotlin/...`, `app/src/androidMain/kotlin/...`, `app/src/commonTest/kotlin/...`. Android resources/manifest remain under `app/src/androidMain/` (manifest) and `app/src/main/res` is moved to `app/src/androidMain/res`.
- Package root stays `com.blizzardcaron.freeolleefaces`.
- Commit after each task with the message shown in its final step.

## Version matrix (Phase 1 — VERIFY before relying on)

The host catalog already pins **Kotlin 2.2.10** and **AGP 9.1.1**, both bleeding-edge. Before writing build files, confirm the Compose Multiplatform release that supports Kotlin 2.2.10 against the JetBrains compatibility table (https://github.com/JetBrains/compose-multiplatform/blob/master/VERSIONING.md). Starting versions to add to `gradle/libs.versions.toml`:

```
composeMultiplatform = "1.8.2"   # VERIFY: must support Kotlin 2.2.10
kotlinxDatetime      = "0.6.2"
ktor                 = "3.1.3"
kotlinxSerialization = "1.8.1"
multiplatformSettings = "1.3.0"
lifecycleViewmodel   = "2.9.1"   # org.jetbrains.androidx.lifecycle (CMP-compatible)
```

If `assembleDebug` fails in Phase 1 Task 1.2 with a Kotlin/Compose plugin version error, adjust `composeMultiplatform` to the version the error names and retry before proceeding.

## Phase 1 — Module conversion (no behavior change)

Goal: `:app` becomes a KMP + Compose Multiplatform module with a single `androidTarget()`, all existing sources living under `androidMain`, building and running identically. This is the highest-risk phase; nothing else changes alongside it.

### Task 1.1: Add multiplatform plugins and library aliases to the version catalog

**Files:**
- Modify: `gradle/libs.versions.toml`

- [ ] **Step 1: Add versions and plugin/library aliases**

In `[versions]` add (using the verified values from the matrix above):

```toml
composeMultiplatform = "1.8.2"
kotlinxDatetime = "0.6.2"
ktor = "3.1.3"
kotlinxSerialization = "1.8.1"
multiplatformSettings = "1.3.0"
lifecycleViewmodel = "2.9.1"
```

In `[libraries]` add:

```toml
kotlinx-datetime = { group = "org.jetbrains.kotlinx", name = "kotlinx-datetime", version.ref = "kotlinxDatetime" }
kotlinx-serialization-json = { group = "org.jetbrains.kotlinx", name = "kotlinx-serialization-json", version.ref = "kotlinxSerialization" }
kotlinx-coroutines-core = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-core", version.ref = "coroutines" }
ktor-client-core = { group = "io.ktor", name = "ktor-client-core", version.ref = "ktor" }
ktor-client-okhttp = { group = "io.ktor", name = "ktor-client-okhttp", version.ref = "ktor" }
multiplatform-settings = { group = "com.russhwolf", name = "multiplatform-settings", version.ref = "multiplatformSettings" }
androidx-lifecycle-viewmodel-compose = { group = "org.jetbrains.androidx.lifecycle", name = "lifecycle-viewmodel-compose", version.ref = "lifecycleViewmodel" }
```

In `[plugins]` add:

```toml
kotlin-multiplatform = { id = "org.jetbrains.kotlin.multiplatform", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
compose-multiplatform = { id = "org.jetbrains.compose", version.ref = "composeMultiplatform" }
```

- [ ] **Step 2: Verify the catalog parses**

Run: `./gradlew help`
Expected: `BUILD SUCCESSFUL` (no "Invalid TOML catalog definition").

- [ ] **Step 3: Commit**

```bash
git add gradle/libs.versions.toml
git commit -m "build: add KMP/Compose Multiplatform catalog entries"
```

### Task 1.2: Register the Compose Multiplatform plugin at the root

**Files:**
- Modify: `build.gradle.kts` (root)

- [ ] **Step 1: Declare the new plugins (apply false) at the root**

Replace the root `build.gradle.kts` body with:

```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.multiplatform) apply false
}
```

- [ ] **Step 2: Verify**

Run: `./gradlew help`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add build.gradle.kts
git commit -m "build: register multiplatform plugins at root"
```

### Task 1.3: Move Android sources into the androidMain source set

KMP expects `androidMain` (not `main`) for the Android target's Kotlin. Move the existing tree wholesale; nothing moves to `commonMain` yet.

**Files:**
- Move: `app/src/main/java/**` → `app/src/androidMain/kotlin/**`
- Move: `app/src/main/res/**` → `app/src/androidMain/res/**`
- Move: `app/src/main/AndroidManifest.xml` → `app/src/androidMain/AndroidManifest.xml`
- Move: `app/src/test/java/**` → `app/src/androidUnitTest/kotlin/**`
- Move: `app/src/debug/**` → `app/src/androidDebug/**` (if present)

- [ ] **Step 1: Move the trees with git so history is preserved**

```bash
cd app/src
mkdir -p androidMain androidUnitTest
git mv main/java androidMain/kotlin
git mv main/res androidMain/res
git mv main/AndroidManifest.xml androidMain/AndroidManifest.xml
git mv test/java androidUnitTest/kotlin
[ -d debug ] && git mv debug androidDebug || true
rmdir main test 2>/dev/null || true
cd ../..
```

- [ ] **Step 2: Do not build yet** (build config is rewritten in Task 1.4). Proceed.

- [ ] **Step 3: Commit the move**

```bash
git add -A app/src
git commit -m "refactor: move Android sources into androidMain/androidUnitTest source sets"
```

### Task 1.4: Rewrite app/build.gradle.kts as a KMP + Compose Multiplatform module

**Files:**
- Modify: `app/build.gradle.kts`

- [ ] **Step 1: Replace the plugins and structure**

Replace the entire `app/build.gradle.kts` with the following. The VERSION-file `versionCode` logic and signing config are preserved verbatim; only the module shape changes.

```kotlin
import org.jetbrains.compose.ComposeBuildConfig

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

// Single source of truth for the app version: the root-level VERSION file.
val appVersionName: String = rootProject.file("VERSION").readText().trim()
val appVersionCode: Int = run {
    val parts = appVersionName.split(".")
    require(parts.size == 3 && parts.all { it.toIntOrNull() != null }) {
        "VERSION must be MAJOR.MINOR.PATCH (got '$appVersionName')"
    }
    val (major, minor, patch) = parts.map { it.toInt() }
    require(minor <= 99 && patch <= 99) {
        "VERSION minor/patch must each be <= 99 for the versionCode formula (got '$appVersionName')"
    }
    major * 10000 + minor * 100 + patch
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.ui)
                implementation(compose.components.resources)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.datetime)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.ktor.client.core)
                implementation(libs.multiplatform.settings)
                implementation(libs.androidx.lifecycle.viewmodel.compose)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
            }
        }
        val androidMain by getting {
            dependencies {
                implementation(libs.androidx.core.ktx)
                implementation(libs.androidx.lifecycle.runtime.ktx)
                implementation(libs.androidx.activity.compose)
                implementation(libs.ktor.client.okhttp)
                implementation(libs.androidx.work.runtime.ktx)
                implementation(libs.androidx.health.connect.client)
            }
        }
        val androidUnitTest by getting {
            dependencies {
                implementation(libs.junit)
            }
        }
    }
}

android {
    namespace = "com.blizzardcaron.freeolleefaces"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.blizzardcaron.freeolleefaces"
        minSdk = 31
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        create("release") {
            System.getenv("KEYSTORE_FILE")?.let { ksPath ->
                storeFile = file(ksPath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (System.getenv("KEYSTORE_FILE") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    debugImplementation(compose.uiTooling)
}
```

Add the coroutines-test alias to the catalog if missing:

```toml
kotlinx-coroutines-test = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-test", version.ref = "coroutines" }
```

- [ ] **Step 2: Build the debug APK**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`. If it fails on plugin/Compose version mismatch, fix `composeMultiplatform` per the error and retry (see Version matrix note). If it fails because a source file under `androidMain` uses `androidx.compose.*` imports — that is expected and fine; those are Android Compose artifacts and resolve via the Compose Multiplatform Android target.

- [ ] **Step 3: Run the existing unit tests**

Run: `./gradlew :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`, all existing tests pass.

- [ ] **Step 4: Install and smoke-test on device**

Run: `./gradlew :app:installDebug`
Expected: app launches; Home dashboard renders; a manual "Update now" still pushes to the watch. (Requires the user's device.)

- [ ] **Step 5: Commit**

```bash
git add app/build.gradle.kts gradle/libs.versions.toml
git commit -m "build: convert :app to Kotlin Multiplatform + Compose Multiplatform (Android target only)"
```

## Phase 2 — Pure logic → commonMain

Goal: move every file with no Android and no `java.*` dependency to `commonMain`, and port its test to `commonTest` with `kotlin.test`. Each move is verified by a green test run.

These files are confirmed to have **no `android.*` and no `java.*` imports** (verified against the source): `ble/OlleeProtocol`, `ble/BleRetryPolicy`, `format/TempUnit`, `format/WeatherErrorCopy`, `weather/Retry`, `weather/WeatherFetchError`, `timer/TimerModels`, `timer/TimerSetEditing`, `notify/NotifyDecision`, `notify/StepsFailureClassifier`, `auto/ActiveFace`, `prefs/IntervalOptions`, `notifications/NotificationCount`, `location/LocationFreshness`, `auto/Staleness`, `ui/PreviewState`, `ui/Screen`.

> Before moving each file, confirm it: `grep -nE "^import (android|java)\." app/src/androidMain/kotlin/com/blizzardcaron/freeolleefaces/<path>`. Empty output ⇒ safe to move. If a file unexpectedly imports `java.*` (e.g. `Staleness` using `System.currentTimeMillis()`), handle it in Phase 3 instead and skip it here.

### kotlin.test import substitution (apply to every ported test)

| JUnit4 | kotlin.test |
|---|---|
| `import org.junit.Test` | `import kotlin.test.Test` |
| `import org.junit.Assert.assertEquals` | `import kotlin.test.assertEquals` |
| `import org.junit.Assert.assertNotNull` | `import kotlin.test.assertNotNull` |
| `import org.junit.Assert.assertNull` | `import kotlin.test.assertNull` |
| `import org.junit.Assert.assertTrue` | `import kotlin.test.assertTrue` |
| `import org.junit.Assert.assertFalse` | `import kotlin.test.assertFalse` |
| `assertEquals(expected, actual)` | unchanged (kotlin.test takes the same order) |
| `assertEquals(msg, expected, actual)` | `assertEquals(expected, actual, msg)` (message LAST) |
| `assertEquals(expectedDouble, actualDouble, delta)` | `assertEquals(expectedDouble, actualDouble, delta)` (unchanged) |

> The message-argument reorder is the one non-mechanical edit. Search each ported test for 3-arg `assertEquals`/`assertTrue` calls where the first arg is a String and move it to the end.

### Task 2.1: Move the dependency-free `ble`, `format`, `weather` (non-HTTP), `notify` files

**Files (move main + test together):**
- Move: `.../ble/OlleeProtocol.kt` → `app/src/commonMain/kotlin/.../ble/OlleeProtocol.kt`; test → `app/src/commonTest/kotlin/.../ble/OlleeProtocolTest.kt`
- Move: `.../ble/BleRetryPolicy.kt` (+ `BleRetryPolicyTest.kt`)
- Move: `.../format/TempUnit.kt`
- Move: `.../format/WeatherErrorCopy.kt` (+ `WeatherErrorCopyTest.kt`)
- Move: `.../weather/Retry.kt` (+ `RetryTest.kt`)
- Move: `.../weather/WeatherFetchError.kt` (+ `WeatherFetchErrorTest.kt`)
- Move: `.../notify/NotifyDecision.kt` (+ `NotifyDecisionTest.kt`)
- Move: `.../notify/StepsFailureClassifier.kt` (+ `StepsFailureClassifierTest.kt`, `FailureKindTest.kt`)

- [ ] **Step 1: Create common dirs and git-move each pair**

```bash
cd app/src
PKG=com/blizzardcaron/freeolleefaces
mkdir -p commonMain/kotlin/$PKG/{ble,format,weather,notify} commonTest/kotlin/$PKG/{ble,format,weather,notify}
for f in ble/OlleeProtocol ble/BleRetryPolicy format/TempUnit format/WeatherErrorCopy \
         weather/Retry weather/WeatherFetchError notify/NotifyDecision notify/StepsFailureClassifier; do
  git mv androidMain/kotlin/$PKG/$f.kt commonMain/kotlin/$PKG/$f.kt
done
for t in ble/OlleeProtocolTest ble/BleRetryPolicyTest format/WeatherErrorCopyTest \
         weather/RetryTest weather/WeatherFetchErrorTest notify/NotifyDecisionTest \
         notify/StepsFailureClassifierTest notify/FailureKindTest; do
  git mv androidUnitTest/kotlin/$PKG/$t.kt commonTest/kotlin/$PKG/$t.kt
done
cd ../..
```

- [ ] **Step 2: Apply the kotlin.test import substitution** to every file under `app/src/commonTest/kotlin/$PKG/{ble,format,weather,notify}` (table above). Reorder any 3-arg `assertEquals` with a leading String message.

- [ ] **Step 3: Run the common tests**

Run: `./gradlew :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`; the moved tests run from `commonTest`.

- [ ] **Step 4: Build to confirm the Android target still links**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`. (MainActivity still imports these by the same package path — unchanged.)

- [ ] **Step 5: Commit**

```bash
git add -A app/src
git commit -m "refactor: move dependency-free ble/format/weather/notify logic to commonMain"
```

### Task 2.2: Move the dependency-free `timer`, `auto`, `prefs`, `notifications`, `location`, `ui` files

**Files (move main; move test where one exists):**
- Move: `.../timer/TimerModels.kt` (+ `TimerModelsTest.kt`)
- Move: `.../timer/TimerSetEditing.kt` (+ `TimerSetEditingTest.kt`)
- Move: `.../auto/ActiveFace.kt` (+ `ActiveFaceTest.kt`)
- Move: `.../prefs/IntervalOptions.kt` (+ `IntervalOptionsTest.kt`)
- Move: `.../notifications/NotificationCount.kt` (+ `NotificationCountTest.kt`)
- Move: `.../location/LocationFreshness.kt` (+ `LocationFreshnessTest.kt`)
- Move: `.../ui/PreviewState.kt`, `.../ui/Screen.kt` (no tests)

- [ ] **Step 1: git-move each pair**

```bash
cd app/src
PKG=com/blizzardcaron/freeolleefaces
mkdir -p commonMain/kotlin/$PKG/{timer,auto,prefs,notifications,location,ui} \
         commonTest/kotlin/$PKG/{timer,auto,prefs,notifications,location}
for f in timer/TimerModels timer/TimerSetEditing auto/ActiveFace prefs/IntervalOptions \
         notifications/NotificationCount location/LocationFreshness ui/PreviewState ui/Screen; do
  git mv androidMain/kotlin/$PKG/$f.kt commonMain/kotlin/$PKG/$f.kt
done
for t in timer/TimerModelsTest timer/TimerSetEditingTest auto/ActiveFaceTest \
         prefs/IntervalOptionsTest notifications/NotificationCountTest location/LocationFreshnessTest; do
  git mv androidUnitTest/kotlin/$PKG/$t.kt commonTest/kotlin/$PKG/$t.kt
done
cd ../..
```

- [ ] **Step 2: Apply the kotlin.test substitution** to the six moved test files.

- [ ] **Step 3: Check for `Staleness`** — if `grep -L "java\." androidMain/.../auto/Staleness.kt` shows it is java-free, move it too (+ `StalenessTest.kt`); otherwise defer to Phase 3. (It likely takes `now: Long` as a parameter rather than calling `System.currentTimeMillis()`; verify.)

- [ ] **Step 4: Run tests and build**

Run: `./gradlew :app:testDebugUnitTest && ./gradlew :app:assembleDebug`
Expected: both `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add -A app/src
git commit -m "refactor: move dependency-free timer/auto/prefs/notifications/location/ui logic to commonMain"
```

## Phase 3 — De-Java the portable files

Goal: replace `java.time`, `HttpURLConnection`, and `org.json` in the otherwise-portable files so they compile in `commonMain`. Each file's existing tests (ported to `commonTest`) prove behavior parity.

### java.time → kotlinx-datetime substitution map

| java.time | kotlinx-datetime |
|---|---|
| `java.time.Instant.now()` | `kotlinx.datetime.Clock.System.now()` |
| `java.time.Instant.ofEpochMilli(ms)` | `kotlinx.datetime.Instant.fromEpochMilliseconds(ms)` |
| `instant.toEpochMilli()` | `instant.toEpochMilliseconds()` |
| `java.time.ZoneId.systemDefault()` | `kotlinx.datetime.TimeZone.currentSystemDefault()` |
| `instant.atZone(zone)` → `ZonedDateTime` | `instant.toLocalDateTime(timeZone)` → `LocalDateTime` |
| `zdt.toLocalTime()` | `localDateTime.time` (`LocalTime`) |
| `zdt.hour` / `zdt.minute` | `localDateTime.hour` / `localDateTime.minute` |
| `ZonedDateTime.now(zone)` | `Clock.System.now().toLocalDateTime(tz)` |
| `DateTimeFormatter.ofPattern("h:mm a")` | `LocalTime.Format { amPmHour(); char(':'); minute(); char(' '); amPmMarker("AM","PM") }` |
| `zdt.format(fmt)` | `localTime.format(theFormat)` |

> `nextTemperatureFire` in `AutoUpdateSchedule` returns a `ZonedDateTime` today and callers do `fire.format(CLOCK)`. Change its return type to `kotlinx.datetime.LocalDateTime` and update the two call sites in `MainActivity` (`tempNextText`) accordingly; the `CLOCK` formatter becomes a `LocalTime` format applied to `fire.time`.

### Number-format helper (replaces `String.format`)

- [ ] **Task 3.0 Step 1: Create the helper**

**Create:** `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/format/NumberFormat.kt`

```kotlin
package com.blizzardcaron.freeolleefaces.format

import kotlin.math.absoluteValue
import kotlin.math.roundToLong

/** "%.1f" equivalent: rounds to [decimals] places, always showing them. */
fun formatDecimal(value: Double, decimals: Int): String {
    val neg = value < 0
    val factor = generateSequence(1L) { it * 10 }.take(decimals + 1).last()
    val scaled = (value.absoluteValue * factor).roundToLong()
    val whole = scaled / factor
    val frac = scaled % factor
    val fracStr = frac.toString().padStart(decimals, '0')
    val body = if (decimals > 0) "$whole.$fracStr" else "$whole"
    return if (neg && scaled != 0L) "-$body" else body
}

/** "%,d" equivalent: groups thousands with commas. */
fun groupThousands(value: Long): String {
    val s = value.absoluteValue.toString()
    val grouped = s.reversed().chunked(3).joinToString(",").reversed()
    return if (value < 0) "-$grouped" else grouped
}
```

- [ ] **Task 3.0 Step 2: Create its test**

**Create:** `app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/format/NumberFormatTest.kt`

```kotlin
package com.blizzardcaron.freeolleefaces.format

import kotlin.test.Test
import kotlin.test.assertEquals

class NumberFormatTest {
    @Test fun decimals_round_half_up() {
        assertEquals("72.0", formatDecimal(72.04, 1))
        assertEquals("72.1", formatDecimal(72.05, 1))
        assertEquals("-3.5", formatDecimal(-3.45, 1))
        assertEquals("0.0", formatDecimal(0.0, 1))
    }
    @Test fun groups_thousands() {
        assertEquals("1,234", groupThousands(1234))
        assertEquals("999", groupThousands(999))
        assertEquals("1,000,000", groupThousands(1_000_000))
    }
}
```

- [ ] **Task 3.0 Step 3:** Run `./gradlew :app:testDebugUnitTest` → PASS. Commit: `git commit -am "feat: add multiplatform number-format helpers"`.

### Task 3.1: Port `format/DisplayFormatter` and its test

**Files:**
- Move + edit: `androidMain/.../format/DisplayFormatter.kt` → `commonMain/.../format/DisplayFormatter.kt`
- Move + edit: `androidUnitTest/.../format/DisplayFormatterTest.kt` → `commonTest/.../format/DisplayFormatterTest.kt`

- [ ] **Step 1:** Read `DisplayFormatter.kt`. Replace every `java.time` import/use per the substitution map; replace any `String.format("%...")` with `formatDecimal`/`groupThousands` or string templates. Its public function signatures (`temperature`, `steps`, `custom`, `sunTime`) must keep their current parameter lists so callers in `MainActivity` are untouched — except `sunTime` which already takes a `LocalTime`: change its parameter to `kotlinx.datetime.LocalTime`.
- [ ] **Step 2:** `git mv` both files to common dirs; apply the kotlin.test substitution to the test; convert any `java.time.LocalTime.of(...)` in the test to `kotlinx.datetime.LocalTime(hour, minute)`.
- [ ] **Step 3:** Run `./gradlew :app:testDebugUnitTest` → the DisplayFormatter tests pass (parity proven).
- [ ] **Step 4:** Update `MainActivity` imports if `sunTime` now consumes `kotlinx.datetime.LocalTime` (the `event.time.toLocalTime()` call becomes `event.time.time` once `SunCalc` is ported in Task 3.2 — do that there).
- [ ] **Step 5:** `./gradlew :app:assembleDebug` → SUCCESS. Commit: `git commit -am "refactor: port DisplayFormatter to kotlinx-datetime in commonMain"`.

### Task 3.2: Port `sun/SunCalc` and its test

**Files:**
- Move + edit: `androidMain/.../sun/SunCalc.kt` → `commonMain/.../sun/SunCalc.kt`
- Move + edit: `androidUnitTest/.../sun/SunCalcTest.kt` → `commonTest/.../sun/SunCalcTest.kt`

- [ ] **Step 1:** Read `SunCalc.kt`. The math is pure; only the time types change. Replace `Instant`/`ZoneId`/`ZonedDateTime` per the map. `nextEvent(now: Instant, lat, lng, zone)` becomes `nextEvent(now: kotlinx.datetime.Instant, lat, lng, zone: kotlinx.datetime.TimeZone)`. The returned `event.time` (a `ZonedDateTime` today) becomes a `kotlinx.datetime.LocalDateTime`; update the `SunEvent` data class field type accordingly.
- [ ] **Step 2:** Update the two `SunCalc.nextEvent(...)` call sites in `MainActivity` to pass `Clock.System.now()` and `TimeZone.currentSystemDefault()`, and change `event.time.toLocalTime()` → `event.time.time`, `event.time.format(CLOCK)` → `event.time.time.format(CLOCK)` where `CLOCK` is now a `LocalTime` format.
- [ ] **Step 3:** `git mv` both files; apply kotlin.test substitution; convert the test's `java.time` constructions (`Instant.parse`, `ZoneOffset.UTC`, `ZonedDateTime.of`) to kotlinx-datetime equivalents (`Instant.parse`, `TimeZone.UTC`, `LocalDateTime(...)`). Keep the `abs(...)` delta assertions.
- [ ] **Step 4:** Run `./gradlew :app:testDebugUnitTest` → SunCalc tests pass.
- [ ] **Step 5:** `./gradlew :app:assembleDebug` → SUCCESS. Commit: `git commit -am "refactor: port SunCalc to kotlinx-datetime in commonMain"`.

### Task 3.3: Port `auto/AutoUpdateSchedule` and its test

**Files:**
- Move + edit: `androidMain/.../auto/AutoUpdateSchedule.kt` → `commonMain/...`
- Move + edit: `androidUnitTest/.../auto/AutoUpdateScheduleTest.kt` → `commonTest/...`
- Move (if not already): `androidMain/.../auto/Staleness.kt` → `commonMain/...` (+ test)

- [ ] **Step 1:** Read `AutoUpdateSchedule.kt`. Replace `java.time` per the map. `nextTemperatureFire(now: ZonedDateTime, intervalMinutes, sleep): ZonedDateTime` becomes `nextTemperatureFire(now: LocalDateTime, intervalMinutes, sleep): LocalDateTime`. Keep `SleepWindow` as-is (it holds minute-of-day Ints). Note the **Android-only** `AutoUpdateScheduler` (Task: stays in androidMain) calls `nextTemperatureFire` with `ZonedDateTime.now(...)` — update that caller to pass `Clock.System.now().toLocalDateTime(tz)`.
- [ ] **Step 2:** `git mv` both files; apply kotlin.test substitution; convert test `ZonedDateTime.of(...)` to `LocalDateTime(...)`.
- [ ] **Step 3:** Run `./gradlew :app:testDebugUnitTest` → schedule tests pass.
- [ ] **Step 4:** `./gradlew :app:assembleDebug` → SUCCESS (AutoUpdateScheduler caller updated). Commit: `git commit -am "refactor: port AutoUpdateSchedule + Staleness to kotlinx-datetime in commonMain"`.

### Task 3.4: Port `timer/TimerSetsJson` to kotlinx.serialization

**Files:**
- Move + edit: `androidMain/.../timer/TimerSetsJson.kt` → `commonMain/...`
- Move + edit: `androidUnitTest/.../timer/TimerSetsJsonTest.kt` → `commonTest/...`
- Modify: `androidMain/.../timer/TimerModels.kt` already in commonMain (Phase 2) — add `@Serializable`.

- [ ] **Step 1:** Annotate the timer models. In `commonMain/.../timer/TimerModels.kt`, add `@Serializable` to the `TimerSet` and slot classes (import `kotlinx.serialization.Serializable`). Verify the on-disk JSON shape: today `TimerSetsJson` writes `{"id","name","slots":[{"label","dur"}]}`. To keep existing stored data readable, mirror those names with surrogate serializers OR keep `TimerSetsJson` as a hand-written codec using `kotlinx.serialization.json.Json` element APIs. **Chosen approach:** rewrite `TimerSetsJson` with explicit `JsonArray`/`JsonObject` building (below) so the exact legacy key names (`dur`, not `durationSeconds`) are preserved and decode stays non-throwing.

- [ ] **Step 2:** Replace `TimerSetsJson.kt` body:

```kotlin
package com.blizzardcaron.freeolleefaces.timer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Pure JSON codec for persisting timer sets. Decoding NEVER throws — malformed/missing input
 * yields an empty list. A set whose slot count is not exactly 10 is skipped (it would violate
 * the [TimerSet] invariant). Legacy key names (`dur`) are preserved for on-disk compatibility.
 */
object TimerSetsJson {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(sets: List<TimerSet>): String = buildJsonArray {
        for (set in sets) {
            add(buildJsonObject {
                put("id", JsonPrimitive(set.id))
                put("name", JsonPrimitive(set.name))
                put("slots", buildJsonArray {
                    for (slot in set.slots) {
                        add(buildJsonObject {
                            put("label", JsonPrimitive(slot.label))
                            put("dur", JsonPrimitive(slot.durationSeconds))
                        })
                    }
                })
            })
        }
    }.toString()

    fun decode(jsonStr: String?): List<TimerSet> {
        if (jsonStr.isNullOrBlank()) return emptyList()
        return runCatching {
            json.parseToJsonElement(jsonStr).jsonArray.mapNotNull { el ->
                val obj = el as? JsonObject ?: return@mapNotNull null
                val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val slots = obj["slots"]?.jsonArray?.mapNotNull { s ->
                    val so = s as? JsonObject ?: return@mapNotNull null
                    val label = so["label"]?.jsonPrimitive?.contentOrNull ?: ""
                    val dur = so["dur"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                    TimerSlot(label = label, durationSeconds = dur)
                } ?: return@mapNotNull null
                if (slots.size != 10) return@mapNotNull null
                TimerSet(id = id, name = name, slots = slots)
            }
        }.getOrDefault(emptyList())
    }
}
```

> Verify the actual slot class name and field names in `TimerModels.kt` (`TimerSlot`/`durationSeconds`/`label`) and adjust the code above to match exactly before saving.

- [ ] **Step 3:** `git mv` both files to common dirs; apply kotlin.test substitution to the test.
- [ ] **Step 4:** Run `./gradlew :app:testDebugUnitTest` → codec round-trip + malformed-input tests pass.
- [ ] **Step 5:** Remove the `org-json` dependency only after Task 3.5 (OpenMeteoClient also uses it). `./gradlew :app:assembleDebug` → SUCCESS. Commit: `git commit -am "refactor: port TimerSetsJson to kotlinx.serialization in commonMain"`.

### Task 3.5: Port `weather/OpenMeteoClient` to Ktor + kotlinx.serialization

**Files:**
- Move + edit: `androidMain/.../weather/OpenMeteoClient.kt` → `commonMain/...`
- Move + edit: tests `OpenMeteoClientParserTest.kt`, `OpenMeteoClientUrlTest.kt` → `commonTest/...`
- Create: `commonMain/.../weather/OpenMeteoHttp.kt` (Ktor client provider)

- [ ] **Step 1: Add the serializable response model + parser** (keeps the parser unit-testable without HTTP). Read the existing `OpenMeteoClientParserTest` to learn the exact JSON it feeds and the function it calls; preserve that function name. Replace the `org.json` parse with:

```kotlin
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class OpenMeteoResponse(val current: Current? = null) {
    @Serializable data class Current(@SerialName("temperature_2m") val temp2m: Double? = null)
}

private val parseJson = Json { ignoreUnknownKeys = true }

/** Pure parser: extracts current.temperature_2m, throwing WeatherFetchError.Parse on bad shape. */
fun parseCurrentTemp(body: String): Double =
    (parseJson.decodeFromString<OpenMeteoResponse>(body).current?.temp2m)
        ?: throw WeatherFetchError.Parse  // use whatever the existing error type is named
```

> Match `WeatherFetchError`'s actual parse-error variant name (read `WeatherFetchError.kt`). Keep the existing `buildUrl` logic but return a `String` instead of `java.net.URL` (Ktor takes a String URL); update `OpenMeteoClientUrlTest` to assert on the `String`.

- [ ] **Step 2: Add the Ktor client provider.** **Create** `commonMain/.../weather/OpenMeteoHttp.kt`:

```kotlin
package com.blizzardcaron.freeolleefaces.weather

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.HttpResponse
import io.ktor.http.isSuccess

/** Shared HTTP entry point. The engine is supplied by the platform (OkHttp on Android). */
internal suspend fun fetchBody(client: HttpClient, url: String): String {
    val resp: HttpResponse = client.get(url)
    if (!resp.status.isSuccess()) {
        WeatherFetchError.fromHttpStatus(resp.status.value)?.let { throw it }
    }
    return resp.bodyAsText()
}
```

- [ ] **Step 3: Rewrite `currentTemp` to use Ktor**, preserving the retry wrapper (`withRetry`) and `WeatherFetchError` mapping (timeout/network → transient). The `HttpClient` is created with the default engine resolved from the classpath (OkHttp via the `androidMain` dependency added in Phase 1). Map `io.ktor.client.network.sockets.SocketTimeoutException`/`ConnectTimeoutException` → `WeatherFetchError.Timeout`, and `io.ktor.utils.io.errors.IOException` → `WeatherFetchError.Network(...)`. Configure timeouts via the `HttpTimeout` plugin (add `io.ktor:ktor-client-... ` timeout is in core) set to the existing 8000 ms values.

- [ ] **Step 4:** `git mv` the three files; apply kotlin.test substitution; the parser/URL tests now run in `commonTest`.
- [ ] **Step 5:** Run `./gradlew :app:testDebugUnitTest` → parser + URL tests pass.
- [ ] **Step 6:** Remove the now-unused `org-json` library from `gradle/libs.versions.toml` and from `androidUnitTest` deps (no source references `org.json` after this task — verify with `grep -rn "org.json" app/src`).
- [ ] **Step 7:** `./gradlew :app:assembleDebug` → SUCCESS; `./gradlew :app:installDebug` and confirm temperature still fetches on device. Commit: `git commit -am "refactor: port OpenMeteoClient to Ktor + kotlinx.serialization in commonMain"`.

## Phase 4 — Storage + platform interfaces

Goal: every Android-privileged capability sits behind a **commonMain interface** with an **androidMain implementation**, and `Prefs`/`TimerSetsRepository` move to commonMain backed by `multiplatform-settings`. We use plain interfaces + dependency injection (not `expect/actual` classes) because these need a `Context` on Android; iOS adds its own implementations later. After this phase, `MainActivity` and the background workers construct the Android implementations and pass them where they were used before.

### Task 4.1: Move `Prefs` to commonMain, backed by `Settings`

**Files:**
- Move + edit: `androidMain/.../prefs/Prefs.kt` → `commonMain/.../prefs/Prefs.kt`
- Create: `androidMain/.../prefs/AndroidSettings.kt`
- Modify: all `Prefs(context)`/`Prefs(ctx)` construction sites (`MainActivity`, `auto/AutoUpdateScheduler`, `auto/AutoUpdateWorker`, `auto/WatchdogWorker`, `auto/BootReceiver`, `notify/RetryReceiver`, `devtools/DevToolsReceiver`, `notifications/NotificationCountService` — confirm with `grep -rn "Prefs(" app/src`).

- [ ] **Step 1:** Read `Prefs.kt`. Change the constructor from `class Prefs(context: Context)` to `class Prefs(private val settings: com.russhwolf.settings.Settings, private val clock: kotlinx.datetime.Clock = kotlinx.datetime.Clock.System)`. Replace the `SharedPreferences` field. Convert each property using these equivalents (apply to ALL ~25 properties):

```kotlin
// String? property
var watchAddress: String?
    get() = settings.getStringOrNull(KEY_WATCH)
    set(value) = if (value == null) settings.remove(KEY_WATCH) else settings.putString(KEY_WATCH, value)

// Double? stored as Float (preserve legacy key + Float storage so existing data reads)
var lastLat: Double?
    get() = if (settings.hasKey(KEY_LAT)) settings.getFloat(KEY_LAT, 0f).toDouble() else null
    set(value) = if (value == null) settings.remove(KEY_LAT) else settings.putFloat(KEY_LAT, value.toFloat())

// Int property
var updateIntervalMinutes: Int
    get() = settings.getInt(KEY_UPDATE_INTERVAL, DEFAULT_INTERVAL)
    set(value) = settings.putInt(KEY_UPDATE_INTERVAL, value)

// Long? property
var tempFetchedMs: Long?
    get() = if (settings.hasKey(KEY_TEMP_FETCHED_MS)) settings.getLong(KEY_TEMP_FETCHED_MS, 0L) else null
    set(value) = if (value == null) settings.remove(KEY_TEMP_FETCHED_MS) else settings.putLong(KEY_TEMP_FETCHED_MS, value)

// Boolean property
var notificationsEnabled: Boolean
    get() = settings.getBoolean(KEY_NOTIFICATIONS_ENABLED, false)
    set(value) = settings.putBoolean(KEY_NOTIFICATIONS_ENABLED, value)

// enum (stored by name) — TempUnit example
var tempUnit: TempUnit
    get() = settings.getStringOrNull(KEY_TEMP_UNIT)?.let { runCatching { TempUnit.valueOf(it) }.getOrNull() } ?: TempUnit.FAHRENHEIT
    set(value) = settings.putString(KEY_TEMP_UNIT, value.name)
```

Replace any `System.currentTimeMillis()` inside `recordTempFetch`/`recordStepsFetch`/`recordAutoSend` with `clock.now().toEpochMilliseconds()`. Keep every `KEY_*` constant string **unchanged** (on-disk compatibility). Preserve the legacy `activeFace` migration logic verbatim, swapping only the storage calls.

- [ ] **Step 2:** **Create** `androidMain/.../prefs/AndroidSettings.kt`:

```kotlin
package com.blizzardcaron.freeolleefaces.prefs

import android.content.Context
import com.russhwolf.settings.Settings
import com.russhwolf.settings.SharedPreferencesSettings

/** Builds the app's [Settings] over the legacy SharedPreferences file so existing data is read. */
fun appSettings(context: Context): Settings {
    val sp = context.applicationContext.getSharedPreferences("freeollee_faces_prefs", Context.MODE_PRIVATE)
    return SharedPreferencesSettings(sp)
}
```

> `"freeollee_faces_prefs"` must equal the existing `Prefs.FILE` constant — confirm and keep identical.

- [ ] **Step 3:** Update every construction site from `Prefs(context)` to `Prefs(appSettings(context))`. (Workers/receivers run in androidMain, so `appSettings` is in scope.)
- [ ] **Step 4:** `git mv Prefs.kt` to commonMain.
- [ ] **Step 5:** Run `./gradlew :app:assembleDebug` → SUCCESS. `installDebug` and confirm saved settings (watch, interval, coords) survive an app restart on device.
- [ ] **Step 6:** Commit: `git commit -am "refactor: move Prefs to commonMain backed by multiplatform-settings"`.

### Task 4.2: Move `TimerSetsRepository` to commonMain, backed by `Settings`

**Files:**
- Move + edit: `androidMain/.../timer/TimerSetsRepository.kt` → `commonMain/...`
- Modify: construction sites `TimerSetsRepository(context)` (grep to confirm; likely only `MainActivity`).

- [ ] **Step 1:** Change constructor to `class TimerSetsRepository(private val settings: Settings)`. Replace `sp.getString(KEY_SETS, null)` → `settings.getStringOrNull(KEY_SETS)`, `sp.edit { putString(...) }` → `settings.putString(...)`, `sp.getString(KEY_ACTIVE, null)` → `settings.getStringOrNull(KEY_ACTIVE)`. Keep `MAX_SETS`, `KEY_SETS`, `KEY_ACTIVE` and the `"timer_sets"` file name. Because this used a **separate** SharedPreferences file (`"timer_sets"`), add an overload in `AndroidSettings.kt`:

```kotlin
fun timerSettings(context: Context): Settings =
    SharedPreferencesSettings(context.applicationContext.getSharedPreferences("timer_sets", Context.MODE_PRIVATE))
```

- [ ] **Step 2:** Update `TimerSetsRepository(context)` → `TimerSetsRepository(timerSettings(context))` at each site.
- [ ] **Step 3:** `git mv` to commonMain. Run `./gradlew :app:assembleDebug` → SUCCESS; confirm existing timer sets still load on device.
- [ ] **Step 4:** Commit: `git commit -am "refactor: move TimerSetsRepository to commonMain backed by multiplatform-settings"`.

### Task 4.3: `BleClient` interface (common) + `AndroidBleClient` (android)

**Files:**
- Create: `commonMain/.../ble/BleClient.kt`
- Move + rename: `androidMain/.../ble/OlleeBleClient.kt` → `androidMain/.../ble/AndroidBleClient.kt` implementing `BleClient`
- Modify: construction sites of `OlleeBleClient(context)` (`MainActivity`, `auto/AutoUpdateWorker`, `notifications/NotificationCountService` — grep `OlleeBleClient(`).

- [ ] **Step 1:** **Create** `commonMain/.../ble/BleClient.kt`:

```kotlin
package com.blizzardcaron.freeolleefaces.ble

interface BleClient {
    suspend fun send(deviceAddress: String, value: String): Result<Unit>
    suspend fun send(deviceAddress: String, value: String, target: Int): Result<Unit>
    suspend fun sendPacket(deviceAddress: String, packet: ByteArray): Result<Unit>
}
```

- [ ] **Step 2:** In `AndroidBleClient.kt`, rename `class OlleeBleClient(context)` → `class AndroidBleClient(private val context: Context) : BleClient`, mark the three public `send*` functions `override`. Body unchanged (it already uses `OlleeProtocol` from commonMain).
- [ ] **Step 3:** Replace each `OlleeBleClient(context)` with `AndroidBleClient(context)`, typed as `BleClient` where stored.
- [ ] **Step 4:** Run `./gradlew :app:assembleDebug` → SUCCESS; `installDebug`; confirm a manual send still reaches the watch. Commit: `git commit -am "refactor: extract BleClient interface; AndroidBleClient implements it"`.

### Task 4.4: `StepsProvider` interface (common) + `AndroidStepsProvider` (android)

**Files:**
- Create: `commonMain/.../health/StepsProvider.kt`
- Move + rename: `androidMain/.../health/StepsRepository.kt` → `androidMain/.../health/AndroidStepsProvider.kt`
- Modify: construction sites of `StepsRepository(context)` (`MainActivity`, `auto/AutoUpdateWorker` — grep).

- [ ] **Step 1:** **Create** `commonMain/.../health/StepsProvider.kt`:

```kotlin
package com.blizzardcaron.freeolleefaces.health

interface StepsProvider {
    enum class Availability { UNAVAILABLE, UPDATE_REQUIRED, AVAILABLE }
    fun availability(): Availability
    suspend fun hasReadPermission(): Boolean
    suspend fun todaySteps(): Result<Long>
}
```

- [ ] **Step 2:** In `AndroidStepsProvider.kt`: `class AndroidStepsProvider(context: Context) : StepsProvider`. Replace the inner `enum class Availability` with `StepsProvider.Availability` (use the interface enum; update `availability()`'s `when` to return `StepsProvider.Availability.*`). Make `availability`, `hasReadPermission`, `todaySteps` `override`. Change `todaySteps(zone: ZoneId = ...)` to `override suspend fun todaySteps(): Result<Long>` and compute the day window with the system zone internally (it already defaulted to `ZoneId.systemDefault()`). Keep `PERMISSIONS` and `companion object` in this Android class (the permission launcher glue in `MainActivity` references `AndroidStepsProvider.PERMISSIONS`).
- [ ] **Step 3:** Update call sites: `StepsRepository(context)` → `AndroidStepsProvider(context)`; `StepsRepository.PERMISSIONS` → `AndroidStepsProvider.PERMISSIONS`; `StepsRepository.Availability.*` → `StepsProvider.Availability.*`; `stepsRepo.todaySteps()` calls drop any zone argument.
- [ ] **Step 4:** Run `./gradlew :app:assembleDebug` → SUCCESS; `installDebug`; confirm steps still read with Health permission granted. Commit: `git commit -am "refactor: extract StepsProvider interface; AndroidStepsProvider implements it"`.

### Task 4.5: `LocationProvider` interface (common) + `AndroidLocationProvider` (android)

**Files:**
- Create: `commonMain/.../location/LocationProvider.kt` (and move `Coords` there)
- Move + rename: `androidMain/.../location/LocationSource.kt` → `androidMain/.../location/AndroidLocationProvider.kt`
- Modify: `LocationSource(context)` sites (`MainActivity`, possibly a worker — grep `LocationSource(`).

- [ ] **Step 1:** **Create** `commonMain/.../location/LocationProvider.kt`:

```kotlin
package com.blizzardcaron.freeolleefaces.location

data class Coords(val lat: Double, val lng: Double, val accuracyM: Float?, val provider: String?)

interface LocationProvider {
    suspend fun fetch(timeoutMs: Long = 10_000): Result<Coords>
}
```

- [ ] **Step 2:** In `AndroidLocationProvider.kt`: remove the local `data class Coords` (now in common), `class AndroidLocationProvider(private val context: Context) : LocationProvider`, mark `fetch` `override`. Body unchanged otherwise.
- [ ] **Step 3:** Update `LocationSource(context)` → `AndroidLocationProvider(context)`.
- [ ] **Step 4:** `./gradlew :app:assembleDebug` → SUCCESS; `installDebug`; confirm "Use my location" still resolves coordinates on device. Commit: `git commit -am "refactor: extract LocationProvider interface; AndroidLocationProvider implements it"`.

### Task 4.6: `NotificationAccess` interface (common) + Android implementation

**Files:**
- Create: `commonMain/.../notifications/NotificationAccessChecker.kt`
- Move + edit: `androidMain/.../notifications/NotificationAccess.kt` → implement the interface
- Modify: call sites of `NotificationAccess.isGranted(context)` (`MainActivity` via `isNotificationAccessGranted`).

- [ ] **Step 1:** **Create** `commonMain/.../notifications/NotificationAccessChecker.kt`:

```kotlin
package com.blizzardcaron.freeolleefaces.notifications

interface NotificationAccessChecker {
    fun isGranted(): Boolean
}
```

- [ ] **Step 2:** In androidMain, replace the `object NotificationAccess` with `class AndroidNotificationAccess(private val context: Context) : NotificationAccessChecker { override fun isGranted() = ... }` (keep the existing enabled-listeners check body). Update `MainActivity.isNotificationAccessGranted(context)` to build/hold an `AndroidNotificationAccess(context)` and call `isGranted()`.
- [ ] **Step 3:** `./gradlew :app:assembleDebug` → SUCCESS. Commit: `git commit -am "refactor: extract NotificationAccessChecker interface; Android implementation"`.

## Phase 5 — Extract `AppViewModel`

Goal: lift all orchestration out of `MainActivity.AppRoot` into a commonMain `AppViewModel` that depends only on the Phase-4 interfaces. After this phase `AppRoot` only: constructs the Android implementations, owns the permission launchers, observes the ViewModel's state, and forwards UI callbacks to ViewModel methods. The UI composables are still in androidMain (moved in Phase 6) but now read `viewModel.state` and call `viewModel.*`.

### Task 5.0: Extract the pure UI data classes to commonMain (prerequisite)

`HomeState` is declared inside `ui/HomeScreen.kt` (line ~39) and `HomeCallbacks` (~77); `SettingsCallbacks` is in `ui/SettingsScreen.kt` (~32). They are plain data classes (state fields + function-typed callbacks) with no Android dependency, so `AppViewModel` can reference them from commonMain only if they live there first.

**Files:**
- Create: `commonMain/.../ui/HomeState.kt`
- Create: `commonMain/.../ui/Callbacks.kt`
- Modify: `androidMain/.../ui/HomeScreen.kt`, `ui/SettingsScreen.kt` (remove the moved declarations; keep importing them by the same package).

- [ ] **Step 1:** Cut the `data class HomeState(...)` block out of `HomeScreen.kt` into `commonMain/.../ui/HomeState.kt` (same package `com.blizzardcaron.freeolleefaces.ui`). It references `PreviewState`, `ActiveFace`, `TempUnit` — all already in commonMain.
- [ ] **Step 2:** Cut `HomeCallbacks` (from `HomeScreen.kt`) and `SettingsCallbacks` (from `SettingsScreen.kt`) into `commonMain/.../ui/Callbacks.kt` (same package). These hold only `() -> Unit`/`(T) -> Unit` lambdas — pure.
- [ ] **Step 3:** The screen files keep their `import` of these types implicitly (same package). Confirm no leftover references break.
- [ ] **Step 4:** `./gradlew :app:assembleDebug` → SUCCESS; `./gradlew :app:testDebugUnitTest` → PASS. Commit: `git commit -am "refactor: extract HomeState + callbacks data classes to commonMain"`.

### Task 5.1: Add a `Scheduler` interface so the ViewModel can request a reschedule

**Files:**
- Create: `commonMain/.../auto/Scheduler.kt`
- Create: `androidMain/.../auto/AndroidScheduler.kt`

- [ ] **Step 1:** **Create** `commonMain/.../auto/Scheduler.kt`:

```kotlin
package com.blizzardcaron.freeolleefaces.auto

/** Lets shared code request a background reschedule without knowing about WorkManager. */
interface Scheduler {
    fun reschedule()
}
```

- [ ] **Step 2:** **Create** `androidMain/.../auto/AndroidScheduler.kt`:

```kotlin
package com.blizzardcaron.freeolleefaces.auto

import android.content.Context

class AndroidScheduler(private val context: Context) : Scheduler {
    override fun reschedule() = AutoUpdateScheduler.reschedule(context)
}
```

- [ ] **Step 3:** `./gradlew :app:assembleDebug` → SUCCESS. Commit: `git commit -am "feat: add Scheduler interface + AndroidScheduler"`.

### Task 5.2: Create the `AppViewModel` skeleton with injected dependencies and state

**Files:**
- Create: `commonMain/.../AppViewModel.kt`

`HomeState`, `HomeCallbacks`, `SettingsCallbacks` currently live with the UI (`ui/HomeScreen.kt`, etc.). `HomeState` is shared data → keep it in `ui/` (it moves to commonMain in Phase 6); for now the ViewModel references it by its existing package path. Confirm `HomeState`'s package before writing.

- [ ] **Step 1:** **Create** `commonMain/.../AppViewModel.kt` with the dependency list, state, and event channel. (Method bodies are filled in Task 5.3.)

```kotlin
package com.blizzardcaron.freeolleefaces

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.blizzardcaron.freeolleefaces.auto.Scheduler
import com.blizzardcaron.freeolleefaces.ble.BleClient
import com.blizzardcaron.freeolleefaces.health.StepsProvider
import com.blizzardcaron.freeolleefaces.location.LocationProvider
import com.blizzardcaron.freeolleefaces.notifications.NotificationAccessChecker
import com.blizzardcaron.freeolleefaces.prefs.Prefs
import com.blizzardcaron.freeolleefaces.timer.TimerSetsRepository
import com.blizzardcaron.freeolleefaces.ui.HomeState
import com.blizzardcaron.freeolleefaces.ui.Screen
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

class AppViewModel(
    private val prefs: Prefs,
    private val ble: BleClient,
    private val steps: StepsProvider,
    private val location: LocationProvider,
    private val notificationAccess: NotificationAccessChecker,
    private val timerRepo: TimerSetsRepository,
    private val scheduler: Scheduler,
) : ViewModel() {

    var state by mutableStateOf(initialState())
        private set

    var screen by mutableStateOf<Screen>(Screen.Home)
        private set

    private val _events = Channel<String>(Channel.BUFFERED)   // snackbar messages
    val events = _events.receiveAsFlow()

    private fun update(transform: (HomeState) -> HomeState) { state = transform(state) }
    private fun showSnackbar(message: String) { viewModelScope.launch { _events.send(message) } }

    private fun initialState(): HomeState = TODO("filled in Task 5.3 from the current AppRoot initializer")
}
```

- [ ] **Step 2:** `./gradlew :app:assembleDebug` will FAIL (the `TODO()` and unused methods are fine to compile, but it references `HomeState` which is still in androidMain). **Do not build yet** — Task 5.3 completes the move. Proceed directly.

### Task 5.3: Move orchestration logic from `AppRoot` into `AppViewModel`

This is a mechanical lift. Read `MainActivity.AppRoot` (lines ~98–768) and move the following into `AppViewModel`, applying the substitution rules below. Keep behavior identical.

**Functions to move verbatim (then substitute):** `clockTime`, `locLabel`, `stepsHuman` (file-level helpers → private members or top-level in the VM file), `refreshTimers`, `newTimerSet`, `sendTimerSet`, `validCoords`, `pushIfWatch`, `pushCountIfWatch`, `sendCustom`, `interval`, `tempNextText`, `refreshTemp`, `refreshSun`, `refreshSteps`, `refreshActive`, `refreshAllPreviews`, `activate`, `setNotificationsEnabled`, `onCoordEdit`, `selectWatch`-less coordination (the permission part stays in Activity; the fetch part becomes `useMyLocationFetch()`), and the `initialState()` body.

**Substitutions:**

| In AppRoot | In AppViewModel |
|---|---|
| `scope.launch { … }` (from `rememberCoroutineScope`) | `viewModelScope.launch { … }` |
| `showSnackbar("…")` | `showSnackbar("…")` (the VM's private method) |
| `context` usages | removed — replace with injected interfaces (`ble`, `steps`, `location`, `notificationAccess`, `scheduler`, `prefs`, `timerRepo`) |
| `OlleeBleClient`/`StepsRepository`/`LocationSource`/`Prefs`/`TimerSetsRepository` locals | the injected constructor params |
| `AutoUpdateScheduler.reschedule(context)` | `scheduler.reschedule()` |
| `isNotificationAccessGranted(context)` | `notificationAccess.isGranted()` |
| `System.currentTimeMillis()` | `kotlinx.datetime.Clock.System.now().toEpochMilliseconds()` |
| `labelForAddress(context, addr)` / `bondedDevices(context)` | **stay in Activity** (need `BluetoothManager`); the VM exposes `onWatchPicked(address: String, label: String)` for the Activity to call after the user picks |

- [ ] **Step 1:** Move the listed functions into `AppViewModel`, applying substitutions. Replace `initialState()`'s `TODO()` with the current initializer body (the `HomeState(...)` block at AppRoot lines ~124–148), reading from `prefs` and using `notificationAccess.isGranted()`; replace `labelForAddress(context, prefs.watchAddress)` with `prefs.watchAddress?.let { "Watch: $it" } ?: "Watch: none selected"` (the human-friendly device name is filled later via `onWatchPicked`/an Activity-provided label — see Step 3).

- [ ] **Step 2:** Add ViewModel methods for the bits the Activity must trigger:

```kotlin
fun setScreen(s: Screen) { screen = s }
fun onWatchPicked(address: String, label: String) {
    prefs.watchAddress = address
    update { it.copy(watchLabel = label, watchSelected = true) }
    when (state.activeFace) {
        com.blizzardcaron.freeolleefaces.auto.ActiveFace.CUSTOM ->
            prefs.customText.takeIf { it.isNotEmpty() }?.let { sendCustom(it) }
        else -> refreshActive(force = false, push = true)
    }
}
fun onLocationFetched(lat: Double, lng: Double) {
    prefs.lastLat = lat; prefs.lastLng = lng
    prefs.lastLocationFetchedMs = Clock.System.now().toEpochMilliseconds()
    update { it.copy(lat = lat.toString(), lng = lng.toString(), locating = false,
        locationLabel = locLabel(lat, lng), locationFreshness = "just now") }
    refreshActive(force = true, push = false)
}
fun onLocationDenied() { showSnackbar("Location permission denied — enter coordinates manually.") }
fun setLocating(v: Boolean) { update { it.copy(locating = v) } }
suspend fun fetchLocation() = location.fetch()  // Activity calls when it holds permission
fun onResumeNotifications() {
    update { it.copy(notificationCount = prefs.notificationCount,
        notificationAccessGranted = notificationAccess.isGranted(),
        notificationsEnabled = prefs.notificationsEnabled) }
}
fun startDashboardPolling() { /* called from a LaunchedEffect in the root composable */ }
```

> The location-bootstrap `LaunchedEffect(Unit)` block (AppRoot ~428–493) splits: the *permission check* stays in the Activity; the *fetch + state update* becomes `onLocationFetched`/`setLocating`/`fetchLocation`. The dashboard-polling `LaunchedEffect(screen)` (AppRoot ~497–504) stays in the root composable but calls `viewModel.refreshAllPreviews()`.

- [ ] **Step 3:** Build `HomeCallbacks`/`SettingsCallbacks` in the Activity (Task 5.4) by delegating to `viewModel.*`. Expose `refreshAllPreviews`, `refreshActive`, `refreshTemp`, `refreshSteps`, `sendCustom`, `activate`, `setNotificationsEnabled`, `onCoordEdit`, `newTimerSet`, `sendTimerSet`, `refreshTimers`, timer CRUD, and `tempNextText` as `public` ViewModel members.

- [ ] **Step 4:** Do not build until Task 5.4 rewires the Activity (the two change together).

### Task 5.4: Slim `MainActivity.AppRoot` to construct deps, observe state, own permissions

**Files:**
- Modify: `androidMain/.../MainActivity.kt`

- [ ] **Step 1:** In `AppRoot`, replace the local object creation + state with ViewModel creation:

```kotlin
val context = LocalContext.current
val viewModel = remember {
    AppViewModel(
        prefs = Prefs(appSettings(context)),
        ble = AndroidBleClient(context),
        steps = AndroidStepsProvider(context),
        location = AndroidLocationProvider(context),
        notificationAccess = AndroidNotificationAccess(context),
        timerRepo = TimerSetsRepository(timerSettings(context)),
        scheduler = AndroidScheduler(context),
    )
}
val state = viewModel.state
val screen = viewModel.screen
```

- [ ] **Step 2:** Collect snackbar events:

```kotlin
LaunchedEffect(Unit) {
    viewModel.events.collect { msg -> snackbarHostState.showSnackbar(msg) }
}
```

- [ ] **Step 3:** Keep all `rememberLauncherForActivityResult` launchers in the Activity. Rewire their result callbacks to ViewModel methods, e.g. the location launcher success calls `scope.launch { viewModel.setLocating(true); viewModel.fetchLocation().onSuccess { viewModel.onLocationFetched(it.lat, it.lng) }.onFailure { viewModel.setLocating(false); /* snackbar via VM */ } }`; the watch picker's `onPick` calls `viewModel.onWatchPicked(device.address, "Watch: ${device.name ?: device.address}")`; the health launcher calls `viewModel.refreshSteps(push = state.activeFace == ActiveFace.STEPS)`.

- [ ] **Step 4:** Replace the location-bootstrap `LaunchedEffect(Unit)` with a version that does the Android permission check, then calls `viewModel.fetchLocation()`/`onLocationFetched` (logic preserved). Keep the dashboard-polling and notifications `LaunchedEffect(screen)` effects, calling `viewModel.refreshAllPreviews()` / `viewModel.onResumeNotifications()`. Call `viewModel.scheduler.reschedule()` is wrong — instead the VM already calls reschedule in `activate`/settings changes; the initial reschedule moves into a `viewModel.onStart()` you add and call from a `LaunchedEffect(Unit)`.

- [ ] **Step 5:** Build `HomeCallbacks`/`SettingsCallbacks` delegating to `viewModel.*` and `screen` navigation via `viewModel.setScreen(...)`. Keep the `when (screen)` block and dialogs as-is, sourcing `state` from `viewModel.state`.

- [ ] **Step 6:** `./gradlew :app:assembleDebug` → SUCCESS; `./gradlew :app:testDebugUnitTest` → PASS.
- [ ] **Step 7:** `./gradlew :app:installDebug` — full manual smoke: each face activates and pushes; Update-now; custom send; location; steps; timer create/send; notifications toggle. Behavior must match pre-refactor.
- [ ] **Step 8:** Commit: `git commit -am "refactor: extract AppViewModel; MainActivity now only wires platform + permissions"`.

### Task 5.5: Route the background worker through shared orchestration (optional consolidation)

**Files:**
- Modify: `androidMain/.../auto/AutoUpdateWorker.kt`

- [ ] **Step 1:** Confirm `AutoUpdateWorker` duplicates fetch/send logic now living in `AppViewModel`. If the duplication is small (it builds its own `Prefs`/`AndroidBleClient` and computes the payload), leave it — it must run headless without a ViewModel. If you want sharing, extract the pure payload-building steps it shares with the VM into a common `UpdateEngine` object and have both call it. **Recommendation:** defer unless duplication is significant; note it in the spec's open items. If skipped, no code change — just verify the worker still compiles and runs after the Phase-4 constructor changes.
- [ ] **Step 2:** `./gradlew :app:assembleDebug` → SUCCESS. Commit only if changed.

## Phase 6 — UI → commonMain (Compose Multiplatform)

Goal: move the Compose screens and theme to commonMain. Good news from the survey: **no `@Preview`, no `R.`/`stringResource`/`painterResource`, no resource files** in the UI. Only three Android couplings exist and each is fixed in its own task:
1. `HomeScreen` reads the app version via `LocalContext`/`packageManager` (line ~235).
2. `SettingsScreen` opens an Android `TimePickerDialog` via `showTimePicker(context, …)` (lines ~150–172).
3. `BondedDevicesDialog` takes `List<BluetoothDevice>` (Android type).

Compose Multiplatform re-exports the `androidx.compose.runtime/foundation/material3/ui` packages, so the vast majority of imports are unchanged when the files move to commonMain.

### Task 6.1: Make the app-version label injectable (remove `LocalContext` from HomeScreen)

**Files:**
- Modify: `ui/HomeScreen.kt`, `commonMain/.../ui/HomeState.kt`, `MainActivity.kt`

- [ ] **Step 1:** Add `val versionLabel: String = ""` to `HomeState` (commonMain). Read `ui/VersionInfo.kt` (the `versionLabel(name, packageName)` helper) — if it's pure, move it to commonMain too; the *source* of the version string stays Android.
- [ ] **Step 2:** In `HomeScreen.kt`, delete the `LocalContext`/`packageManager` block (~233–240) and render `state.versionLabel` instead.
- [ ] **Step 3:** In `MainActivity`, compute the version once (`context.packageManager.getPackageInfo(context.packageName, 0).versionName`) and feed it into the ViewModel's initial state — add a `versionLabel` constructor param to `AppViewModel` (or a `setVersionLabel(...)` called from a `LaunchedEffect(Unit)`), and include it in `initialState()`.
- [ ] **Step 4:** `./gradlew :app:assembleDebug` → SUCCESS. Commit: `git commit -am "refactor: inject app version label instead of reading Context in HomeScreen"`.

### Task 6.2: Replace the Android time picker with a Compose Multiplatform TimePicker

**Files:**
- Modify: `ui/SettingsScreen.kt`
- Create: `commonMain/.../ui/TimePickerDialog.kt`

- [ ] **Step 1:** **Create** a common `TimePickerDialog` composable wrapping Material3's multiplatform `TimePicker` inside an `AlertDialog`, returning minute-of-day:

```kotlin
package com.blizzardcaron.freeolleefaces.ui

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.window.Dialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimePickerDialog(initialMinuteOfDay: Int, onConfirm: (Int) -> Unit, onDismiss: () -> Unit) {
    val st = rememberTimePickerState(
        initialHour = initialMinuteOfDay / 60,
        initialMinute = initialMinuteOfDay % 60,
        is24Hour = false,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onConfirm(st.hour * 60 + st.minute) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = { TimePicker(state = st) },
    )
}
```

- [ ] **Step 2:** In `SettingsScreen.kt`, delete the `showTimePicker(context: android.content.Context, …)` helper and the `LocalContext` usage. Add local state (`var pickingStart by remember { mutableStateOf(false) }`, `pickingEnd`) and show `TimePickerDialog(...)` when set, calling `callbacks.onSleepStartChange` / `onSleepEndChange` on confirm. The two clock buttons set the flags instead of calling `showTimePicker`.
- [ ] **Step 3:** `./gradlew :app:assembleDebug` → SUCCESS; `installDebug`; confirm the sleep-window time pickers work on device. Commit: `git commit -am "refactor: replace Android TimePickerDialog with Compose Multiplatform TimePicker"`.

### Task 6.3: Make `BondedDevicesDialog` use a common device model

**Files:**
- Modify: `ui/BondedDevicesDialog.kt`
- Modify: `MainActivity.kt`

- [ ] **Step 1:** Add a common model at the top of `BondedDevicesDialog.kt` (or in a small `ui/BondedDevice.kt`):

```kotlin
data class BondedDevice(val name: String?, val address: String)
```

Change the composable signature to `devices: List<BondedDevice>, onPick: (BondedDevice) -> Unit`, render `device.name ?: device.address`. Remove the `android.*` imports and `@SuppressLint`.
- [ ] **Step 2:** In `MainActivity.bondedDevices(context)`, map `BluetoothDevice` → `BondedDevice(it.name, it.address)` (keep `@SuppressLint("MissingPermission")` on the Android mapping function). The `onPick` handler calls `viewModel.onWatchPicked(device.address, "Watch: ${device.name ?: device.address}")`.
- [ ] **Step 3:** `./gradlew :app:assembleDebug` → SUCCESS. Commit: `git commit -am "refactor: BondedDevicesDialog uses common BondedDevice model"`.

### Task 6.4: Move all screens + theme to commonMain

**Files (git-move):**
- `ui/HomeScreen.kt`, `ui/SettingsScreen.kt`, `ui/FacesListScreen.kt`, `ui/NotificationsScreen.kt`, `ui/TimerSetsScreen.kt`, `ui/TimerSetEditScreen.kt`, `ui/BondedDevicesDialog.kt`, `ui/theme/Theme.kt`, `ui/VersionInfo.kt` (if pure) → `commonMain/.../ui/...`

- [ ] **Step 1:** `git mv` each file from `androidMain/.../ui/` to `commonMain/.../ui/` (and `ui/theme/`).
- [ ] **Step 2:** For each moved file, confirm no remaining `androidx.compose.ui.platform.LocalContext`, `androidx.compose.ui.tooling.preview.*`, or `android.*` imports (`grep -rn "LocalContext\|^import android\.\|tooling.preview" app/src/commonMain/kotlin/.../ui`). Resolve any stragglers (there should be none after 6.1–6.3).
- [ ] **Step 3:** `Theme.kt` is just `MaterialTheme(content = content)` — compiles unchanged in common (Material3 is multiplatform). If a future dynamic-color (Android `dynamicColorScheme`) is ever wanted it would be an `expect/actual`; not now.
- [ ] **Step 4:** `./gradlew :app:assembleDebug` → SUCCESS; `./gradlew :app:testDebugUnitTest` → PASS.
- [ ] **Step 5:** `./gradlew :app:installDebug` — full UI smoke on device: all screens render and navigate; theming intact. Commit: `git commit -am "refactor: move Compose UI screens + theme to commonMain"`.

### Task 6.5: Shrink `MainActivity` to a thin host

**Files:**
- Modify: `androidMain/.../MainActivity.kt`

- [ ] **Step 1:** `MainActivity` should now contain only: the `ComponentActivity` + `setContent { FreeOlleeFacesTheme { Scaffold { AppRoot(...) } } }`, the ViewModel construction, the permission launchers, the Bluetooth bonded-device mapping, the version-string read, and the `LaunchedEffect`s that bridge permissions to the ViewModel. All face/orchestration logic is gone (now in `AppViewModel`). Verify the file is dramatically smaller (target: under ~250 lines vs ~800).
- [ ] **Step 2:** `./gradlew :app:assembleDebug && ./gradlew :app:testDebugUnitTest` → both pass.
- [ ] **Step 3:** Commit (if any cleanup edits): `git commit -am "refactor: MainActivity reduced to platform host + permission glue"`.

## Phase 7 — Cleanup, docs, release verification

### Task 7.1: Remove dead dependencies and confirm no stragglers

**Files:**
- Modify: `gradle/libs.versions.toml`, `app/build.gradle.kts`

- [ ] **Step 1:** Verify no source references the removed libs: `grep -rn "org.json\|HttpURLConnection\|java.time\|SharedPreferences" app/src/commonMain` → expect **no hits** (java.time/SharedPreferences may legitimately remain in `androidMain` workers; common must be clean).
- [ ] **Step 2:** Remove any now-unused catalog entries (`org-json` already removed in Phase 3; drop `androidx-compose-bom` and the individual `androidx-compose-*` aliases if the Compose Multiplatform `compose.*` accessors fully replaced them — verify `assembleDebug` still succeeds after each removal).
- [ ] **Step 3:** `./gradlew :app:assembleDebug && ./gradlew :app:testDebugUnitTest` → both pass.
- [ ] **Step 4:** Commit: `git commit -am "build: drop dependencies superseded by the multiplatform stack"`.

### Task 7.2: Verify the signed release build still works

**Files:** none (build verification)

- [ ] **Step 1:** Confirm `applicationId` is still `com.blizzardcaron.freeolleefaces` and the debug suffix `.debug` is intact in `app/build.gradle.kts` (so existing users update cleanly and debug builds still coexist).
- [ ] **Step 2:** Confirm `.github/workflows/release.yml` still invokes a task that exists for the KMP module (it builds the release APK; the `:app` path and `assembleRelease`/`bundleRelease` task names are unchanged by the KMP conversion — verify the exact task name it calls still resolves: `./gradlew :app:tasks --all | grep -i release`).
- [ ] **Step 3:** Dry-run the release assembly locally without secrets: `./gradlew :app:assembleRelease` → SUCCESS (unsigned, since `KEYSTORE_FILE` is absent locally; the build must not fail when the signing config is absent — matches current behavior).
- [ ] **Step 4:** No commit unless the workflow needed a task-name fix.

### Task 7.3: Update documentation

**Files:**
- Modify: `README.md`
- Modify: `docs/superpowers/specs/2026-06-06-kmp-cross-platform-design.md` (mark Status: Implemented; resolve the "Open items" note about `UpdateEngine` per the Task 5.5 decision)

- [ ] **Step 1:** Update README "Building" section: note the app is now Kotlin Multiplatform with a single Android target; `./gradlew :app:assembleDebug` is unchanged. Add a short "Cross-platform status" line: logic + Compose UI are in `commonMain`; iOS is a future step (no iOS targets in the build yet).
- [ ] **Step 2:** Commit: `git commit -am "docs: document the KMP/Compose Multiplatform structure"`.

### Task 7.4: Final full verification

- [ ] **Step 1:** `./gradlew clean :app:assembleDebug :app:testDebugUnitTest` → all green from a clean build.
- [ ] **Step 2:** `./gradlew :app:installDebug` — complete manual regression on device: every face activates and pushes to the watch; auto-update worker fires (trigger via `devtools/DevToolsReceiver` if it exposes a hook); boot persistence; notification count; steps; location; timers; sleep window. Behavior must match v0.11.0.
- [ ] **Step 3:** Merge per the finishing-a-development-branch skill.

## Phase 8 — Rebrand, complications rename, active-complication UX fix

> Spec: `docs/superpowers/specs/2026-06-06-rebrand-complications-addendum.md`. **Runs after Phase 6** so the UI files are settled in `commonMain` and the rename is a clean pass, not entangled with source-set moves. The rename tasks are mechanical (rename by identifier, verify by compile + tests); because a half-renamed type does not compile, **each rename touches all references in one task and ends green**. File locations below assume the post-Phase-6 layout (screens + `HomeState`/`ActiveFace` in `commonMain`; orchestration in `AppViewModel`); use the `grep` enumerations rather than line numbers, since prior phases moved these files.

> **Invariant for Phase 8 (release + saved-data continuity):** the `applicationId`/package `com.blizzardcaron.freeolleefaces` and the **persisted preference-key strings never change**. Rename the Kotlin constants but keep their string *values* exactly: `KEY_ACTIVE_FACE = "active_face"`, `LEGACY_NOTIFICATIONS_FACE = "NOTIFICATIONS"`, `KEY_AUTO_SOURCE = "auto_source"`. Enum constant names stay `TEMPERATURE/SUN/STEPS/CUSTOM` (their `.name` is persisted).

### Task 8.1: Rename the `ActiveFace` enum to `ActiveComplication`

**Files:**
- Rename: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/auto/ActiveFace.kt` → `auto/ActiveComplication.kt`
- Rename: `app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/auto/ActiveFaceTest.kt` → `auto/ActiveComplicationTest.kt`
- Modify: every file referencing the type (enumerate, do not trust this list): `grep -rln "ActiveFace" app/src`

- [ ] **Step 1:** `git mv` both files to their new names (enum + test).
- [ ] **Step 2:** In `ActiveComplication.kt`, rename `enum class ActiveFace` → `enum class ActiveComplication`. Keep the four constants (`TEMPERATURE/SUN/STEPS/CUSTOM`) and `fromLegacyAutoSource` unchanged. Update the KDoc wording "face" → "complication" (the `0x2F` nameplate description stays).
- [ ] **Step 3:** In `ActiveComplicationTest.kt`, rename `class ActiveFaceTest` → `class ActiveComplicationTest`; update all `ActiveFace.` references to `ActiveComplication.`. Assertions (legacy-source mapping) are unchanged.
- [ ] **Step 4:** Replace the type name everywhere else: for each file in `grep -rln "ActiveFace" app/src`, change `ActiveFace` → `ActiveComplication` (type usages, imports `...auto.ActiveFace` → `...auto.ActiveComplication`). Do **not** touch the package segment `freeolleefaces`.
- [ ] **Step 5:** Verify none remain: `grep -rn "ActiveFace" app/src` → **no hits**.
- [ ] **Step 6:** `./gradlew :app:assembleDebug :app:testDebugUnitTest` → both pass (the `ActiveComplicationTest` legacy-migration assertions still green).
- [ ] **Step 7:** Commit: `git commit -am "refactor: rename ActiveFace enum to ActiveComplication"`.

### Task 8.2: Rename the `activeFace` property/field and pref constants

**Files:**
- Modify: `prefs/Prefs.kt` (now in `commonMain` after Phase 4.1), `ui/HomeScreen.kt` (`HomeState`, now in `commonMain`), `AppViewModel`, workers/scheduler in `androidMain`, and any other hit from `grep -rln "activeFace\|KEY_ACTIVE_FACE\|LEGACY_NOTIFICATIONS_FACE" app/src`.

- [ ] **Step 1:** Rename the Kotlin property `var activeFace` → `var activeComplication` in `Prefs` and the data-class field `HomeState.activeFace` → `activeComplication`. Update every read/write site (`prefs.activeFace`, `state.activeFace`, `it.copy(activeFace = …)`, the `update { it.copy(activeFace = face) }` in `AppViewModel.activate`, worker `prefs.activeFace`, scheduler `prefs.activeFace`, HomeScreen `state.activeFace`).
- [ ] **Step 2:** Rename the constant `KEY_ACTIVE_FACE` → `KEY_ACTIVE_COMPLICATION` **keeping its value `"active_face"`**, and `LEGACY_NOTIFICATIONS_FACE` → `LEGACY_NOTIFICATIONS_COMPLICATION` **keeping its value `"NOTIFICATIONS"`**. Leave `KEY_AUTO_SOURCE` (name and value) alone.
- [ ] **Step 3:** Confirm the persisted values are intact: `grep -rn '"active_face"\|"NOTIFICATIONS"\|"auto_source"' app/src` → still present (string values unchanged).
- [ ] **Step 4:** Confirm no stale identifiers: `grep -rn "activeFace\|KEY_ACTIVE_FACE\|LEGACY_NOTIFICATIONS_FACE" app/src` → **no hits**.
- [ ] **Step 5:** `./gradlew :app:assembleDebug :app:testDebugUnitTest` → both pass.
- [ ] **Step 6:** Commit: `git commit -am "refactor: rename activeFace to activeComplication (persisted keys unchanged)"`.

### Task 8.3: Rename the complications UI (screen, composables, route, labels)

**Files:**
- Rename: `ui/FacesListScreen.kt` → `ui/ComplicationsListScreen.kt` (in `commonMain` after Phase 6.4)
- Modify: `ui/Screen.kt`, `ui/HomeScreen.kt`, `AppViewModel`/`MainActivity` route wiring — enumerate via `grep -rln "FacesList\|FaceCard\|FaceRow\|onOpenFaces" app/src`

- [ ] **Step 1:** `git mv ui/FacesListScreen.kt ui/ComplicationsListScreen.kt`. Rename `fun FacesListScreen` → `fun ComplicationsListScreen` and the private `fun FaceRow` → `fun ComplicationRow`.
- [ ] **Step 2:** In `ui/Screen.kt`, rename `data object FacesList` → `data object ComplicationsList`. Update every `Screen.FacesList` reference (route wiring in the host/`AppViewModel`).
- [ ] **Step 3:** In `ui/HomeScreen.kt`, rename the private `enum class FaceCardId` → `ComplicationCardId`, the private `fun FaceCard` → `ComplicationCard`, the local `fun toggle(id: FaceCardId)`/`expanded` types, and `HomeCallbacks.onOpenFaces` → `onOpenComplications`. Update the host's `onOpenFaces = { … Screen.FacesList }` → `onOpenComplications = { … Screen.ComplicationsList }`.
- [ ] **Step 4:** Update visible labels: the Home toolbar `Text("Faces")` → `Text("Complications")`; in `ComplicationsListScreen` the header `Text("Faces", …)` → `Text("Complications", …)`. Leave the per-row labels ("Temperature"/"Sun event"/"Steps"/"Custom") as-is.
- [ ] **Step 5:** Confirm no stale identifiers: `grep -rn "FacesList\|FaceCard\|FaceRow\|onOpenFaces\|>Faces<\|\"Faces\"" app/src` → **no hits**.
- [ ] **Step 6:** `./gradlew :app:assembleDebug :app:testDebugUnitTest` → both pass.
- [ ] **Step 7:** Commit: `git commit -am "refactor: rename faces UI to complications"`.

### Task 8.4: Fix the active-complication switching UX

**Files:**
- Create: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/auto/ComplicationLabel.kt`
- Create: `app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/auto/ComplicationLabelTest.kt`
- Modify: `ui/HomeScreen.kt` (active header + card highlight), `ui/ComplicationsListScreen.kt` (no auto-navigate), `AppViewModel` (`activate` no longer navigates)

- [ ] **Step 1: Write the failing test** for a pure label helper (single source of truth for the human label).

```kotlin
// ComplicationLabelTest.kt
package com.blizzardcaron.freeolleefaces.auto

import kotlin.test.Test
import kotlin.test.assertEquals

class ComplicationLabelTest {
    @Test fun labels_each_complication() {
        assertEquals("Temperature", ActiveComplication.TEMPERATURE.displayLabel())
        assertEquals("Sun event", ActiveComplication.SUN.displayLabel())
        assertEquals("Steps", ActiveComplication.STEPS.displayLabel())
        assertEquals("Custom", ActiveComplication.CUSTOM.displayLabel())
    }
}
```

- [ ] **Step 2: Run it, verify it fails** — Run: `./gradlew :app:testDebugUnitTest --tests "*ComplicationLabelTest*"`. Expected: FAIL (`displayLabel` unresolved).
- [ ] **Step 3: Implement the helper.**

```kotlin
// ComplicationLabel.kt
package com.blizzardcaron.freeolleefaces.auto

/** The human-readable name shown for each complication, used by both the picker and the Home "Active:" header. */
fun ActiveComplication.displayLabel(): String = when (this) {
    ActiveComplication.TEMPERATURE -> "Temperature"
    ActiveComplication.SUN -> "Sun event"
    ActiveComplication.STEPS -> "Steps"
    ActiveComplication.CUSTOM -> "Custom"
}
```

- [ ] **Step 4: Run it, verify it passes** — Run the same command. Expected: PASS. Then point the picker rows and Home cards at `displayLabel()` so labels can't drift.
- [ ] **Step 5: Add a prominent "Active" header on Home.** Just under the Home toolbar (before the cards `Column`), add a clearly-styled line bound to state, e.g.:

```kotlin
Text(
    "Active: ${state.activeComplication.displayLabel()}",
    style = MaterialTheme.typography.titleMedium,
    color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
)
```

- [ ] **Step 6: Strengthen the active card.** Replace the faint `badge = "active"` signal with an unmistakable highlight: give the active `ComplicationCard` a `MaterialTheme.colorScheme.primaryContainer` container color (or a 2.dp primary border) in addition to a readable "ACTIVE" chip, so the active card is obvious at a glance. Apply to all four cards via the existing `badge`/`active` plumbing.
- [ ] **Step 7: Remove the silent bounce.** In `AppViewModel.activate(...)`, delete the `screen = Screen.Home` line so selecting a complication updates the radio **in place** on `ComplicationsListScreen` (the radio already binds to `active = state.activeComplication`) and still performs the pref write + reschedule + push. The user returns via Back. This gives immediate visible confirmation instead of an instant jump.
- [ ] **Step 8:** `./gradlew :app:assembleDebug :app:testDebugUnitTest` → both pass.
- [ ] **Step 9:** Commit: `git commit -am "fix: make the active complication obvious and switching reflect in place"`.

### Task 8.5: Rebrand the display name to "Super FreeOllee"

**Files:**
- Modify: `app/src/androidMain/res/values/strings.xml`, `app/src/androidDebug/res/values/strings.xml` (if it overrides `app_name`)
- Modify: `app/src/androidMain/res/values/themes.xml`, `app/src/androidMain/AndroidManifest.xml` (theme reference), and any layout/manifest reference to `Theme.FreeOlleeFaces`
- Modify: `README.md`

- [ ] **Step 1:** In `strings.xml`, set `<string name="app_name">Super FreeOllee</string>`. Check `androidDebug/res/values/strings.xml` — if it overrides `app_name` (debug label), set it to `Super FreeOllee (debug)` to keep debug/release distinguishable; otherwise leave it.
- [ ] **Step 2:** Rename the style `Theme.FreeOlleeFaces` → `Theme.SuperFreeOllee` in `themes.xml`, and update the `android:theme="@style/Theme.FreeOlleeFaces"` reference in `AndroidManifest.xml`. Verify with `grep -rn "Theme.FreeOlleeFaces" app/src` → **no hits**.
- [ ] **Step 3:** Update `README.md` title/intro and the `notification_listener_label` string if it should read "Super FreeOllee notification count" (keep it recognizable to users in system settings).
- [ ] **Step 4:** Do **not** touch `applicationId`, the package path, or `namespace` in `app/build.gradle.kts`. Confirm: `grep -rn "applicationId\|namespace" app/build.gradle.kts` still shows `com.blizzardcaron.freeolleefaces`.
- [ ] **Step 5:** `./gradlew :app:assembleDebug` → passes.
- [ ] **Step 6:** Commit: `git commit -am "feat: rebrand display name to Super FreeOllee"`.

### Task 8.6: On-device verification of the rebrand, rename, and switch fix

- [ ] **Step 1:** `./gradlew :app:installDebug`.
- [ ] **Step 2:** Launcher icon now reads "Super FreeOllee (debug)"; the toolbar/picker read "Complications".
- [ ] **Step 3:** On Home, the "Active: <name>" header and the highlighted active card make the current complication obvious at a glance.
- [ ] **Step 4:** Open Complications, pick a different one: the radio updates **in place** (no bounce), the watch nameplate updates, and after returning to Home the header + highlight reflect the new selection.
- [ ] **Step 5:** Force-stop and relaunch: the new selection persists (proves the unchanged `"active_face"` key still loads). A user upgrading from the prior build keeps their previous selection.
- [ ] **Step 6:** If the watch nameplate fails to change even though the app reflects the switch, capture logs — that is the separate BLE-push fault noted in the addendum spec, to be triaged on its own.

## Self-review notes

- **Spec coverage:** module layout (Phase 1), pure logic → common (Phase 2), de-Java/Ktor/serialization (Phase 3), expect/actual-style interfaces + Settings storage (Phase 4), AppViewModel extraction (Phase 5), UI → CMP (Phase 6), cleanup + release continuity (Phase 7), rebrand + complications rename + switch-UX fix (Phase 8, per the addendum spec). The Hybrid background decision is honored — workers stay in androidMain (Task 5.5).
- **iOS:** intentionally absent — no `iosMain`, no Kotlin/Native targets, per spec non-goals and the Pi pre-flight.
- **Verification gates:** every task ends with `assembleDebug` + tests; device smoke after Phases 1, 3, 4, 5, 6, 7, 8.
- **Phase 8 continuity invariants:** `applicationId`/package frozen; persisted key *strings* (`active_face`, `NOTIFICATIONS`, `auto_source`) and enum constant names unchanged so existing users keep their selection. Only Kotlin identifiers and display strings change.
- **Known soft spots (verify during execution):** the Compose Multiplatform ↔ Kotlin 2.2.10 ↔ AGP 9.1.1 version matrix (Phase 1 Task 1.4); the Ktor timeout/exception-type mapping (Phase 3 Task 3.5); and whether the switch "doesn't work" symptom is fully resolved by the UX fix or masks a separate BLE-push fault (Phase 8 Task 8.6 Step 6).


# Cross-platform migration to Kotlin Multiplatform + Compose Multiplatform

**Date:** 2026-06-06
**Status:** Approved (design)
**Branch:** `feat/kmp-cross-platform`

## Summary

Convert FreeOllee Faces from a single-platform Android app (Kotlin + Jetpack Compose)
into a **Kotlin Multiplatform (KMP)** app whose **logic and Compose UI live in
`commonMain`**, with all Android-specific I/O behind `expect`/`actual` boundaries. The
end state is structured so that iOS becomes a mostly-mechanical addition later (built on
a MacBook) — but **no iOS targets are added to the build in this project**.

This direction replaces the originally-requested `react-strict-dom`/React Native path.
RSD is a UI-only layer; it does nothing for the app's hard parts (BLE, Health Connect,
notification listening, background work) and those would be rewritten per-platform
regardless. KMP/Compose Multiplatform shares the *hard* logic (protocol/CRC, sun math,
orchestration) with iOS, builds entirely with Gradle (no Node/Metro — important on the
Raspberry Pi 500 dev host), and needs no iOS toolchain until the iOS phase.

## Goals

- All portable logic and the Compose UI live in `commonMain`.
- Every Android-privileged capability sits behind an interface with an Android `actual`.
- The autonomous background engine (the app's reason to exist for GrapheneOS users)
  stays **native Kotlin** — reliability is not put at risk.
- The signed-release update path for existing users is preserved unchanged.
- The Pi build stays fast: Gradle-only, single Android target, no Kotlin/Native download.

## Non-goals (deferred to a future, MacBook-based spec)

- iOS source sets (`iosMain`), CoreBluetooth / HealthKit / CoreLocation actuals, iOS UI
  shell, `BGTaskScheduler` background engine.
- Web or desktop targets.
- Notification-count reading on iOS — Android-privileged (`NotificationListenerService`);
  it will **never** cross to iOS and remains Android-only.

## Decisions (from brainstorming)

1. **Targets:** Android now; iPhone as a future step (built on the user's wife's MacBook).
2. **Background model — Hybrid:** WorkManager, `BootReceiver`, BLE push, and scheduling
   stay in Kotlin/Android. The shared *orchestration logic* they invoke is extracted to
   common code so iOS can reuse it later.
3. **Tech choice — KMP + Compose Multiplatform** (not react-strict-dom / React Native).
4. **Scope — share logic AND UI now** via Compose Multiplatform.

## Architecture

### Module layout

Keep the module path **`:app`** and the **`applicationId` unchanged**
(`com.blizzardcaron.freeolleefaces`). Changing either would break the signed-release
update path for existing GrapheneOS users and require rewiring `.github/workflows/release.yml`.
Convert `:app` in place from a Kotlin/Android `com.android.application` module to a
**Kotlin Multiplatform + Compose Multiplatform** module that also applies
`com.android.application` (the standard CMP "composeApp" pattern, kept under the `:app`
path).

```
:app
├─ commonMain/   pure logic + Compose Multiplatform UI + state holder + expect declarations
├─ androidMain/  actual impls (BLE, Health, Location, Prefs, Scheduler) + Activity + background engine
└─ commonTest/   ported pure-logic tests (kotlin.test)
   (iosMain/ added later, on the MacBook — NOT in this project)
```

Only `androidTarget()` is declared. No `iosArm64()` / `iosX64()` → no Kotlin/Native
toolchain download, fast Gradle on the Pi, no Metro/Node.

### The central refactor: break up `AppRoot`

`MainActivity.AppRoot` (~800 lines) currently fuses three concerns: it owns the UI state
(`HomeState`), all orchestration (`refreshTemp/Sun/Steps`, `sendCustom`, `activate`,
scheduling), and Android calls (permission launchers, `Intent`, `BluetoothManager`). To
move the UI to `commonMain` it is split three ways:

| New unit | Lives in | Responsibility |
|---|---|---|
| `AppViewModel` (state holder) | commonMain | Owns `HomeState`; all `refresh*` / `send*` / `activate` orchestration; depends only on interfaces. Built on the multiplatform `androidx.lifecycle.ViewModel`. |
| Compose UI (`HomeScreen`, `SettingsScreen`, `FacesListScreen`, `NotificationsScreen`, `TimerSetsScreen`, `TimerSetEditScreen`, `BondedDevicesDialog`) | commonMain | Pure rendering of state + event callbacks. Screens already take `state` + callbacks today, so they are close to this shape. |
| Platform glue | androidMain | Permission launchers, `Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS` intent, bonded-device listing, wiring `actual` impls into the ViewModel. Remains in the `ComponentActivity`. |

The background engine (`AutoUpdateWorker`, `WatchdogWorker`) calls the same shared
orchestration (via `AppViewModel` or an extracted common `UpdateEngine`) so iOS can later
drive it from `BGTaskScheduler`.

### File disposition

**→ `commonMain` (pure, move as-is):** `ble/OlleeProtocol`, `ble/BleRetryPolicy`,
`format/TempUnit`, `format/WeatherErrorCopy`, `weather/Retry`, `weather/WeatherFetchError`,
`timer/TimerModels`, `timer/TimerSetEditing`, `notify/NotifyDecision`,
`notify/StepsFailureClassifier`, `auto/ActiveFace`, `prefs/IntervalOptions`,
`notifications/NotificationCount`, `location/LocationFreshness`, `auto/Staleness`,
`ui/PreviewState`, `ui/Screen`, all `ui/*` screens, `ui/theme/Theme`.

**→ `commonMain` (move + de-Java):**
- `sun/SunCalc`, `auto/AutoUpdateSchedule`, `format/DisplayFormatter` — `java.time` →
  kotlinx-datetime.
- `weather/OpenMeteoClient` — `HttpURLConnection` + `org.json` → Ktor + kotlinx.serialization.
- `timer/TimerSetsJson` — `org.json` → kotlinx.serialization.

**→ split (pure part common, storage behind interface):** `prefs/Prefs`,
`timer/TimerSetsRepository` — typed API/codec stays common; `SharedPreferences` backing
moves behind an interface (multiplatform-settings).

**→ `expect`/`actual` interface (Android `actual` now, iOS later):**
- `ble/OlleeBleClient` → `BleClient`
- `health/StepsRepository` → `StepsProvider`
- `location/LocationSource` → `LocationProvider`
- `notifications/NotificationAccess`

**→ `androidMain` only (Android-privileged, no iOS equivalent — by design):**
`auto/AutoUpdateScheduler`, `auto/AutoUpdateWorker`, `auto/WatchdogWorker`,
`auto/BootReceiver`, `notifications/NotificationCountService`, `notify/ErrorNotifier`,
`notify/RetryReceiver`, `devtools/DevToolsReceiver`, `MainActivity`.

## Dependency swaps

| Concern | Today | Multiplatform replacement |
|---|---|---|
| Date/time | `java.time` (`Instant`, `ZonedDateTime`, `DateTimeFormatter`) | kotlinx-datetime (`Clock`, `Instant`, `LocalTime.Format { }` for `"h:mm a"`). Android-only files (`AutoUpdateScheduler`, workers) may retain `java.time`. |
| HTTP | `HttpURLConnection` | Ktor client (commonMain); OkHttp engine (androidMain). Darwin engine added with iOS later. Preserves connect/read timeouts + retry policy. |
| JSON | `org.json` | kotlinx.serialization (`@Serializable`); removes the `org.json` dependency. |
| Key-value prefs | `SharedPreferences` | multiplatform-settings (russhwolf), `SharedPreferences`-backed on Android. `Prefs` keeps its typed API; only the backing store changes. |
| Coroutines | `kotlinx-coroutines-android` | `kotlinx-coroutines-core` in common (already multiplatform). |
| Number formatting | `String.format("%.1f", …)`, `"%,d"`, `Locale.US` | small commonMain helpers (`formatDecimal`, grouped-int). No locale library needed. |
| UI toolkit | `androidx.compose.*` (Jetpack BOM) | `org.jetbrains.compose` Compose Multiplatform (`compose.material3` re-exports Material3). |
| Tests | JUnit4 + `org.junit.Assert` | kotlin.test in `commonTest` (mechanical assert swaps). |

The VERSION-file `versionCode` derivation and the CI signing config move into the KMP
module's `android { }` block unchanged.

## Phasing

Each phase ends green: builds, unit tests pass, and the debug APK installs and runs.

1. **Module conversion** — flip `:app` to `kotlin("multiplatform")` + `org.jetbrains.compose`,
   keep all sources in `androidMain` first. App builds and runs identically; no behavior
   change. (Highest-risk Gradle step, deliberately isolated.)
2. **Pure logic → commonMain** — move the no-dependency files; port their tests to
   `commonTest`. Large, low-risk, high-confidence.
3. **De-Java the portable files** — kotlinx-datetime (`SunCalc`, `AutoUpdateSchedule`,
   `DisplayFormatter`), Ktor (`OpenMeteoClient`), kotlinx.serialization (`TimerSetsJson`).
   Tests prove parity.
4. **Storage + platform interfaces** — `expect`/`actual` for `BleClient`, `StepsProvider`,
   `LocationProvider`, `NotificationAccess`, and the `Prefs`/`TimerSetsRepository` backing.
5. **Extract `AppViewModel`** — lift orchestration out of `AppRoot` into a commonMain state
   holder; the Activity keeps only permission/intent glue.
6. **UI → commonMain** — move `ui/*` + `Theme` to Compose Multiplatform; `MainActivity`
   shrinks to host + glue.
7. **Cleanup** — drop dead Android-only deps, update README/docs, confirm the signed
   release build still produces an installable APK.

## Testing strategy

- Port all pure-logic tests to `commonTest` / `kotlin.test` — the safety net for phases
  2–3 (CRC, sun math, schedule, formatters, timer codec).
- After each phase: the common/Android unit-test task **and** `:app:assembleDebug` must
  pass before proceeding.
- Manual smoke on a real Ollee watch after phases 4–6 (BLE/Health/Location are not
  unit-testable) — requires the user's device.

## Risks & caveats

- **Phase 1 Gradle conversion is the highest-risk step** — KMP + Compose Multiplatform +
  AGP plugin alignment. Isolated so nothing else changes simultaneously. The plan will pin
  a known-good version matrix (Kotlin 2.2.10 already in use; Compose Multiplatform ~1.7.x;
  AGP 9.1.1). If the plugin matrix fights us, this is the one place time may be lost.
- **Compose Multiplatform with only an Android target gains little today** — the payoff is
  deferred to the iOS phase. Migration cost is paid now for iOS leverage later. (Accepted.)
- **Release continuity:** `applicationId` and signing unchanged, so existing users update
  cleanly; verified in phase 7.
- **Background reliability:** unchanged — the native WorkManager engine is retained.

## Open items for the implementation plan

- Pin the exact Compose Multiplatform and Ktor/kotlinx-datetime/kotlinx.serialization/
  multiplatform-settings versions compatible with Kotlin 2.2.10 + AGP 9.1.1.
- Confirm `androidx.lifecycle` ViewModel artifact coordinates for Compose Multiplatform.
- Decide whether orchestration lives directly on `AppViewModel` or a separate common
  `UpdateEngine` shared between the ViewModel and the background workers.

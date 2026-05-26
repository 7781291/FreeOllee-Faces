# Automatic Background Updates — Design

**Date:** 2026-05-25
**Status:** Approved by user; ready for implementation planning.
**Predecessor specs:**
- `docs/superpowers/specs/2026-05-14-freeollee-faces-app-design.md` (v0.1)
- `docs/superpowers/specs/2026-05-14-preview-and-temp-unit-design.md` (v0.2)

## Problem

Today every send is a manual tap. The user wants the watch to stay fresh on its
own: the app should push updates to the watch in the background, while the app is
closed and the screen is off. This was anticipated — the v0.2 spec's *Open
follow-ups* explicitly deferred "auto refresh on a timer."

Two kinds of data can auto-update, and they share **one** display slot on the
watch (the protocol writes a single 6-character field; temperature, sun, and
custom all overwrite it). So only **one** auto-update source is active at a time —
the "selected face."

- **Temperature** updates on a configurable interval (default hourly), with a
  configurable power-saving sleep window (default 22:00–06:00) during which it
  goes quiet.
- **Sun events** update only when the displayed value goes stale — i.e. right
  after the next sunrise/sunset passes. Because that happens at most twice a day,
  sun has no interval and no sleep window.

## Goals

- Background sends that work with the app closed and screen off, on GrapheneOS,
  without a persistent notification and without background-location permission.
- A single selected auto-update source: `OFF`, `TEMPERATURE`, or `SUN`.
- Temperature: configurable interval (default 60 min) and a configurable
  power-saving sleep window (default 22:00–06:00, enabled).
- Sun: re-send only when the current event has passed; no interval, no sleep.
- Background sends reuse the coordinates last set in the foreground (Prefs) — no
  GPS wakeups, no `ACCESS_BACKGROUND_LOCATION`.
- Survive app close, process kill, and device reboot via re-arm triggers.
- Existing manual previews + custom send remain unchanged.

## Non-goals (explicit cuts)

- **No simultaneous temp + sun.** They share one display slot; the user picks one.
  (Surfaced and confirmed during brainstorming.)
- **No foreground service and no exact alarms.** Battery-friendly WorkManager only.
  Hourly is best-effort and may drift inside WorkManager/Doze windows — accepted.
- **No fresh location fix in the background.** Saved coordinates only; the user
  refreshes location by opening the app and tapping *Use my location*.
- **No new BLE protocol / no new watch field.** Same single 6-char write as today.
- **No auto-update for custom text.** Custom remains manual only.
- **No per-event push notifications** about background activity. Observability is a
  single in-app "last auto-update" status line.

## Approach (chosen)

**Approach A — unified self-rescheduling one-shot chain via WorkManager.** One
`CoroutineWorker` performs the due send, computes the *next* fire time, and
enqueues a single unique-named `OneTimeWorkRequest` with that delay. Because the
sends are one-shots scheduled exactly when due, nothing wakes during the sleep
window. The chain is re-armed on app launch, on settings change, on
`BOOT_COMPLETED`, and by a once-daily watchdog (insurance against a dropped chain
on aggressive OEM/GrapheneOS process killing).

Alternatives considered and rejected: a `PeriodicWorkRequest` for temperature
(wakes hourly overnight just to no-op; 15-min floor; cancel/reschedule on every
interval change), and a foreground-service loop (persistent notification, higher
battery — ruled out by the battery-friendly requirement).

## Config model

New persisted fields on `prefs/Prefs.kt`:

| Key | Type | Default | Meaning |
|---|---|---|---|
| `autoSource` | `AutoSource { OFF, TEMPERATURE, SUN }` | `OFF` | The selected face. |
| `tempIntervalMinutes` | `Int` | `60` | Temperature cadence. UI floor 15 min. |
| `sleepEnabled` | `Boolean` | `true` | Power-saving window (temperature only). |
| `sleepStartMin` | `Int` (minute-of-day) | `1320` (22:00) | Sleep window start. |
| `sleepEndMin` | `Int` (minute-of-day) | `360` (06:00) | Sleep window end (may wrap midnight). |
| `lastAutoSendMs` | `Long?` | `null` | Timestamp of the last background send attempt. |
| `lastAutoSendSummary` | `String?` | `null` | e.g. `Sent '  72 F'` / `Skipped: watch unreachable`. |

`AutoSource` is a new enum in a new `auto/` package. It backs a String pref,
parsed defensively like `tempUnit` (unknown value → `OFF`).

### Semantics

- **OFF** — no background work enqueued (v0.2 behavior).
- **TEMPERATURE** — send temperature every `tempIntervalMinutes`; suppressed inside
  the sleep window when `sleepEnabled`; resumes at `sleepEndMin`.
- **SUN** — send the next sunrise/sunset, then re-send only when that event passes.
  No interval, no sleep window.

## Scheduling engine

### Pure core — `auto/AutoUpdateSchedule.kt`

No Android dependencies; unit-tested in isolation (mirrors `SunCalc`,
`OpenMeteoClient.buildUrl`, `DisplayFormatter`).

- `isInSleepWindow(minuteOfDay: Int, startMin: Int, endMin: Int): Boolean`
  - `start < end`: `start <= m < end`.
  - `start > end` (wraps midnight): `m >= start || m < end`.
  - `start == end`: `false` (no suppression).
- `nextTemperatureFire(now: ZonedDateTime, intervalMinutes: Int, sleep: SleepWindow?): ZonedDateTime`
  - Base = `now + intervalMinutes`.
  - If `sleep != null` and `isInSleepWindow(base)`, snap forward to the next
    occurrence of `sleep.endMin` at or after `base`.
- `nextSunWake(eventTime: ZonedDateTime, bufferSeconds: Long = 60): ZonedDateTime`
  = `eventTime + bufferSeconds`.

`SleepWindow` is a small data class `(startMin: Int, endMin: Int)`; `null` means
sleep disabled.

### Worker — `auto/AutoUpdateWorker.kt` (`CoroutineWorker`)

`doWork()`:

1. Read `autoSource`, `lastLat`/`lastLng`, `tempUnit`, `watchAddress` from Prefs.
2. **Guard.** If `OFF`, or coords/watch missing → record a skip summary and return
   `success()` **without rescheduling**. (No pointless wakeups when unconfigured;
   app-launch / settings re-arm restarts the chain once fixed.)
3. **Build payload.**
   - `TEMPERATURE`: `OpenMeteoClient.currentTemp(lat, lng, tempUnit)` →
     `DisplayFormatter.temperature(temp, unit)`.
   - `SUN`: `SunCalc.nextEvent(now, lat, lng, systemZone)` →
     `DisplayFormatter.sunTime(kind, time)`.
4. **Send.** `OlleeBleClient.send(address, payload)`. Record `lastAutoSendMs` and a
   `lastAutoSendSummary`.
5. **Self-reschedule.** Enqueue the next `OneTimeWorkRequest` via
   `enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, next)`.

The chain is always at most one unique-named one-shot
(`WORK_NAME = "auto_update_chain"`). Because it is a `OneTimeWorkRequest`, there is
no 15-min floor on the *delay*, but very short intervals are best-effort under
Doze (see Error handling).

BLE from a background worker is permitted with the already-held
`BLUETOOTH_CONNECT` runtime permission; a connect-write-disconnect completes in
seconds, well within the worker's 10-minute budget. No expedited job / foreground
service is needed.

### Scheduler wrapper — `auto/AutoUpdateScheduler.kt`

`reschedule(context)` (thin Android glue, the single re-arm entry point):

1. Read Prefs.
2. If `OFF` → cancel `WORK_NAME`, return.
3. Compute the next fire:
   - `TEMPERATURE`: if a send is **overdue** (`lastAutoSendMs + interval <= now`
     and `now` is outside the sleep window) → delay 0 (fire immediately so the
     watch isn't stale after the user was away). Otherwise
     `nextTemperatureFire(now, interval, sleepOrNull)`.
   - `SUN`: delay 0 (send the current next-event immediately on (re-)arm; the
     worker then schedules `nextSunWake` after sending).
4. `enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)` with the
   computed `initialDelay`.

### Re-arm triggers

- **App launch** — `MainActivity` calls `AutoUpdateScheduler.reschedule`.
- **Settings change** — any auto-update setting edit persists then reschedules.
- **`BOOT_COMPLETED`** — `auto/BootReceiver.kt` calls `reschedule`.
- **Daily watchdog** — a `PeriodicWorkRequest` (24 h,
  `ExistingPeriodicWorkPolicy.KEEP`) whose worker just calls `reschedule`. One
  cheap wake/day; restarts the chain if it ever dies while the app is never
  opened.

## Background plumbing

- **Dependency:** add `androidx.work:work-runtime-ktx` to `gradle/libs.versions.toml`
  and `app/build.gradle.kts`. Pure AAR — no native code, builds on the ARM64 Pi
  toolchain without emulation.
- **Manifest:** add `<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />`
  and register `BootReceiver` (`exported=false`, intent-filter
  `android.intent.action.BOOT_COMPLETED`). No foreground-service permissions, no
  background-location.
- **WorkManager init:** rely on the default `WorkManagerInitializer`
  (androidx.startup). No custom configuration needed.

## UI changes

A new **Auto-update** `Card` on `ui/MainScreen.kt`, placed below the two preview
cards and above the custom-send section.

- **Source selector** — `SingleChoiceSegmentedButtonRow`: `Off` / `Temp` / `Sun`.
- **When `Temp`:** an interval `OutlinedTextField` (minutes, numeric); a sleep
  `Switch`; and start/end times shown as buttons opening a `TimePicker` dialog
  (defaults 22:00 / 06:00).
- **When `Sun`:** a single explanatory line — "Re-sends automatically after each
  sunrise/sunset; no schedule needed."
- **Status line** — `Last auto-update: 14:00 — Sent '  72 F'` from
  `lastAutoSendMs` / `lastAutoSendSummary`; reads "No auto-updates yet" when null.

`MainScreenState` gains: `autoSource`, `tempIntervalText`, `sleepEnabled`,
`sleepStartMin`, `sleepEndMin`, `lastAutoSummary`. New callbacks persist each
change to Prefs and call `AutoUpdateScheduler.reschedule`. The interval field
clamps to a 15-min minimum on commit.

## Error handling

All background; failures surface only on the in-app status line.

| Condition | Behavior |
|---|---|
| `autoSource = OFF` | No chain enqueued; watchdog no-ops. |
| Coords or watch address missing | Record `Skipped: set location/watch in app`. Don't reschedule; app-launch re-arm restarts once fixed. |
| Temp fetch fails (network/timeout) | Record `Skipped: weather fetch failed`. Reschedule the next normal interval — no retry-storm. |
| BLE send fails — `TEMPERATURE` | Record `Skipped: watch unreachable`. Reschedule the next normal interval. |
| BLE send fails — `SUN` | Stale value matters: retry at **+15 min**, capped at 3 attempts, then fall back to waiting for the next event. |
| `SunCalc.nextEvent` returns null (polar) | Nothing to send; reschedule a re-check in **+12 h**. |
| Temp fire computed inside sleep window | Snapped to `sleepEndMin` by `nextTemperatureFire`; worker also guards and skips if it somehow fires in-window. |
| Interval below 15 min | UI clamps to 15 min, with a note that very short intervals are best-effort under Doze and cost battery. |
| Sleep window `start == end` | Treated as no suppression (always awake). |

## Testing

**Automated (pure JVM unit tests; additive — existing tests keep passing):**
`auto/AutoUpdateScheduleTest.kt`:

- `isInSleepWindow` — inside, outside, midnight-wrap (22:00–06:00), and both
  boundaries (start inclusive, end exclusive); `start == end` → false.
- `nextTemperatureFire` — plain `now + interval` (sleep disabled); lands-in-window
  snaps to `sleepEnd`; `now` already in-window snaps to `sleepEnd`.
- `nextSunWake` — `eventTime + buffer`.

The worker/scheduler are thin Android wrappers; the only nontrivial logic (the
scheduling math) is fully covered above. WorkManager's own behavior is not
re-tested.

**Manual on-device verification** (appended to the verification report; background
timing needs a real device, and the dev box is usually headless over SSH):

- Select Temp, 15-min interval, sleep 22:00–06:00 → sends land ~every 15 min and
  stop overnight, resuming at 06:00.
- Select Sun → a fresh value lands shortly after a sunrise/sunset passes.
- Kill the app → sends continue. Reboot the phone → sends resume.
- Turn the watch off during a tick → status shows `watch unreachable`; the next
  tick recovers.

## New / changed files

| File | Change |
|---|---|
| `auto/AutoSource.kt` *(new)* | `enum class AutoSource { OFF, TEMPERATURE, SUN }`. |
| `auto/AutoUpdateSchedule.kt` *(new)* | Pure scheduling math + `SleepWindow`. |
| `auto/AutoUpdateWorker.kt` *(new)* | `CoroutineWorker`: send + self-reschedule. |
| `auto/AutoUpdateScheduler.kt` *(new)* | Re-arm entry point + watchdog enqueue. |
| `auto/BootReceiver.kt` *(new)* | Re-arm on `BOOT_COMPLETED`. |
| `prefs/Prefs.kt` | New fields (table above). |
| `ui/MainScreen.kt` | New Auto-update card + state fields + callbacks. |
| `MainActivity.kt` | Wire callbacks → Prefs + `reschedule`; re-arm on launch. |
| `app/build.gradle.kts`, `gradle/libs.versions.toml` | Add WorkManager. |
| `AndroidManifest.xml` | `RECEIVE_BOOT_COMPLETED` + `BootReceiver`. |
| `app/src/test/.../auto/AutoUpdateScheduleTest.kt` *(new)* | Pure unit tests. |

## References

- v0.2 design spec (deferred this feature): `docs/superpowers/specs/2026-05-14-preview-and-temp-unit-design.md`
- v0.1 design spec: `docs/superpowers/specs/2026-05-14-freeollee-faces-app-design.md`
- WorkManager one-time / unique work: `https://developer.android.com/develop/background-work/background-tasks/persistent`

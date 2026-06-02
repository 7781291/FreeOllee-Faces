# Steps Face — design spec

**Date:** 2026-06-02
**Status:** Approved for planning
**Feature:** A new "Steps" face that pushes today's step count (from Android Health Connect)
to the Ollee watch nameplate.

## Summary

Add a **Steps** face to FreeOllee-Faces that reads today's total step count from Android
**Health Connect** — where the user's step-tracking app (a fitness ring, phone pedometer,
etc.) publishes it — and pushes it to the Ollee watch's 6-character `0x2F` nameplate every
15 minutes, paused during the sleep window — exactly like the existing Temperature face, but
with a Health Connect read replacing the weather fetch.

## Why Health Connect

Health Connect is Android's shared store for fitness data: any step-tracking app the user
runs can write `StepsRecord` entries, and other apps can read them with the user's
permission. Reading through Health Connect therefore works with **whatever source the user
already has** — no per-tracker API, no reverse-engineering, no root, and no dependence on a
given tracker exposing its own integration. That matters because the test device is **not
rooted** (GrapheneOS-style Pixel 7, Android 16), so reading a tracker's private DB or
sniffing its BLE traffic would not be viable anyway.

```
step-tracking app ─writes→ Health Connect (StepsRecord)
                              │ aggregate(today: midnight→now)
        StepsRepository ◀─────┘
              │ DisplayFormatter.steps()  →  " 12345"
              ▼
        OlleeBleClient.send(0x2F nameplate) ─BLE→ watch "Name tag"
```

### Phase 0 feasibility — CONFIRMED on-device 2026-06-02

Verified the data path on the actual Pixel 7 over adb before committing to the design, using
a real step-tracking app as the Health Connect writer:

1. Enabled that app's Health Connect sync, granting it `health.WRITE_STEPS`.
2. Health Connect → Data → **Steps** then showed **real step data**: **5,353 steps** total
   today, in 30-minute buckets (e.g. `11:30 AM–12:00 PM — 1,770 steps`).

Writers typically post `StepsRecord` entries in periodic batches (per their own sync
cadence), so Health Connect can lag real time by minutes — acceptable for a watch nameplate.
The feature is therefore unblocked: a `COUNT_TOTAL` aggregate over today returns the running
total.

## Watch-side constraint (important framing)

This is **not** a new dial on the watch. The Ollee watch's built-in faces (including its own
Step Counter `0x0A` and Heart Rate `0x0C`) are firmware-locked to onboard sensors and ignore
BLE-injected values — already proven by the Temperature-face experiment. The only writable
display surface is the **6-character `0x2F` nameplate** ("Name tag", held under ALARM on the
Clock face). FreeOllee-Faces already models a "face" as an app-side source that writes that
single nameplate, with exactly one active at a time (`ActiveFace = TEMPERATURE | SUN |
CUSTOM`). The Steps face is one more such source: today's count shows up where the
temperature does, not as a separate watch face.

## Scope

**In scope:** steps only (one new face/source). **Out of scope:** heart rate, sleep,
distance, calories — also available in Health Connect and could be added later by the same
pattern, but they are not part of this work.

## Components

New and changed files (all identifiers verified against the current tree):

### New
- `health/StepsRepository.kt` — wraps `androidx.health.connect.client.HealthConnectClient`.
  - `suspend fun todaySteps(): Result<Long>` — `aggregate(StepsRecord.COUNT_TOTAL)` over
    `[local midnight, now]` in the system zone. Returns the summed count (0 if no records).
  - `fun availability(): Availability` — wraps `HealthConnectClient.getSdkStatus(...)` →
    `UNAVAILABLE | UPDATE_REQUIRED | AVAILABLE`.
  - `suspend fun hasReadPermission(): Boolean` — checks granted permissions include
    `HealthPermission.getReadPermission(StepsRecord::class)`.
  - Never throws to callers; maps failures into `Result.failure`.
- `format/DisplayFormatter.steps(count: Long): String` — right-aligned raw count in 6 chars
  via `"%6d"`. Values ≥ 1,000,000 (not physically real) clamp to `"999999"`. Negative
  guarded to `0`. **Unit-tested.**

### Changed
- `auto/ActiveFace.kt` — add `STEPS`. (`fromLegacyAutoSource` unchanged; STEPS is not a
  legacy value, so no migration mapping.)
- `auto/AutoUpdateWorker.kt` — add `runSteps(...)`: read `StepsRepository.todaySteps()` →
  `DisplayFormatter.steps()` → `OlleeBleClient.send(address, payload)` (defaults to the
  nameplate target) → reschedule +15 min. Mirrors `runTemperature` structure, including the
  sleep-window skip guard and the `recordAutoSend(...)` / `applyHealth(...)` calls. No
  location/coords needed (unlike temperature/sun), so the `lat/lng == null` setup guard does
  not gate STEPS — only `watchAddress == null` does.
- `auto/AutoUpdateScheduler.kt` — add `ActiveFace.STEPS` branch in `reschedule(...)`:
  next fire = +15 min, sleep-aware. Reuse `AutoUpdateSchedule.nextTemperatureFire(now, 15,
  sleep)` (the math is generic; the "temperature" name is incidental) or add a thin
  `nextStepsFire` alias for clarity.
- `prefs/Prefs.kt` — add `lastStepCount: Long?` + `stepsFetchedMs: Long?` (cache for the
  home-screen status line) and a `recordStepsFetch(count)` helper. New SharedPreferences keys
  `steps_last_count`, `steps_fetched_ms`. The 15-min interval is fixed (not a stored pref).
- `notify/FailureKind.kt` — add `HEALTH_UNAVAILABLE` for "Health Connect missing or read
  access not granted", wired through the existing `NotifyDecision` / `ErrorNotifier` path the
  same way `WATCH_UNREACHABLE` is.
- `ui/FacesListScreen.kt` — add `FaceRow("Steps", ActiveFace.STEPS, active, onSelect)`.
- `ui/HomeScreen.kt` — when STEPS is active: show last step count + age; show a **"Grant
  Health access"** button when `StepsRepository.hasReadPermission()` is false (launches the
  Health Connect permission request).
- `MainActivity.kt` — register the Health Connect permission request contract
  (`PermissionController.createRequestPermissionResultContract()`) for the read-steps
  permission set, invoked from the HomeScreen button.
- `AndroidManifest.xml` — add `<uses-permission android:name="android.permission.health.READ_STEPS"/>`
  and `android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND` (so the WorkManager job can
  read while the app is backgrounded); add the Health Connect privacy-policy/rationale
  activity with intent filter `androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE` and the
  `<queries>` entry for the Health Connect package, per the connect-client docs.
- `app/build.gradle.kts` + `gradle/libs.versions.toml` — add
  `androidx.health.connect:connect-client` (current stable). Pure Android library — builds on
  the Pi (ARM64), no native/x86 component.

## Behavior & data flow

1. User selects **Steps** in the Faces list. If Health Connect read access isn't granted,
   HomeScreen shows "Grant Health access"; tapping it runs the HC permission request.
2. `AutoUpdateScheduler.reschedule` arms the WorkManager chain for STEPS (+15 min, sleep-aware).
3. Each `AutoUpdateWorker` run (`runSteps`):
   - If in sleep window → `recordAutoSend("Asleep (power saving)")`, skip send, reschedule.
   - Else read today's steps from Health Connect, format to ≤6 chars, send to the nameplate.
   - On success → `recordStepsFetch(count)`, `recordAutoSend("Sent '<payload>'")`, clear notif.
   - Reschedule the next run +15 min.

## Error handling (reuses existing infra)

| Condition | Behavior |
|-----------|----------|
| Health Connect unavailable / read perm not granted | `recordAutoSend("Skipped: grant Health access")` + `FailureKind.HEALTH_UNAVAILABLE` notification; HomeScreen shows grant button |
| No step records yet today | Send honest `0` (`"     0"`) |
| Watch unreachable | Existing `FailureKind.WATCH_UNREACHABLE` |
| In sleep window | Skip send (steps barely change asleep), like Temperature |
| `watchAddress == null` | Existing `SETUP_INCOMPLETE` path |

## Testing

- **Unit — `DisplayFormatter.steps`:** boundaries `0`, `5`, `12345`, `99999`, `100000`,
  `≥1_000_000` (clamp), negative (→0); assert exactly 6 chars and right alignment.
- **Unit — schedule math:** STEPS next-fire = +15 min and sleep-window snap, via the existing
  pure `AutoUpdateSchedule` tests.
- **Manual / on-device:** select Steps; grant HC read; confirm a send within 15 min and the
  watch nameplate shows today's count; confirm sleep-window pause; confirm the "Grant Health
  access" path when permission is revoked. (`StepsRepository` is a thin Android-dependent
  wrapper — logic kept minimal; the testable logic lives in the formatter and schedule math.)

## Risks / open items

- **The source app's sync cadence** sets data freshness; our 15-min push can only be as fresh
  as the writer's last `StepsRecord`. Acceptable; documented.
- **Background read permission** (`READ_HEALTH_DATA_IN_BACKGROUND`) is required for the worker
  to read while backgrounded; verify the grant flow surfaces it on Android 16.
- If the source app is later disconnected from Health Connect (or the user has no step
  writer), the count goes stale → the honest `0`/last-value behavior and the status line
  should make that visible.

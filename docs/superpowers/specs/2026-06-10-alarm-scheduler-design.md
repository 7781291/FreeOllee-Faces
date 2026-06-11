# 5-Alarm Scheduler — design

**Date:** 2026-06-10 · **Status:** approved, ready for implementation plan
**Branch:** `alarm-scheduler`, **stacked on `timer-enhancements`** — both features ship together in
one release (a single `VERSION` bump when the combined work merges to `main`).
**Scope:** a phone-side multi-alarm scheduler driving the watch's single `0x25` alarm. Hourly-chime,
snooze, and the watch's own alarm UI are out of scope.

## Goal

The watch stores **one** alarm (`0x25`: `HH:MM` + chime + enable) with **no day-of-week field**.
Give FreeOllee-Faces up to **5 logical alarms**, each with a time, an enabled toggle, a day-of-week
repeat mask, and its own chime. The phone computes the **next fire** across all enabled alarms and
keeps the watch's single alarm armed with that time — re-arming after each fire — or disarms the
watch when nothing is due.

This mirrors how the **official Ollee app already works**: its Alarm screen has repeat-day chips and
a per-alarm chime, yet the `0x25` record carries no day field — so the official app itself computes
the next fire phone-side and re-arms the one watch alarm. We do the same, for five alarms instead of
one.

## On-device findings (2026-06-10, settled — no open unknowns)

Verified by pushing crafted `0x25` records via the debug app's `DevToolsReceiver` over the bonded
link (watch `00:80:E1:26:DC:86`) and observing the watch:

| Push | Result |
|------|--------|
| `enabled=true, playNow=false`, future `HH:MM` | Watch **arms a real alarm**; it **rings at `HH:MM`** and self-stops after ~35 s (no dismiss). The alarm face shows the set time. |
| `enabled=false`, future `HH:MM` | **Silent** — does not ring. |
| `enabled=true` then `enabled=false` at the **same** `HH:MM` (override) | **Silent** — `enabled=false` overrides a just-armed alarm. |

Conclusions, all load-bearing for this design:
- **`buildAlarmPacket(..., playNow=false, enabled=true)` arms a genuine firing alarm** (previously
  only the `playNow=true` chime *preview* had been tested).
- **`enabled=false` (byte 0 = `0`) is a reliable disarm** — even over an already-armed alarm. This
  resolves the protocol doc's open "byte 0 enable? persist untested" question: byte 0 is a true
  enable/disable.
- The alarm **face display is sticky** (it kept showing the last *enabled* time after a disable) —
  a watch UI quirk only; the **ring** behavior is what byte 0 controls, definitively.

## Assumptions

- **"Bluetooth always on"** is enabled on the watch, so the app can reconnect and push without a
  manual wake. (The app still uses the existing `BleClient` connect/retry path; a push can fail if
  the watch is briefly unreachable, and is retried — see Re-arm engine.)
- The phone **cannot observe a fire** (the watch never sends watch→phone events), so the app
  schedules its own re-arm trigger from its computed next-fire time.
- **Last-writer-wins** versus the watch's own alarm UI / the official app (one shared `0x25` slot),
  same as the timer face. When this feature is active, FreeOllee-Faces owns the watch alarm.

## Design

### 1. Data model & persistence (commonMain)

Mirrors the existing `timer/` package (`TimerSet`/`TimerSetsJson`/`TimerSetsRepository`).

```kotlin
data class Alarm(
    val id: String,
    val hour: Int,        // 0..23
    val minute: Int,      // 0..59
    val enabled: Boolean,
    val daysMask: Int,    // bit0=Mon … bit6=Sun; a 1-bit means "repeats that weekday"
    val chimeIndex: Int,  // 0..13 (Classic, Breeze, Westminster, …)
    val label: String,    // phone-side only, never sent to the watch
)
```

- **`AlarmsJson`** — pure codec, decode NEVER throws (corrupt prefs → empty list), like
  `TimerSetsJson`. Validates ranges; drops malformed entries.
- **`AlarmsRepository`** — persists up to **`MAX_ALARMS = 5`** in its own `Settings` store
  (key `alarms`), like `TimerSetsRepository`. CRUD: `getAll`, `get(id)`, `save` (insert/replace,
  capped at 5), `delete`.
- **Empty-days = inert (decision):** the day chips *are* the schedule. A new alarm defaults to **all
  7 days**. An enabled alarm with **zero days** selected simply never matches and contributes no
  fire — a harmless inert state, no one-shot/auto-disable logic.

### 2. Next-fire computation (commonMain, pure — the testable core)

`object AlarmSchedule` with no Android deps, using `kotlinx-datetime` (as `AutoUpdateScheduler`
does):

```kotlin
data class NextFire(val dateTime: LocalDateTime, val hour: Int, val minute: Int, val chimeIndex: Int)

fun nextFire(alarms: List<Alarm>, now: LocalDateTime): NextFire?
```

- For each **enabled** alarm, find the soonest `LocalDateTime` ≥ `now` whose weekday bit is set in
  `daysMask`, at `HH:MM`. Scan today..+7 days; **today counts only if `HH:MM` is still in the
  future** (strictly after `now`).
- Return the **minimum** across all alarms (ties → any; same fire time, the earliest-listed alarm's
  chime), or **null** if none enabled / none with matching days.
- Weekday mapping fixed to `kotlinx.datetime.DayOfWeek` (Mon=isoDayNumber 1 → bit0).
- Heavily unit-tested: today-future vs today-past, next-week wrap, tie, none, DST/midnight edges.

### 3. Re-arm engine

- **`AlarmScheduler`** interface (commonMain, alongside the existing `Scheduler`):
  ```kotlin
  interface AlarmScheduler { fun rearm() }
  ```
- **One `rearm()` operation** (Android impl) always does three steps:
  1. `nextFire(repo.getAll(), now)`.
  2. **Push to watch** via `BleClient.sendPacket`:
     - if a next fire exists → `buildAlarmPacket(hour, minute, chimeIndex, playNow=false, enabled=true)`;
     - else (no alarm due) → `buildAlarmPacket(0, 0, 0, playNow=false, enabled=false)` to **disarm**
       (verified silent on-device).
  3. **Schedule the next trigger:** if a next fire exists, `AlarmManager.setExactAndAllowWhileIdle`
     at `nextFire + 60 s` targeting `AlarmRearmReceiver`; else cancel any pending trigger.
- **`AlarmManager`** (new to this app) with **`USE_EXACT_ALARM`** (auto-granted for alarm-clock
  apps — no runtime prompt; targetSdk 35). The receiver re-runs `rearm()` ~1 minute after each fire,
  recomputing the next occurrence (this is what makes repeat-days and "fire once then advance" work,
  and pre-empts any same-time daily re-ring well within a day).
- **Triggers for `rearm()`:** (a) user edits/toggles/deletes an alarm; (b) `AlarmRearmReceiver`
  firing post-alarm; (c) `BootReceiver` (already exists) on device boot; (d) app open. Pure
  AlarmManager — **no periodic WorkManager watchdog** (chosen for simplicity).
- **Push failure** (watch unreachable): the watch keeps its last good alarm; the next trigger,
  app-open, or boot retries `rearm()`. Eventually consistent over the existing `BleClient` path.
- **Manifest:** add `USE_EXACT_ALARM`; register `AlarmRearmReceiver`. The receiver uses `goAsync()`
  to run the BLE push off the main thread (same pattern as `DevToolsReceiver`).

### 4. ViewModel & UI

- `AppViewModel` gains: `alarms` state (from `AlarmsRepository`), `saveAlarm(alarm)`,
  `toggleAlarm(id, enabled)`, `deleteAlarm(id)`, `addAlarm()` (capped at 5). Each persists then
  calls `alarmScheduler.rearm()`. A derived **next-alarm summary** string (e.g. "Next: Tue 7:00 AM ·
  Breeze", or "No alarms").
- **UI — `Screen.Alarms`**, reached from Home like "Timers". Up to 5 **inline alarm cards** styled
  consistently with the app's existing Cards (the timer/complication cards): each card has an
  enabled `Switch`, the `HH:MM` (tap → Material time-picker dialog), a row of 7 day chips
  (`M T W T F S S`), a chime dropdown, an optional label field, and a delete action; plus an "Add
  alarm" button (disabled at 5) and a next-alarm summary header. New `Screen.Alarms` entry and a
  Home button, wired in `MainActivity`.

### 5. Testing

- Pure unit tests (commonTest, JVM): `AlarmsJson` round-trip + malformed-input resilience;
  `AlarmsRepository` cap/CRUD; extensive `AlarmSchedule.nextFire` cases (the core).
- `OlleeProtocolTest` already covers `buildAlarmPacket`; add a case asserting the **disarm** frame
  (`enabled=false`) and an **armed** frame (`enabled=true`) byte layouts against the captured frames.
- `AppViewModel` tests: save/toggle/delete → `alarmScheduler.rearm()` is invoked, and (via a fake)
  the pushed frame's `enabled`/`HH:MM`/`chime` bytes match the computed next fire.

### 6. Cross-repo: protocol reference update

Update `ollee-graphene/docs/reference/ollee-ble-protocol.md`'s `0x25` section: byte 0 is a
**verified enable/disable** (not "untested") — `enabled=false` reliably silences the alarm, even
over an armed one; `playNow=false, enabled=true` arms a **real** alarm that rings ~35 s and
self-stops. Cite the 2026-06-10 on-device tests. Separate repo, separate commit.

## Out of scope

- Snooze, hourly-chime, and the watch's other alarm-screen settings.
- Reading the watch's current alarm back (last-writer-wins; no sync).
- True one-time (no-repeat) alarms — empty-days is inert here; could be added later.

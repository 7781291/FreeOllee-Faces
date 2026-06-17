# Alarm-mode quick timer — design

**Date:** 2026-06-15
**Status:** Approved, ready for implementation plan
**Branch:** `feat/alarm-mode-quick-timer`

## Summary

Add an **"Alarm mode"** toggle to the Quick-timer card on the Timer dashboard. When on,
the H/M/S "from now" inputs are replaced by an **H : M : AM/PM** wall-clock input (the same
controls as the Alarms screen). Sending computes `countdown = nextOccurrence(targetTime) − now`
and pushes it as a **single quick-timer countdown that starts immediately**, so the watch
fires at the chosen wall-clock time. A target earlier than now rolls forward to the next day.

This is a quick, calculated one-time alarm — distinct from the real **Alarms** feature
(`AlarmsScreen`), which writes the watch's persistent day-of-week alarm slot. Alarm-mode
quick timer uses the Timer face's standalone countdown and is bounded to the next ~24h.

## Background / key finding (2026-06-15 BLE capture)

The watch's Timer-face quick timer supports countdowns up to ~24h, **not** the ~4h15m the
prior model assumed. Capturing the official app sending **20h 05m 00s** produced the 0x26
header `14 05 00 01`:

| byte | value | meaning |
|------|-------|---------|
| 0 | `0x14` = 20 | **hours** |
| 1 | `0x05` = 5 | minutes |
| 2 | `0x00` = 0 | seconds |
| 3 | `0x01` | `START_SINGLE` |

So header byte 0 is **hours**, not a constant `00`. The watch also normalizes an over-60
minute byte into hours, which is why FreeOllee's `00 4b 00` (75 min) displayed `01:15:00` and
masked the real layout.

### Latent bug this exposes

`OlleeProtocol.buildTimerPacket` (OlleeProtocol.kt:157) currently writes:

```kotlin
payload[1] = (headerSeconds / 60).coerceAtMost(0xFF).toByte() // MM
payload[2] = (headerSeconds % 60).toByte()                    // SS
// payload[0] left 0
```

Any header ≥ 256 minutes silently truncates to 4h15m (`0xFF` minutes). Fixing the encoding is
a prerequisite for alarm mode and is in scope here.

## Components

### 1. Protocol fix — `OlleeProtocol.buildTimerPacket`

Change the header packing to `[HH, MM, SS, mode]`:

```kotlin
payload[0] = (headerSeconds / 3600).toByte()          // hours
payload[1] = ((headerSeconds % 3600) / 60).toByte()   // minutes 0–59
payload[2] = (headerSeconds % 60).toByte()            // seconds
payload[3] = startMode.byte3.toByte()
```

- Validation: tighten the `headerSeconds` `require` to `0..86_399` (≤ 23:59:59) so byte 0
  stays ≤ 23, matching the watch UI's own max. (Slot durations keep their existing
  `0..359_999` range — unchanged.)
- Update the KDoc comment block that currently describes the `[00, MM, SS]` model and the
  "clamped to one byte (0xFF max)" note.
- Sub-256-minute values render identically on the watch before and after (normalization), so
  this is not a user-visible regression for existing quick timers — only a correctness fix.

### 2. New pure logic — `QuickAlarm.countdownSeconds`

A new file `timer/QuickAlarm.kt` in `commonMain`, no UI/Android deps, mirroring the
`AlarmSchedule` style:

```kotlin
object QuickAlarm {
    /**
     * Seconds from [now] until the next occurrence of [targetHour]:[targetMinute]
     * (target seconds = 0). Rolls forward a full day when the target is at or before now,
     * so the result is always in 1..86400. Exactly-now → 86400, then capped to 86399
     * by the caller / protocol bound.
     */
    fun countdownSeconds(now: LocalTime, targetHour: Int, targetMinute: Int): Int
}
```

- `targetHour` is 0–23 (24h), `targetMinute` 0–59.
- Implementation: `target = targetHour*3600 + targetMinute*60`; `nowSec = now.toSecondOfDay()`;
  `delta = floorMod(target - nowSec, 86400)`; if `delta == 0` → `86400`.
- The caller coerces to `86399` before sending (keeps byte 0 ≤ 23). A 24h-exact alarm becomes
  23:59:59 — acceptable edge behavior, documented in the preview as well.

### 3. State & persistence — `Prefs` + `AppViewModel`

New `Prefs` fields, consistent with the existing `quickTimerSeconds` /
`quickTimerStartFromApp` / `quickTimerIntervalMode`:

- `quickTimerAlarmMode: Boolean` (default `false`)
- `quickTimerAlarmHour: Int` (0–23, default e.g. `7`)
- `quickTimerAlarmMinute: Int` (0–59, default `0`)

The H/M/S countdown (`quickTimerSeconds`) and the alarm target are stored **separately**, so
toggling the mode preserves each side's last value.

`AppViewModel` gains mirrored `mutableStateOf` properties plus setters:
- `toggleQuickTimerAlarmMode(enabled)`
- `saveQuickTimerAlarmTime(hour, minute)`
- A `sendQuickAlarm()` that: reads `now = Clock.System.now().toLocalDateTime(...).time`,
  computes `QuickAlarm.countdownSeconds(...).coerceAtMost(86_399)`, and calls
  `buildTimerPacket(slots, headerSeconds = seconds, startMode = START_SINGLE)` via the
  existing `pushTimerFrame` path. Slots are the active set's durations or zeros, exactly as
  `sendQuickTimer` does today.

### 4. UI — `TimerSetsScreen` + shared widgets

- New `quickTimerAlarmMode` / `quickTimerAlarmHour` / `quickTimerAlarmMinute` params and
  `onToggleAlarmMode` / `onSaveAlarmTime` / `onSendAlarm` callbacks plumbed from `MainActivity`.
- Card layout:
  - Top: an "Alarm mode" `ToggleRow`.
  - **Alarm mode OFF** (unchanged): H/M/S `NumberField`s + "Start timer from app" +
    "Interval timer mode" toggles + existing send button.
  - **Alarm mode ON**: an H : M : AM/PM row (`HourField` + `NumberField("M")` + AM/PM
    `TextButton`); both existing toggles hidden; send button labelled **"▶ Send alarm"**.
  - A preview line under the input: **"Fires 7:00 AM · in 9h 0m"**, recomputed each
    recomposition from `Clock.System` (and noting "(capped)" if the delta hit 23:59:59).
- Extract the alarm time controls so both screens share them: move `HourField` and the
  `hour24(hour12, pm)` helper out of `AlarmsScreen.kt` into `TimerWidgets.kt` (next to
  `NumberField`) as `internal` functions, plus a small `hour12Of(hour24)` / `isPm(hour24)`
  helper pair. `AlarmsScreen` switches to the shared versions (no behavior change there).

## Data flow

```
User picks H:M:AM/PM  ──onSaveAlarmTime──▶ AppViewModel ──▶ Prefs (hour, minute)
User taps "Send alarm" ─onSendAlarm─▶ sendQuickAlarm()
    now = Clock.System local time
    seconds = QuickAlarm.countdownSeconds(now, hour, minute).coerceAtMost(86399)
    packet  = OlleeProtocol.buildTimerPacket(slots, seconds, START_SINGLE)
    pushTimerFrame(packet, "Started alarm timer on watch")
```

## Error handling

- Send failures reuse the existing `pushTimerFrame` snackbar ("Send failed — long-press
  ALARM to wake the watch, then retry").
- Out-of-range typed input is coerced by the field widgets (hour 1–12, minute 0–59) exactly
  as on the Alarms screen.
- `delta == 0` (target is the current minute) → next-day 24h, capped to 23:59:59.

## Testing

- **`QuickAlarmTest`** (commonTest): rollover cases — target later today, target earlier
  today → +next day, target == now → 86400 (pre-cap), midnight wrap, boundary minutes.
- **`OlleeProtocolTest`**: header encoding across hour boundaries — 75 min → `01 0f 00`,
  20h5m → `14 05 00`, 23h59m59s → `17 3b 3b`, 0 → `00 00 00`; plus the `START_SINGLE` byte 3.
  Update any existing assertion that encoded the old `[00, MM, SS]` layout.
- **`AppViewModelTest`**: alarm-mode send computes the expected `headerSeconds` for a fixed
  injected clock and emits a `START_SINGLE` frame; mode/target persist through `Prefs`.

## Out of scope (YAGNI)

- No live-ticking countdown preview (recompute on recomposition / at send is enough).
- No notification or phone-side scheduling — the watch owns the countdown once sent.
- No multi-alarm-mode list; this is a single calculated one-shot.
- No change to the real `AlarmsScreen` persistent-alarm feature beyond sharing widgets.

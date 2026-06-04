# Notification count (+ alarm-chime BLE spike) — design

> **Date:** 2026-06-04 · **Status:** approved, pre-implementation
> **Repos touched:** `FreeOllee-Faces` (Phase A, the feature) and `ollee-graphene`
> (Phase B, the reverse-engineering spike).

## Summary

Add basic notification support to FreeOllee-Faces: when the watch is in its idle
Clock display, show the **count of undismissed, non-persistent notifications** in the
upper-left letter pair — the same two cells that normally render the weekday
(`SU`/`MO`/`TU`/…). Optionally, beep the watch on a new notification.

The beep cannot be done today: the watch has **no "beep now" BLE command**. Its only
audible output is the **alarm chime**, played by firmware when an alarm rings. So the
beep is split off into a bounded reverse-engineering spike (Phase B) that decodes the
alarm-set command. Phase B's outcome decides whether a watch beep is viable; either way
its capture is reusable research (it also unlocks a future "custom alarm chime"
feature). Phase A — the count — ships independently of Phase B.

One combined spec, two phases.

## Background: the hardware constraint that shapes everything

From the reverse-engineering in `ollee-graphene/docs/reference/`:

- The **upper-left letter pair** (LCD `position0,1`) is the **only BLE-writable text in
  the upper panel**. It renders the current day's 2-char slot from a 7-entry **weekday
  table**, written at target `02 34` (read at `02 35`→`55`). FreeOllee-Faces already
  implements this write as `OlleeProtocol.buildWeekdayPacket(slots)` — pass 7 identical
  slots to make the panel show one fixed 2-char label regardless of weekday. ✅ verified
  on-device.
- ⚠️ **The weekday label and the name tag are mutually exclusive.** When a name-tag
  value (`02 2F`) is shown in the main digits, the *entire* upper panel (weekday + date)
  **blanks**. Every current FreeOllee face — Temperature, Sun, Steps, Custom — pushes a
  name-tag value, so none of them can co-display an upper-panel count. The count can only
  appear on the plain Clock display (time in the main digits, nothing in the name tag).
- The watch has **no standalone "beep"/"play tone" command.** The chime tones
  (`alarmChime1`–`14`, `alarmChimeClassic`) are **alarm configuration**; the app's own
  help text — *"Hold the ALARM button to test the alarm chime"* — confirms the "test" is
  the user holding the watch's physical button, not an app→watch message. The
  alarm-*set* command itself has never been captured (Alarm face is ID `05`; its content
  is still `❓` in `ollee-ble-protocol.md`).

**Consequence:** the count is its own display mode, mutually exclusive with the
value-faces — not an overlay. And a watch beep, if possible at all, must go through the
alarm subsystem, which is undecoded.

## Phase A — Notification count (FreeOllee-Faces)

### A.1 Mode model

Add a new value to the existing `ActiveFace` enum:

```kotlin
enum class ActiveFace { TEMPERATURE, SUN, STEPS, CUSTOM, NOTIFICATIONS }
```

This reuses the app's "exactly one active face" model. When `NOTIFICATIONS` is the
active face:

- the app pushes the count to the **weekday table** (`buildWeekdayPacket`), and
- pushes **nothing** to the name tag (`02 2F`),

so the watch keeps showing the firmware Clock (live time in the main digits) with the
count in the upper-left letter pair. Selecting any other face restores normal value-face
behaviour and the count disappears (the panel blanks under a name-tag value) — which is
the hardware-honest behaviour, not a bug.

`NOTIFICATIONS` appears in the Faces list (`FacesListScreen.kt`) alongside the others. It
is **not** added to `fromLegacyAutoSource` (no legacy `AutoSource` maps to it), matching
how `CUSTOM` is handled.

### A.2 Notification reading

A `NotificationListenerService` subclass (`NotificationCountService`) registered in the
manifest with `BIND_NOTIFICATION_LISTENER_SERVICE`. It requires the user to grant
"Notification access" in system settings (a manual, per-app grant; works on GrapheneOS).

Lifecycle:

- `onListenerConnected()` — seed the count from `getActiveNotifications()`.
- `onNotificationPosted(...)` / `onNotificationRemoved(...)` — recompute the count.
- Each recompute updates a single source of truth (a cached `Int`, exposed for the push
  pipeline; e.g. a `StateFlow<Int>` or a value persisted to `Prefs`).

**Counting is a pure, unit-tested function** `NotificationCount.countFrom(...)` taking the
relevant per-notification fields (package name, flags, `isClearable`, group key / is-group
flags) so it can be tested without Android framework objects (matching the repo's
`NotifyDecision` / `DisplayFormatter` style). It includes a notification only when **all**
hold:

- `isClearable()` is true, **and**
- `FLAG_ONGOING_EVENT` is **not** set (drops persistent/media/foreground-service
  notifications) — this is the user's "subtracting persistent", **and**
- it is **not** a group **summary** row (avoids double-counting a bundled app), **and**
- its package is **not** FreeOllee-Faces' own (don't count our delivery-failure
  notifications).

### A.3 Push pipeline (live, debounced)

Count changes feed a coroutine that **coalesces bursts** within a short window (~2 s),
then pushes — **only if `NOTIFICATIONS` is the active face** — through the existing
`OlleeBleClient` + `BleRetryPolicy`. The push payload:

- count `> 0` → `buildWeekdayPacket(List(7) { formatted })` where `formatted` is the
  2-char count string (see A.4);
- count `== 0` → `buildWeekdayPacket(REAL_WEEKDAYS)` restoring the captured default
  `["MO","TU","WE","TH","FR","SA","SU"]`, so the watch shows the correct weekday again —
  i.e. "no badge".

For resilience, the existing periodic `AutoUpdateWorker` and the on-connect path also
push the latest cached count when `NOTIFICATIONS` is active, so a missed or failed live
push self-heals on the next tick. The debounce + "active-face-only" guard keep BLE
wake-ups bounded.

### A.4 Formatting & edge cases

The 2-char slot is written to all 7 weekday entries identically so it displays regardless
of the current day. The upper-left pair is letter-shaped 7-segment but renders digits
cleanly (per `ollee-graphene/docs/reference/ollee-segment-font.md`).

| Count | Display |
|-------|---------|
| `0`   | restore real weekday (`MO`…`SU`) — no badge |
| `1`–`9`   | zero-padded `"01"`…`"09"` |
| `10`–`99` | `"10"`…`"99"` |
| `≥100`    | capped `"99"` (two cells cannot show three digits; rare) |

Formatting is a pure function `NotificationCount.format(n): String?` (null/sentinel for
the zero case so the caller knows to restore the weekday).

### A.5 UI / permissions

- `NOTIFICATIONS` is selectable in the Faces list.
- Selecting it while notification access is **not** granted shows a short rationale and a
  button that opens the system Notification-access settings
  (`Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS`). Selecting it while granted activates
  the mode and pushes the current count.
- A **"Beep on watch" toggle** is placed here but **reserved/disabled** with a short
  "coming soon — under investigation" note, as the gated placeholder for Phase B. It
  stores no behaviour yet.

## Phase B — Alarm/chime BLE capture spike (ollee-graphene)

A bounded reverse-engineering session in `ollee-graphene`, using the existing capture
harness (`CAPTURE=1` build → `ollee-graphene-capture.apk`, smali-patched to log BLE TX/RX
as hex under logcat tag `OLLEE_BLE`).

**Method.** Drive the **official** Ollee app while logging, to isolate the alarm-set
write:

1. Set a single alarm at a known time with a known chime; capture the `02 ??` write(s).
2. Vary the **time** (and enable/disable) across runs to isolate the time encoding and
   the on/off byte.
3. Vary the **chime selection** (`alarmChime1`–`14`, `alarmChimeClassic`) across runs,
   holding time constant, to isolate the chime-selector byte.

**Deliverables.**

- Resolve the Alarm-face content `❓` in
  `ollee-graphene/docs/reference/ollee-ble-protocol.md`: document the alarm-set command
  (target, time encoding, enable byte, chime selector, any snooze config).
- Update `ollee-watch-capabilities.md` Alarm row accordingly.

**Decision gate (the beep).** Evaluate whether firing a momentary/near-immediate alarm is
a tolerable notification cue. Expected verdict: **no** — firing an alarm enters a
must-clear "Alarm ring" state (snooze logic, "Press any button to clear"), which is not a
brief beep. Record the verdict either way:

- If **negative** (likely): pivot to **count-only**; leave the reserved beep toggle out
  of the shipped UI (or repurpose it as a phone-side beep in a later spec). The capture
  still stands as documentation and unlocks a future custom-chime feature.
- If **positive**: wiring the reserved "Beep on watch" toggle to a momentary-alarm push
  becomes a small follow-up task in FreeOllee-Faces.

Phase B does **not** block Phase A shipping.

## Testing

Following the repo's pure-function TDD pattern (`NotifyDecision`, `DisplayFormatter`,
`OlleeProtocol` tests):

- `NotificationCount.countFrom(...)` — include/exclude rules: ongoing excluded,
  non-clearable excluded, group-summary excluded, own-package excluded, ordinary
  notifications counted, and multiple notifications from one app each count (only the
  group **summary** row is dropped, not its children).
- `NotificationCount.format(n)` — zero→restore sentinel, 1–9 zero-pad, 10–99, ≥100 cap.
- Weekday-restore builds the captured default `MO…SU` table.
- Existing `OlleeProtocol.buildWeekdayPacket` is already covered; no change needed there.

Service lifecycle, BLE transport, and the debounce coroutine are thin glue verified
on-device, not unit-under-test targets.

## Risks

- **Permission friction:** Notification access is a manual system grant; onboarding copy
  must be clear, and the mode is inert until granted.
- **BLE wake-ups / battery:** live pushes add radio activity; mitigated by the ~2 s
  debounce and the "only when `NOTIFICATIONS` is the active face" guard.
- **Glyph rendering:** digits in the letter-shaped upper pair render fine per the segment
  font map, but should be eyeballed on-device once.
- **Phase B is open-ended RE:** the alarm-set command may be multi-fragment or carry
  unknown fields; the beep outcome is genuinely uncertain (and probably unusable).

## Out of scope

- Per-app notification filtering or allow/deny lists ("really basic" support).
- Showing notification *content* (only a count fits two cells).
- Any phone-side beep (deferred unless Phase B is negative and a later spec adds it).
- Decoding alarm **snooze**/other alarm settings beyond what the chime capture incidentally
  reveals.

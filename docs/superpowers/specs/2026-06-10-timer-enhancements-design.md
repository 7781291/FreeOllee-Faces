# Timer Enhancements — design

**Date:** 2026-06-10 · **Status:** approved, ready for implementation plan
**Scope:** the Timer face. The 5-alarm scheduler is a **separate** spec (Spec B), not covered here.

## Goal

Bring FreeOllee-Faces' Timer feature to parity with the official Ollee app's Set Timer screen:

1. A persistent **Quick timer** — one independent countdown value (the official app's big
   `HH:MM:SS` picker), separate from the saved Timer Sets.
2. **Start on watch** — start a countdown remotely, both as the single Quick timer and as a Set's
   interval/HIIT sequence.

All three map onto the **existing** `0x26` write. No new BLE command is needed — this was confirmed
by an on-device capture (see [Capture evidence](#capture-evidence)).

## Background — what the capture proved (2026-06-10)

Captured four "Send to watch" actions from the official app (HCI snoop → `adb bugreport` →
btsnoop decode; watch `panther`/Pixel 7 bonded link). The four frames were **identical except for
the 4-byte header** `[00, MM, SS, byte3]`; the ten slot durations never changed. This isolates
every new behavior to the header:

| Action | Header | Meaning |
|--------|--------|---------|
| Baseline, "Start from app" OFF | `00 03 00 00` | picker 03:00, `byte3=00` |
| Primary picker → 07:07, still OFF | `00 07 07 00` | **only the header changed** — 10 slots byte-identical |
| "Start from app" ON, interval mode ON | `00 07 07 01` | `byte3=01` |
| Interval mode OFF, start still ON | `00 07 07 02` | `byte3=02` |

Two findings, both load-bearing:

- **The primary picker = header `MM:SS`, independent of the 10 slots.** Changing the picker moved
  only `byte1`/`byte2`; the slot table stayed byte-for-byte identical. This is the "Quick timer".
- **`byte3` is a start/mode selector** (the existing protocol doc and `buildTimerPacket` treat it as
  a reserved `0x00`):
  - `0x00` — save/configure only (no auto-start)
  - `0x01` — start now in **interval mode** (runs the 10-slot sequence)
  - `0x02` — start now in **single-timer mode** (runs the header countdown)

On-device, `0x01` started the interval sequence as decoded. The watch starts the timer **in the
background** (it does not auto-switch to the Timer face) — a firmware behavior, out of our control.

## Design

### Protocol layer — `OlleeProtocol.kt`

`buildTimerPacket` stops hardcoding the header and gains two parameters:

```kotlin
enum class TimerStartMode(val byte3: Int) { SAVE(0x00), START_INTERVAL(0x01), START_SINGLE(0x02) }

fun buildTimerPacket(
    durationsSeconds: List<Int>,   // exactly 10, unchanged
    headerSeconds: Int,            // seeds header MM:SS (MM clamped to 255, as today)
    startMode: TimerStartMode = TimerStartMode.SAVE,
): ByteArray
```

- Header becomes `[0x00, (headerSeconds/60).coerceAtMost(0xFF), headerSeconds%60, startMode.byte3]`.
- The 10 LE-uint32 slot words are written exactly as today.
- The current `headerSeconds`-less behavior (seed from slot 1) is **removed**; all callers pass an
  explicit `headerSeconds` (the Quick timer). This is the single source of the watch's primary
  countdown.

### Send semantics

Every `0x26` write carries header + 10 slots, so each send decides all three. The header **always**
carries the Quick-timer value, keeping the watch's primary countdown equal to the user's Quick timer
regardless of which Set is pushed:

| Action | header MM:SS | `startMode` | slots in frame |
|--------|--------------|-------------|----------------|
| Send a Set (configure) — existing radio | Quick timer | `SAVE` | that set's 10 |
| Start a Set (intervals) — new | Quick timer | `START_INTERVAL` | that set's 10 |
| Start the Quick timer (single) — new | Quick timer | `START_SINGLE` | **active set's 10** (preserve), or zeros if no active set |

Quick-start uses the **active set's** slots (`TimerSetsRepository.activeId()` → `get(id)`) so
starting the Quick timer never wipes the watch's stored interval list. Zeros only if there is no
active set.

### Persistence — `Prefs.kt`

Add one key:

```kotlin
var quickTimerSeconds: Int
    get() = settings.getInt(KEY_QUICK_TIMER_SECONDS, 180)   // default 03:00
    set(value) = settings.putInt(KEY_QUICK_TIMER_SECONDS, value.coerceAtLeast(0))
```

The Quick timer is a single value, so `Prefs` is the right home (Timer **Sets** stay in
`TimerSetsRepository`). Range: stored as seconds; the header `MM` byte caps the effective primary at
255 min (≈4h15m) — values above clamp for the header only (documented in the picker).

### ViewModel — `AppViewModel.kt`

- New state `quickTimerSeconds` (seeded from `Prefs`), `saveQuickTimer(seconds: Int)` persists +
  updates state.
- `sendTimerSet(set)` — now passes `headerSeconds = quickTimerSeconds`, `startMode = SAVE`
  (behavior otherwise identical: sets active on success, snackbar, `sending` guard).
- New `startTimerSet(set)` — `startMode = START_INTERVAL`. Sets active on success.
- New `startQuickTimer()` — `startMode = START_SINGLE`; slots = active set's durations or `List(10){0}`.
- All reuse the existing `ble.sendPacket(addr, …)` path, the no-watch-address snackbar, and the
  in-flight `state.sending` guard exactly as `sendTimerSet` does today.

### UI

**Quick Timer card — top of `TimerSetsScreen`** (above the sets list):
- Shows the current Quick-timer value (`TimerSetEditing.formatHms`).
- H:M:S editor reusing `TimerSetEditScreen`'s existing picker pattern (and
  `TimerSetEditing.hmsToSeconds` / `secondsToHms`).
- A **Start ▶** button → `onStartQuick()`.

**`TimerSetRow`** keeps the radio = "send/configure, set active" and gains a **Start ▶** TextButton
(→ `onStart(set)`, interval start) alongside Duplicate/Delete. New `TimerSetsScreen` callbacks:
`onStartQuick`, `onStart`, `onSaveQuick`, plus the `quickTimerSeconds` value passed in.

Rationale for this layout (vs. alternatives): tapping a set to "send-and-start" directly was
rejected — it conflates "select active set" with "start" and drops the configure-only path the
official app keeps. A per-set bottom sheet (Save / Start-intervals / Start-single) is more UI than
this earns. The chosen split mirrors the official app's separate "Send to watch" + start toggle.

## Testing

`OlleeProtocolTest` (pure JVM, existing suite):
- `buildTimerPacket` writes `byte3` per `startMode` (`SAVE`/`START_INTERVAL`/`START_SINGLE` →
  `00`/`01`/`02`), asserted against the captured frames.
- Header `MM:SS` derives from `headerSeconds` (incl. the 255-minute clamp), independent of slot 1.
- CRC/LEN still valid (the captured frames decode CRC-clean — use them as golden vectors).

`AppViewModelTest`: extend the existing `sendTimerSet` tests for the new `startTimerSet` /
`startQuickTimer` paths (no-watch-address snackbar, `sending` guard, active-id-on-success).

## Cross-repo: protocol reference update

Update `ollee-graphene/docs/reference/ollee-ble-protocol.md` (the canonical protocol home): the
Timer-slots section currently calls the 4th header byte a constant `00` and documents the header as
seeding only the default countdown. Add the `byte3` start/mode decode (`00`/`01`/`02`), note the
header `MM:SS` **is** the "Start timer from app" primary picker, and cite the 2026-06-10 capture.
Separate repo, separate commit.

## Out of scope

- The **5-alarm scheduler** (`0x25`) — Spec B.
- Stopwatch, world-time, faces table, and other `0x2x` targets.
- Reading timer state back from the watch (no read command exists; writes are last-writer-wins).

## Capture evidence

btsnoop decode, all four frames CRC-clean (`crcOK=True`), reassembled from the
`6e400002` (handle `0x000e`) writes:

```
Baseline  (start OFF):       0226 | header 00 03 00 00 | slots 180,30,180,30,0,60,120,600,900,1800
Picker->07:07 (start OFF):   0226 | header 00 07 07 00 | slots unchanged
Start ON  (interval):        0226 | header 00 07 07 01 | slots unchanged
Interval OFF (start single): 0226 | header 00 07 07 02 | slots unchanged
```

Ack each at `0x46` (`0006AA55536F0246`).

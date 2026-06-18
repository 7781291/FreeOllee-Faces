# Alarm & timer read-back confirmation — design

> **Status:** approved design, pre-implementation.
> **Date:** 2026-06-17.
> **Goal:** close the open-loop gap in alarm re-arms and timer pushes by *reading the watch
> back* after a write and confirming it actually took the value — auto-healing once before
> falling into the existing retry/notify ladder.

## Problem

The app is **write-only**. `WatchLink` (androidMain) opens GATT, writes to the RX
characteristic `6e400002`, and disconnects — it never subscribes to the notify characteristic
`6e400003` (TX), so the watch's answers are discarded. Every push is therefore fire-and-forget:
the app learns only whether the GATT *write* succeeded, never whether the watch *stored the
intended value*.

This matters most for the two records the app fully owns and re-writes:

- **Alarm re-arm.** The watch holds a single alarm; after every edit and every fire the app
  recomputes the soonest occurrence and re-arms that one slot. A re-arm that reaches the watch
  but is mis-stored (or a write the GATT layer reports as "sent" but the watch dropped) becomes
  a **silently skipped alarm** — the failure mode the README already guards against with blind
  2/5/15-minute retries.
- **Timer push.** Interactive "send to watch" reports success on GATT-write, not on the watch
  actually holding the slots.

The recovered protocol code (`../ollee-graphene/docs/reference/ollee-app-code.md`) shows the
watch exposes a **read** for each: `oap_get_alarm_req` (`0x2B`) and `oap_get_timer_req`
(`0x2C`). A read is `02 <target>` answered on notify with cmd = `target + 0x20` (`0x2B`→`4B`,
`0x2C`→`4C`), exactly mirroring the write acks (`0x25`→`45`, `0x26`→`46`). The `4B` alarm
read-back is already captured and decoded (it is the `0x25` record minus the play-now byte).
The workflow can flip from "we sent it" to "the watch confirms it holds it."

## Goal & non-goals

**Goal:** after writing the alarm or a timer set, read the corresponding record back, compare
the fields the app owns, and on mismatch re-send once before handing off to the existing
failure path — turning a silent miss into a healed write or an explicit alert.

**Non-goals:**
- **No periodic polling / drift detection.** Confirmation runs only right after a write, not on
  a schedule. (Catching a watch that *silently loses* the alarm hours later — via a periodic
  `0x2B` re-read — is a deliberate follow-on, not this spec.)
- **No new retry ladder.** Auto-heal is a single immediate re-send; a still-failing confirm
  feeds the *existing* `AlarmRearmRecovery` chain (alarm) or the existing push-failure surface
  (timer). Nothing new is invented downstream.
- **No other reads.** The read foundation is built generic, but only alarm + timer consume it
  here. Version, activity log, faces, etc. (see the opportunity survey) are out of scope.
- **Not a watch-event listener.** The watch sends no unsolicited frames; this is solicited
  read-after-write only. It cannot detect "the alarm just rang" — the phone-side scheduler
  already knows that.

## Architecture

Four units, boundaries chosen so the comparison logic — the part worth testing — lives in
`commonMain` and the GATT plumbing stays thin.

### 1. Read capability in the BLE layer (androidMain) — the one foundational change

- After service discovery, subscribe to notify char `6e400003` and write its CCCD descriptor
  (`0x2902`) to enable notifications.
- **Reassemble** fragmented notify frames: the watch splits a frame into `[20][FF]` (20-byte
  ATT payload then the trailing byte), so the handler accumulates bytes until the frame's
  LEN byte (byte index 1) is satisfied, then hands the complete buffer to the existing
  `OlleeProtocol.parseFrame()`.
- New suspend primitive on `BleClient`:
  `sendAndAwait(deviceAddress, requestPacket, expectedCmd, timeoutMs): Result<OlleeProtocol.Frame>`
  — writes `requestPacket`, then suspends until a notify frame with `cmd == expectedCmd`
  arrives or the timeout elapses (`Result.failure` on timeout / link loss). The held-link +
  `Mutex` model is unchanged; the link simply stays open across the round-trip.

### 2. Pure comparators (commonMain — fully unit-testable)

- **`AlarmConfirm`** — given the intended alarm parameters and a parsed `4B` `Frame`, compare
  only the fields the app owns and that affect correctness: `enable`, `hour`, `minute`,
  `chime`, `hourlyChime`. Fields the watch may echo or normalize (snooze, day-mask, hour-mask,
  terminator) are **not** compared, to avoid spurious mismatches. Fixture: the captured
  `…024B 01 00 00 0D 1E FE 01 05 C0 FF 0F FF`.
- **`TimerConfirm`** — same shape against the `4C` frame: compare the 10 slot durations and the
  header HH:MM:SS, ignoring the start-mode selector (a transient command, not stored state —
  the read-back is expected to drop it, as `4B` drops play-now). **Blocked on the `4C` layout
  being captured (Task 1 below) before it can be written against real bytes.**

### 3. Auto-heal orchestration

After the write, build the read request, `sendAndAwait` the response, and compare via the
relevant comparator:
- **Match** → done (silent for background alarm re-arms; "✓ Confirmed on watch" for timers).
- **Mismatch or read timeout** → re-send the original write **once**, re-read, re-compare.
- **Still mismatch/timeout** → hand off to the existing path: for alarms, treat it as a push
  failure into `AlarmRearmRecovery.afterPush(success = false, attempt)`; for timers, the
  existing `pushTimerFrame` failure surface ("⚠ Watch didn't take it").

### 4. Wiring

- **Alarm** — in `AlarmRearm.rearm()` (androidMain), after the existing
  `AndroidBleClient(ctx).sendPacket(address, AlarmSchedule.packetFor(latest))`, run the confirm
  read. A confirmed write feeds `afterPush(success = true, …)` exactly as today; a failed
  confirm (after the one heal) feeds `afterPush(success = false, …)`, reusing the entire
  ScheduleRetry/NotifyFailure chain unchanged. `AlarmSchedule.packetFor(latest)` gives the
  intended parameters for the comparator.
- **Timer** — in `AppViewModel.pushTimerFrame(packet, successMsg, onSuccess)`, after
  `ble.sendPacket` reports success, run the confirm read and reflect the result in the status
  message / `HomeState`. Applies to `sendTimerSet`/`startTimerSet`/`sendQuickTimer` via the
  shared helper; the intended durations/header come from the same values used to build `packet`.

## The one RE-risk, isolated as Task 1

The `4C` timer read-back format is **unobserved**. Before `TimerConfirm` can be implemented, an
on-hardware capture of `02 2C` → `4C` on watch `panther` (the same capture method that verified
the timer `byte3` on 2026-06-13) is needed to confirm whether `4C` mirrors the `0x26` payload
(4-byte header + ten LE-uint32 durations) minus the start-mode byte, or differs. Until then:

- The **alarm** half ships independently on fully-captured `4B` data.
- `TimerConfirm` is implemented only after Task 1 records the real `4C` layout; if `4C` turns
  out materially different from the `0x26` write format, the comparator adapts to what was
  captured (the spec's assumption is documented, not assumed-correct).

## Testing

- `commonMain` comparators (`AlarmConfirm`, `TimerConfirm`) are unit-tested against captured
  byte fixtures — the `4B` record now, the `4C` record after Task 1 — covering match, each
  single-field mismatch, and short/garbled frames (`parseFrame` returning null or `crcOk =
  false`).
- The androidMain notify subscription + reassembly is kept thin (no comparison logic), so it is
  exercised on-device rather than unit-tested; reassembly of the `[20][FF]` split is the one
  piece with a small pure helper that *can* be unit-tested with a fragmented-frame fixture.

## Acceptance criteria

1. `WatchLink` subscribes to notify `6e400003` (CCCD written) and reassembles `[20][FF]`
   notify frames into complete buffers passed to `OlleeProtocol.parseFrame()`.
2. `BleClient.sendAndAwait(address, requestPacket, expectedCmd, timeoutMs): Result<Frame>`
   exists, returns the matching notify frame, and fails cleanly on timeout / link loss.
3. `AlarmConfirm` (commonMain) compares `enable/hour/minute/chime/hourlyChime` of an intended
   alarm against a parsed `4B` frame, with unit tests over the captured fixture incl. mismatch
   and malformed-frame cases.
4. Alarm re-arm (`AlarmRearm.rearm`) confirms the write, re-sends once on mismatch, and on a
   still-failed confirm routes through `AlarmRearmRecovery.afterPush(false, attempt)` — no new
   retry logic added.
5. Task 1 capture of `02 2C`→`4C` on hardware is recorded (in the FreeOllee/ollee-graphene
   reference docs), and `TimerConfirm` + the timer-push confirmation wiring are implemented
   against that captured layout, surfacing confirmed / not-confirmed in the timer push status.
6. The protocol-reference discrepancy note is updated: the `4B`/`4C` read-backs used here are
   cross-referenced from `../ollee-graphene/docs/reference/ollee-app-code.md`.

## Risks & mitigations

| Risk | Mitigation |
|------|------------|
| `4C` layout differs from the `0x26` write format | Task 1 captures it first; `TimerConfirm` is written against real bytes, not the assumption. Alarm half is independent and unblocked. |
| Holding the link open for the read-back regresses the connect→write→disconnect timing | The `Mutex`/held-link model already supports a held connection; the read adds one round-trip with a bounded timeout, then the existing teardown proceeds. |
| Watch normalizes a field the app sent, causing a false mismatch | Comparators check only the app-owned semantic fields, explicitly excluding echo/normalized bytes. |
| Notify reassembly mis-frames a multi-fragment reply | LEN-byte-driven accumulation + `parseFrame`'s `crcOk` gate; a bad reassembly fails the CRC and is treated as a non-confirm (heals/alerts), never a false positive. |
| Auto-heal masks a persistent problem by resending forever | Heal is exactly one re-send; a second failure hands off to the existing bounded ladder (alarm) or a user-visible warning (timer). |

## Out of scope / follow-ups

- Periodic `0x2B` polling to detect and self-heal silent alarm drift (battery pull / firmware
  reset) — the natural next spec, reusing this read foundation.
- Other reads from the survey (version `0x2A`, activity log `0x27`/`0x28`, faces `0x37`).
- Resolving the `0x2E` / `0x32`–`0x35` protocol discrepancies via read-back diffing.

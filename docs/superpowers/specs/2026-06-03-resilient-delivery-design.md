# Resilient delivery — chunked writes + connection retry + wake guidance

**Date:** 2026-06-03
**Status:** design (approved)

## Summary

Two cohesive improvements to how FreeOllee-Faces delivers a value to the watch:

- **#3 Foundation:** fragment BLE writes longer than the ATT payload (the current single-write
  path silently cannot send frames > 20 bytes), and add the general framing primitives
  (`buildRawPacket`, `buildWeekdayPacket`, `sendPacket`) with unit tests. Ported from the
  `experiment/weekday-panel-poc` branch, **without** the throwaway `WeekdayPocActivity`.
- **#4 Reliability:** make delivery robust when the watch radio is asleep / slow-advertising —
  in-client quick reconnect retries, a face-agnostic worker backstop with backoff, and an
  actionable "wake the watch" notification.

Both touch the same `OlleeBleClient.writePacket` path, so they ship together.

## Background / motivation

On-device investigation (see `ollee-graphene/docs/reference/`):

- A single cold `connectGatt` to a sleeping/slow-advertising watch frequently times out at 8 s,
  but a second attempt seconds later usually succeeds. Today TEMPERATURE and STEPS get **one**
  attempt, then a "watch unreachable" notification and a wait until the next interval; only SUN
  retries (bespoke, 3×15 min).
- A 26-byte frame (e.g. the weekday table `02 34`) is rejected as `GATT_INVALID_ATTRIBUTE_LENGTH`
  (status 13) by the single-write path; the watch reassembles fragments by the frame `LEN`.
- The only way the phone influences a sleeping radio is a **physical wake** on the watch
  (long-press ALARM), so the failure notification should say so.

## Non-goals (explicitly deferred)

- The always-on upper-left **label** feature (opportunities #1/#2). `buildWeekdayPacket` lands as
  tested-but-dormant plumbing to make that trivial later, but no UI/face is added now.
- BLE **scan-assisted** connect and **MTU negotiation** — fragmentation at the 20-byte default is
  sufficient and already validated on-device.
- Any new user-facing face or setting.

## Design

### #3 Foundation — protocol + transport

`OlleeProtocol` (pure, fully unit-tested):

- `buildRawPacket(target: Int, payload: ByteArray): ByteArray` — general framing
  (`00 LEN AA55 CRChi CRClo 02 target payload…`, `LEN = inner.size + 4`, CRC-16/CCITT-FALSE),
  with **no** ASCII or 6-char cap. `buildPacket(target, value)` is refactored to delegate to it;
  its existing ASCII/length validation and behavior are unchanged.
- `buildWeekdayPacket(slots: List<String>): ByteArray` — requires exactly 7 entries of exactly
  2 ASCII chars, prepends the captured `00 00 7E 90` prefix, delegates to `buildRawPacket(0x34, …)`.

`OlleeBleClient`:

- `writePacket` fragments the packet into ≤ **20-byte** ATT chunks (`ATT_PAYLOAD = 20`, the
  default-MTU payload) and writes them **sequentially** — each chunk waits for
  `onCharacteristicWrite(GATT_SUCCESS)` before the next; the coroutine resumes only after the
  **last** chunk. A frame ≤ 20 bytes (the 14-byte nameplate) is a single chunk, so existing sends
  are byte-for-byte unchanged.
- `sendPacket(deviceAddress: String, packet: ByteArray): Result<Unit>` — public entry for
  prebuilt frames (e.g. a weekday-table write), reusing the same connect + chunked-write path.

Tests: port the `OlleeProtocolTest` additions — `buildRawPacket` reproduces the captured `02 34`
frame byte-for-byte (CRC `0x7EAB`), `buildWeekdayPacket` from `MO…SU` matches it, an all-`"TE"`
table parses with `crcOk`, and the validation rejections (not-7-slots, not-2-chars).

### #4 Reliability — three layers

**Layer 1 — in-client quick retry (`OlleeBleClient`).**
Wrap connect → discover → chunked-write in a retry loop: up to **3 attempts**, short backoff
(~2 s, then 4 s) between attempts, a fresh `connectGatt` each time, keeping the existing 8 s
per-attempt `withTimeout`. First success returns immediately; failure surfaces only after all
attempts fail. The retry **policy** (max attempts + delays) lives in a small **pure
`BleRetryPolicy`** object (mirroring the existing `weather/Retry` + `RetryPolicy`), so the policy
is unit-tested even though the GATT calls are not. All connect/discover/write/timeout failures are
treated as retryable (a few extra cheap attempts is preferable to a false "unreachable").

**Layer 2 — worker backstop (`AutoUpdateWorker` + `AutoUpdateSchedule`).**
Generalize SUN's existing `KEY_SUN_ATTEMPT` / `MAX_SUN_RETRIES` into a face-agnostic
`KEY_SEND_ATTEMPT`. When a run's send still fails after Layer 1:

- re-enqueue the worker with `attempt + 1` and a **backoff delay** instead of the normal interval;
- backoff schedule (pure, unit-tested function, e.g. `AutoUpdateSchedule.backstopDelayMs(attempt)`):
  **2 min → 5 min → 15 min**, i.e. up to ~3 backstop tries;
- on send **success** at any attempt, or after the budget is **exhausted**, resume the normal
  interval chain (`enqueueNext` at the regular cadence) with the attempt counter reset;
- the **sleep window** is respected exactly as today — no backstop retries fire while asleep.

This unifies TEMPERATURE/STEPS (today single-shot) and SUN (today bespoke 3×15 min) under one
mechanism. SUN's behavior changes only in that its first retries become faster (2/5/15 vs 15/15/15).

**Layer 3 — wake notification (`ErrorNotifier`).**
Fires only after the Layer-2 budget is exhausted, gated by the existing `NotifyDecision`
de-duplication. Reword `WATCH_UNREACHABLE` (and `SUN_UNREACHABLE`) `textFor(...)` to be
actionable about the physical wake:

> "Couldn't reach your watch after several tries. Long-press the ALARM button to wake its
> Bluetooth, then tap Retry."

The existing **Retry** action (`RetryReceiver`, which clears the dismissed-kind and enqueues an
immediate run) is unchanged.

## Data flow (one scheduled update)

1. `AutoUpdateWorker.doWork()` fetches the value, calls `OlleeBleClient.send(...)`.
2. `send` → Layer 1: up to 3 connect+chunked-write attempts with backoff.
3. **Success** → record "Sent", clear any failure notification, enqueue normal next interval
   (attempt reset).
4. **Failure** & not asleep & attempt < budget → Layer 2: re-enqueue with backoff, increment
   attempt, **no** notification yet.
5. **Failure** & budget exhausted → Layer 3: post the actionable wake notification, then resume
   the normal interval chain (attempt reset).
6. **Asleep** at any point → skip send/retry/notify (record "Asleep (power saving)"), normal chain.

## Error handling & edge cases

- **No double-chaining:** a failed run enqueues *either* a backstop retry *or* the normal next
  interval, never both — same invariant SUN already maintains.
- **Process death between retries:** Layer 2 survives it (WorkManager-scheduled); Layer 1 does not,
  which is fine — the next worker run re-enters Layer 1.
- **GATT status 13** should no longer occur for our frames once chunking lands; it is still treated
  as a (retryable) failure if it does.
- **Worker runtime budget:** Layer 1's 3 attempts (~≤30 s total) stay well within WorkManager's
  allowance; Layer 2 spreads tries over ~22 min via separate enqueues.

## Testing

- **Pure unit tests:** `BleRetryPolicy` (max attempts, delay sequence, retryable predicate);
  `AutoUpdateSchedule.backstopDelayMs` (2/5/15 then exhausted); the generalized attempt-carry
  logic; the ported `OlleeProtocol` builder/validation tests.
- **Not unit-tested (Android framework):** the actual GATT connect + chunked write — validated
  manually on-device (the chunked `0x34` write and the nameplate path are both already proven).

## Out-of-tree references

- `ollee-graphene/docs/reference/ollee-ble-protocol.md` — framing, fragmentation, connection
  lifecycle / waking the watch.
- `experiment/weekday-panel-poc` — source of the chunked-write + builder code being ported.

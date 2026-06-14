# Live connection status + reconnect button — design

> **Status:** approved design, pending implementation plan.
> **Date:** 2026-06-13.
> **Origin:** Port the official Ollee app's connection-status indicator and "reconnect"
> gesture into FreeOllee-Faces. Reverse-engineering background lives in the `ollee-graphene`
> repo, `docs/reference/ollee-ble-protocol.md` (§"Connection lifecycle & waking the watch").

## Background: how the official app does it

- The official (Flutter) app holds a **single persistent GATT connection** to the watch
  over Nordic UART. "Connection status" is nothing more than the BLE link state of that
  held connection (`onConnectionStateChange` → `STATE_CONNECTED`/`STATE_DISCONNECTED`).
  There is no application-level heartbeat — capture confirmed **the watch never initiates
  a frame**, so "connected" only means "the GATT link is currently up".
- "Drag down to reconnect" is a pull-to-refresh that **manually triggers a fresh scan +
  `connectGatt`** to the bonded device, re-running the startup register reads. The official
  app only reconnects on launch / manual trigger; it does not keep the link warm.

## The constraint that shapes this design

Two facts from the reverse-engineering work drive every decision:

1. **FreeOllee-Faces is connectionless today.** Every `send`/`sendPacket` does a fresh
   `connectGatt → discover → write → disconnect` with a retry loop
   (`AndroidBleClient.deliver`). It never holds a link open, so there is currently no
   connection whose status could be shown.
2. **The phone cannot wake a sleeping watch radio.** The watch sleeps its BLE radio to
   save battery; re-activation is a *physical* action on the watch (long-press ALARM, or
   triple-tap the Clock face), unless the user enabled the watch's "Bluetooth always on"
   setting. A reconnect attempt therefore *fails by design* whenever the radio is asleep —
   that is physics, not a bug, and the UX must say so.

## Decisions (from brainstorming Q&A)

| Decision | Choice |
|----------|--------|
| Status model | **Persistent GATT connection** (true live connected/disconnected), like the official app. |
| Hold scope | **Foreground only** — hold the link while the UI is foregrounded; release on background. No 24/7 service. |
| Send routing | All sends route through a **process-wide single connection point**; if the link is held open, writes ride it, otherwise one-shot connect/write/disconnect. |
| Background jobs | **Delegate to the held link** automatically (they share the same process-wide singleton). No per-caller changes. |
| Reconnect trigger | A **combined chip-button** in the header (not pull-to-refresh) that reflects state and triggers reconnect. |
| Reconnect failure | Show **wake instructions** ("long-press ALARM or triple-tap the Clock face, then tap Reconnect"). |

## Architecture

### `ConnectionStatus` (commonMain, new)

A sealed type / enum surfaced to the UI:

- `NoWatch` — no watch selected (replaces today's ad-hoc `watchSelected` hint logic for
  the chip; `watchSelected` itself stays for the existing per-card enable checks).
- `Connecting` — a connect attempt is in flight.
- `Connected` — GATT link is up and the Nordic UART service/characteristic resolved.
- `NotReachable` — a connect attempt finished without success (watch likely asleep).

### `WatchLink` — the process-wide connection point (androidMain, new)

A single process-scoped object that owns the optional held connection and is the one place
every write goes through. Responsibilities:

- Owns an optional held `BluetoothGatt?` and the resolved write characteristic.
- Exposes `status: StateFlow<ConnectionStatus>`.
- `suspend fun connect(address)` — connect + discover services, hold the link open, drive
  `status` (`Connecting` → `Connected`/`NotReachable`). Bounded by the existing
  `CONNECT_TIMEOUT_MS`.
- `fun disconnect()` — release the held link, set `status` to its idle value.
- `suspend fun send(packet): Result<Unit>` — **the unified send rule:**
  - if the link is held open and `Connected` → write through it (chunked, serialized);
  - otherwise → one-shot `connect → write → disconnect` (today's `deliver` logic).
- A **write `Mutex`** serializes all writes on the single-client link, so a foreground send
  and a background Worker that fires in the same process cannot collide.

The chunked-write logic currently inside `AndroidBleClient.writePacket` (20-byte ATT
payload fragmentation, the `BluetoothGattCallback` write loop) is **extracted into a shared
helper** used by both the held-link path and the one-shot path — single implementation, no
duplication.

### `AndroidBleClient` becomes a thin delegate

`AndroidBleClient.deliver` (and thus `send`/`sendPacket`) delegates to `WatchLink.send`.
The `BleClient` interface and all its callers are unchanged:

- `AutoUpdateWorker`, `AlarmRearm`, `NotificationCountService` keep constructing
  `AndroidBleClient(ctx)` and calling `send`/`sendPacket` exactly as today.
- Because they all funnel through the `WatchLink` singleton, their writes **automatically
  ride the held link** whenever the app is foreground and connected, and fall back to
  one-shot connect/write/disconnect otherwise. No conflict, no per-caller logic.

### `WatchConnection` (commonMain, new) — the ViewModel's view of the link

A small interface exposing only what the ViewModel needs: `status: StateFlow`,
`suspend fun connect(address)`, `fun disconnect()`. Backed by the same `WatchLink`
singleton in androidMain. Keeps `commonMain`/`commonTest` free of Android types and lets
tests substitute a fake.

### `AppViewModel` changes

- New constructor param `watchConnection: WatchConnection`.
- Mirror `watchConnection.status` into a new `HomeState.connectionStatus: ConnectionStatus`
  field (collected in `viewModelScope`).
- `onForeground()` → `connect(addr)` if a watch is selected; `onBackground()` →
  `disconnect()`. Wired to MainActivity's existing lifecycle observer (`ON_START` /
  `ON_STOP`), so the link is released on background and Workers that fire while
  backgrounded simply take the one-shot path.
- `onReconnect()` callback → `connect(addr)` again (the chip-button action).
- `sendAndReport` is unaffected in shape — it still calls `ble.send/sendPacket`, which now
  transparently routes through the held link via `WatchLink`.

### `MainActivity` wiring

- Construct the `WatchConnection` view of `WatchLink` and pass it into `AppViewModel`
  alongside the existing named params (`ble = AndroidBleClient(context)`, etc.).
- Reuse the existing lifecycle observer to call `viewModel.onForeground()` /
  `viewModel.onBackground()` on `ON_START` / `ON_STOP`.

### UI (`HomeScreen` / `HomeState` / `HomeCallbacks`)

- **`HomeState`**: add `connectionStatus: ConnectionStatus = ConnectionStatus.NoWatch`.
- **`HomeCallbacks`**: add `onReconnect: () -> Unit`.
- **Header chip-button** in the existing header `Row` (alongside Timers / Alarms / ⚙):
  a single labeled, tappable control that reflects state and triggers `onReconnect()`:
  - `Connected` → "● Connected" (green); tappable to force a reconnect.
  - `NotReachable` / `NoWatch` → "⟳ Reconnect" (neutral/grey).
  - `Connecting` → "Connecting…" with a small spinner; disabled while in flight.
- **Wake hint**: when `connectionStatus == NotReachable`, show a one-line hint under the
  header: *"Wake the watch: long-press ALARM or triple-tap the Clock face, then tap
  Reconnect."* This supersedes the current "No watch selected — open Settings (⚙)" hint
  when a watch is selected but unreachable.

## Data flow

```
foreground (ON_START) ─▶ ViewModel.onForeground() ─▶ WatchConnection.connect(addr)
        │                                                     │
        │                                   status: Connecting ─▶ Connected | NotReachable
        │                                                     │
        ▼                                                     ▼
  HomeState.connectionStatus ◀──────────── StateFlow ◀── WatchLink.status
        │
        ▼
  header chip-button  +  wake hint (when NotReachable)

user taps chip-button ─▶ onReconnect() ─▶ WatchConnection.connect(addr)

any send (UI or Worker) ─▶ BleClient ─▶ WatchLink.send(packet)
        ├─ link held & Connected ─▶ write through held link (mutex-serialized)
        └─ otherwise             ─▶ one-shot connect → write → disconnect

background (ON_STOP) ─▶ ViewModel.onBackground() ─▶ WatchConnection.disconnect()
```

## Single-client safety

The watch accepts one client at a time. With every write funneled through `WatchLink` and
serialized by its write `Mutex`, there is exactly one connection in the process and writes
never overlap — foreground sends and background Workers cooperate on the same link when
it's held, and each take the one-shot path when it isn't. (This is why the earlier
"accept a rare foreground/background conflict" edge no longer exists.)

## Error handling

- `connect` failure (timeout, status 133, service/characteristic not found) → `status =
  NotReachable`; no exception surfaced to the UI (the chip-button + wake hint communicate
  it). The existing `BleRetryPolicy` backoff still governs retry attempts within a connect.
- Held-link write failure / mid-session disconnect → `status` returns to a non-`Connected`
  value and the write `Result` is a failure; the next send falls back to one-shot.
- `send` results continue to flow through the existing `sendAndReport` snackbar path —
  no change to how send success/failure is reported to the user.

## Testing

- **commonTest** (no Android types): a fake `WatchConnection` with scriptable `status`
  exercises —
  - status transitions reflected into `HomeState.connectionStatus`;
  - `onForeground`/`onBackground` calling `connect`/`disconnect`;
  - `onReconnect` calling `connect`;
  - that a send while `Connected` and a send while not connected both succeed through the
    `BleClient` seam (routing correctness is asserted at the `WatchLink` seam where it can
    be faked).
- **Hardware**: the real `WatchLink` GATT state machine is verified on-device (long-press
  ALARM to wake, observe chip → Connected; let the radio sleep, observe chip →
  NotReachable + wake hint; confirm a foreground send rides the held link via the
  `OLLEE_BLE` debug log), consistent with how the rest of the BLE stack is validated.

## Out of scope (YAGNI)

- No 24/7 foreground service / always-on connection.
- No auto-retry-until-woken loop (chosen behaviour is the wake-instructions hint).
- No persistence of connection state across process death.
- No change to the `BleClient` interface or any of its existing callers' call sites.

# Connection status in the shared AppBar — design

**Date:** 2026-06-17
**Branch:** `feat/alarm-mode-quick-timer` (folded in alongside the alarm-mode quick timer)

## Problem

Watch connection status is shown only on the Home screen (`ConnectionRow`, `HomeScreen.kt:66`).
On the Timer, Alarms, Settings, and Edit screens there is no connection indicator, so a user can
tap a "Send" action while disconnected and get no signal that nothing reached the watch. This was
hit live on 2026-06-17: an alarm-mode "Send" logged a byte-correct frame at the BLE `deliver()`
chokepoint but the watch did nothing — because FreeOllee was not connected, which was invisible
from the Timer screen.

## Goal

Surface connection status on **every** screen, with minimal duplication, reusing the existing
`ConnectionStatus` / `connectionChip()` infrastructure.

## Approach

Add the indicator to the shared `AppBar` composable (used by all six screens) rather than
duplicating a row on each screen. One component change covers every current and future screen.

### 1. `AppBar` gains two optional parameters

`app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/AppBar.kt`

```kotlin
@Composable
fun AppBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    connectionStatus: ConnectionStatus? = null,
    onReconnect: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
)
```

- When `connectionStatus != null`, AppBar renders a **compact connection chip** in its trailing
  area, *before* `actions()`.
- The chip is driven by the existing `connectionChip(status)` →
  `ConnectionChip(label, clickable, showSpinner)` (`ble/ConnectionStatus.kt`).
- Chip content: a small `CircularProgressIndicator` when `showSpinner`, then the `label` text
  (e.g. `● Connected`, `Connecting…`, `⟳ Reconnect`).
- Chip color matches the existing Home logic: `Connected` → `primary`, `Connecting` →
  `onSurfaceVariant`, `NotReachable`/`NoWatch` → `error`.
- The chip is tappable (invokes `onReconnect`) **only when** `ConnectionChip.clickable` is true
  (i.e. `NotReachable` / `NoWatch`), matching the Home `ConnectionRow` behavior.
- **Backward compatible:** passing neither param renders exactly today's AppBar. The `actions`
  slot is currently unused by any screen, so there is no collision.

### 2. Shared color helper (DRY)

Extract the status→color mapping (currently inline in `HomeScreen.kt:202-204`) into one small
`@Composable` helper in the `ui` package (e.g. `connectionStatusColor(status): Color`), and use it
from both `HomeScreen`'s `ConnectionRow` and the new AppBar chip so they cannot drift.

### 3. Wiring per screen

- **Timer (`TimerSetsScreen`), Alarms (`AlarmsScreen`), Edit (`TimerSetEditScreen`)**: add
  `connectionStatus: ConnectionStatus` + `onReconnect: () -> Unit` params; pass them to their
  `AppBar(...)` call. Wire from `MainActivity` using `viewModel.state.connectionStatus` and
  `viewModel.onReconnect()`.
- **Settings (`SettingsScreen`)**: already receives `state: HomeState` (which has
  `connectionStatus`); pass `state.connectionStatus` and `onReconnect` to its AppBar.
- **Home (`HomeScreen`)**: unchanged. Keeps its detailed `ConnectionRow` (with the wake hint) as
  the connection hub; its AppBar passes no `connectionStatus` (no redundant chip).

## Data flow

`AppViewModel.state.connectionStatus` (already maintained, `ConnectionStatus` enum) → MainActivity
screen call sites → screen → `AppBar(connectionStatus = …, onReconnect = …)` → `connectionChip()` →
rendered chip. Reconnect taps call `viewModel.onReconnect()` (the existing path Home uses).

## Testing & verification

- No Compose UI tests exist in this codebase; verification is compile (`:app:assembleDebug`) plus a
  visual/hardware check (chip shows `Connected` when connected, `⟳ Reconnect` when not, on the Timer
  and Alarms screens; tapping it while disconnected triggers a reconnect).
- The only branching logic, `connectionChip()`, is already pure and unit-tested; the extracted
  color helper is a trivial `when` over the enum.

## Scope guard (YAGNI)

- No new connection states.
- No per-screen wake-hint text (Home keeps that; the chip's `⟳ Reconnect` label is the cue
  elsewhere).
- No new animations beyond the existing spinner.
- No shared-`Scaffold` refactor; screens keep rendering their own `AppBar`.

## Out of scope

The alarm-mode quick timer itself (already implemented and hardware-verified on this branch).

# Settings screen — design spec

**Date:** 2026-06-02
**Status:** Approved for planning
**Feature:** A dedicated Settings screen that consolidates the app-wide configuration
(watch, location, update interval, power-saving sleep), simplifying the per-face Home
screen. Ships in the same release as the Steps face (v0.7.0).

## Summary

Today the Home screen mixes app-wide configuration (watch selection, location entry,
power-saving sleep, the Temperature face's update interval) with the active face's own
controls. This moves all the app-wide settings into a single `Settings` screen reached by a
gear icon, leaving each face's Home view focused on its value plus face-specific controls.

It also **unifies the update interval into one global setting** — a preset selector
(**15 / 30 / 45 / 60 min, default 15**) shared by the interval-driven faces — replacing the
Temperature face's free-text minutes field and the Steps face's hardcoded 15-minute cadence.

## Navigation

- Add `data object Settings` to the existing `Screen` sealed interface (`Home`, `FacesList`,
  `Settings`).
- Home's top bar becomes `Title … Faces ⚙`. The gear `IconButton` sets
  `screen = Screen.Settings`; a back arrow (and system back) returns Home — the same pattern
  the Faces screen already uses.

## What moves into Settings

A single scrollable `SettingsScreen`, top to bottom:

1. **Watch** — the select/change row and the bonded-devices picker dialog.
2. **Location** — the label + freshness line, the manual latitude/longitude fields, and the
   "Use my location" button (the current `LocationFallback` content, now always shown here).
3. **Update interval** — *new shared setting*: a `SingleChoiceSegmentedButtonRow` with
   **15 / 30 / 45 / 60** minutes, default **15**. Applies to the interval-driven faces
   (Temperature, Steps). Sun is event-driven and Custom is manual, so they ignore it.
4. **Power-saving sleep** — the enable `Switch` plus the From/To `TimePickerDialog` buttons.

## What stays on Home (the active face)

Face title + the gear/Faces top bar, the value/preview, **Update now**, and face-specific
controls only:

- **Temperature** keeps the °F/°C selector.
- **Custom** keeps its text field + Send.
- **Sun**: value + Update now only.
- **Steps**: value + Update now; the "Pushed every N min while awake" line now reflects the
  shared interval.

### Missing-prerequisite hints

Because watch/location entry leaves Home, each face shows a one-line hint (not the old inline
entry card) when something it needs is unset, pointing at Settings:

- Temperature/Sun with no coordinates → "Location not set — open Settings (⚙)".
- Any non-Custom face with no watch selected → "No watch selected — open Settings (⚙)".

## Components

- `ui/Screen.kt` — add `Settings`.
- `ui/SettingsScreen.kt` *(new)* — the screen + a small `SettingsCallbacks` (onBack,
  onSelectWatch, onIntervalChange, onSleepEnabledChange, onSleepStartChange, onSleepEndChange,
  onLatChange, onLngChange, onUseMyLocation). Reuses the existing `HomeState` fields (watch,
  location, sleep, interval) — no duplicate state object.
- `ui/HomeScreen.kt` — add the gear button; remove the watch row, location row,
  `SleepControls`, and the Temperature interval field from the face bodies; replace the
  inline `LocationFallback` with the hint text. `HomeCallbacks` loses the moved actions and
  gains `onOpenSettings`. `SleepControls` and `LocationFallback` move to `SettingsScreen`.
- `MainActivity.kt` — render `Screen.Settings`; rewire the existing watch-picker, location
  launchers, sleep callbacks, and the interval callback to feed `SettingsScreen` (the lambdas
  already exist — this is mostly moving which screen consumes them). The bonded-devices
  picker dialog opens from Settings.
- `prefs/Prefs.kt` — replace `tempIntervalMinutes` (default 60) with
  `updateIntervalMinutes` (default 15), read back coerced to one of {15, 30, 45, 60}.
- `auto/AutoUpdateScheduler.kt` — both `TEMPERATURE` and `STEPS` branches call
  `scheduleIntervalFace(ctx, prefs, prefs.updateIntervalMinutes)`; remove the
  `STEPS_INTERVAL_MINUTES` constant.
- `auto/AutoUpdateWorker.kt` — `runTemperature` and `runSteps` both reschedule using
  `prefs.updateIntervalMinutes`.

## State

`HomeState`: replace `tempIntervalText: String` with `updateIntervalMinutes: Int` (default
15). `tempNextText()` / `interval()` read this directly. The Steps body's cadence line uses
it too.

## Error handling

Unchanged behavior, relocated: location/watch failures still surface via snackbars and the
existing `FailureKind` notifications. The new piece is the missing-prerequisite hints, which
are pure presentation derived from `watchSelected` / coordinate validity.

## Testing

- **Unit:** interval coercion in `Prefs` (a stored 60 or any non-preset value reads back as a
  valid preset; default is 15) — verified via a small pure helper
  `coerceInterval(raw): Int` that `Prefs` delegates to, so it is testable without Android.
- **On-device:** open Settings via the gear; change the interval and confirm the scheduler
  re-arms at the new cadence (Temperature "Next update" reflects it; Steps line updates);
  confirm watch selection, location entry, and sleep still work from Settings; confirm the
  Home hints appear when watch/location are unset.

## Scope / release

Bundled into PR #3 with the Steps face; **VERSION stays 0.7.0**. No new permissions or
dependencies. Out of scope: any new settings beyond the four consolidated here.

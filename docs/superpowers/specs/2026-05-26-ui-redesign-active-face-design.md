# UI Redesign — Active Face Design

**Date:** 2026-05-26
**Status:** Approved for planning

## Background

Today the app is a single scrolling screen that shows everything at once: a temperature
preview card, a sun-event preview card, a separate auto-update card (an Off/Temp/Sun selector
plus interval and sleep-window controls), always-visible latitude/longitude fields, a watch
selector, a temperature-unit toggle, and a custom-string sender.

The watch only ever displays one value at a time, yet the UI presents all three faces and a
detached auto-update selector as peers. This makes the central concept — *which face is the
watch currently showing?* — implicit. The redesign makes that concept the spine of the UI.

## Goals

1. Make **the active face** the default view: the app opens showing the one face the watch is
   currently displaying.
2. Provide a **Faces list** to switch the active face.
3. **Fold per-face concerns** (auto-update config, unit, custom text) into each face instead of a
   detached global card.
4. Show **timestamps and the next update time** for fetched data.
5. Surface the **manual lat/lng inputs only on location failure** — device location is used
   silently otherwise.
6. **Remove the ability to turn auto-update off.** Auto-update is always on for the active face,
   except the Custom face, which has no schedule.

## Non-goals

- Changing the BLE protocol, the Open-Meteo client, the sun-calc algorithm, or the retry/error
  layers (the recently-added `WeatherFetchError` / `withRetry` / `WeatherErrorCopy` are untouched).
- Changing the auto-update scheduling *math* (`AutoUpdateSchedule`) or the watchdog / boot-receiver
  re-arm mechanics. We only change which face the scheduler is keyed on.
- Adding a navigation library. Screen switching is hand-rolled (Approach A below).
- Adding a settings screen. The watch selector lives on Home.
- Multi-watch support or showing multiple faces on one watch.

## Design decisions (settled during brainstorming)

| Decision | Choice |
|----------|--------|
| Meaning of "active face" | The active face *is* the auto-update source *is* what the watch shows. Exactly one at a time. |
| Faces-list tap behavior | Tap **activates** the face and returns Home (fast switcher, not a hub). |
| Faces-list row content | Face name + active marker only. No fetching to render the list. |
| Watch selector location | On the Home view (compact line under the title). |
| Temperature unit | Folded into the Temperature face (it is face-specific). |
| Activation send model | Activating always pushes to the watch; it re-fetches first **only if** data is unknown or stale, otherwise pushes the cached value. |
| Staleness | Temperature: last fetch older than the interval, or cached unit ≠ current unit. Sun: cached next-event time has passed (recompute — cheap). |
| Manual refresh | A "Update now" button on Temperature/Sun forces a fresh fetch + push, bypassing the cache. |
| Location inputs | Hidden while device location works; appear inline on Temperature/Sun only when location is denied or a fix fails. |
| Temperature config | Keep both: interval (min 15) and power-saving sleep window. |
| Sun config | None — event-driven. |
| Custom face | No schedule. Activating pushes the saved string if one exists, else waits for input. |
| Navigation | Approach A: a sealed `Screen` state in `mutableStateOf` + `BackHandler`. No nav library. |
| First-launch active face | `TEMPERATURE`. Legacy `auto_source == OFF` migrates to `TEMPERATURE`. |

## Screens & navigation

Two screens, switched by a `Screen` value held in `AppRoot`:

```kotlin
sealed interface Screen {
    data object Home : Screen
    data object FacesList : Screen
}
```

- `AppRoot` holds `var screen by remember { mutableStateOf<Screen>(Screen.Home) }` and renders a
  `when (screen)`.
- **Home** is the default. A **Faces** action in the top bar sets `screen = FacesList`.
- **FacesList** has a `BackHandler { screen = Home }`; tapping a row also returns Home.
- The bonded-devices watch picker remains a `BondedDevicesDialog` shown over Home (unchanged
  pattern).

## The active-face model

`AutoSource { OFF, TEMPERATURE, SUN }` is replaced by:

```kotlin
enum class ActiveFace { TEMPERATURE, SUN, CUSTOM }
```

- `CUSTOM` becomes a first-class active face (it was not selectable before).
- There is no `OFF`. One face is always active.
- `TEMPERATURE` and `SUN` drive an auto-update chain; `CUSTOM` takes the cancel path that `OFF`
  used to take.

The file `auto/AutoSource.kt` is renamed/replaced by `auto/ActiveFace.kt`. All references
(`Prefs`, `AutoUpdateScheduler`, `AutoUpdateWorker`, UI) switch to `ActiveFace`.

## Data model (`prefs/Prefs.kt`)

**Carried over unchanged:** `lastLat`, `lastLng`, `watchAddress`, `tempUnit`,
`tempIntervalMinutes`, `sleepEnabled`, `sleepStartMin`, `sleepEndMin`,
`lastAutoSendMs`, `lastAutoSendSummary`.

**Changed / new:**

| Property | Key | Type | Notes |
|----------|-----|------|-------|
| `activeFace` | `active_face` | `ActiveFace` | Default `TEMPERATURE`. Migration below. |
| `tempValue` | `temp_value` | `Float?` | Cached temperature in `tempCacheUnit`. |
| `tempCacheUnit` | `temp_cache_unit` | `TempUnit?` | Unit the cached value was fetched in. |
| `tempFetchedMs` | `temp_fetched_ms` | `Long?` | When the cached temperature was fetched. |
| `customText` | `custom_text` | `String` | Persisted custom string (was transient-only). |
| `customSentMs` | `custom_sent_ms` | `Long?` | When the custom string was last sent. |

**Migration:** the mapping is a pure function on the enum so it stays JVM-unit-testable without an
Android `Context`:

```kotlin
// ActiveFace.kt
fun fromLegacyAutoSource(name: String?): ActiveFace  // "OFF"/null → TEMPERATURE, "SUN" → SUN, else TEMPERATURE
```

`Prefs.activeFace`'s getter reads `active_face`; if absent, it falls back to
`ActiveFace.fromLegacyAutoSource(sp.getString("auto_source", null))` and persists the result. All
other legacy keys are read as-is. No keys are deleted destructively.

`recordAutoSend(summary)` is unchanged; a new helper `recordTempFetch(value, unit, ms)` writes the
three temperature-cache fields together.

## Staleness logic (new pure function)

A pure, unit-tested function decides whether the cached temperature can be pushed without a
re-fetch:

```kotlin
// auto/Staleness.kt (or similar)
fun isTempCacheFresh(
    fetchedMs: Long?,
    cacheUnit: TempUnit?,
    currentUnit: TempUnit,
    intervalMin: Int,
    nowMs: Long,
): Boolean
```

Returns `true` only when `fetchedMs != null`, `cacheUnit == currentUnit`, and
`nowMs - fetchedMs < intervalMin * 60_000`. Sun has no analogous cache — `SunCalc.nextEvent` is
recomputed on demand and is "stale" exactly when its returned time is in the past, which the
recompute resolves implicitly.

## Activation logic (Faces-list tap, in `MainActivity`)

When a row is tapped:

1. `prefs.activeFace = face`; update UI state; `screen = Home`.
2. `AutoUpdateScheduler.reschedule(context)` — arms the interval chain for `TEMPERATURE`, the
   event chain for `SUN`, cancels the chain for `CUSTOM`.
3. Push to the watch (only if a watch is selected and coords exist where needed):
   - **Temperature:** if `isTempCacheFresh(...)` → push the formatted cached value. Else fetch via
     `OpenMeteoClient.currentTemp(..., RetryPolicy.Preview)`, cache it, then push.
   - **Sun:** recompute `SunCalc.nextEvent`, push its payload.
   - **Custom:** if `customText` is non-empty → push it (stamp `customSentMs`); else show the input
     and push nothing.

"**Update now**" (Temperature/Sun Home) bypasses the cache: always fetch/recompute fresh, then
push.

## Home view

Common chrome (top bar + watch line) wraps a per-face body.

- **Top bar:** active face name + a **Faces** action.
- **Watch line:** `Watch: <name>` with a `change` affordance, or `Watch: none — tap to select`.
  Tapping opens `BondedDevicesDialog`.

### Temperature face

```
+----------------------------------+
| Temperature             [Faces]  |
| Watch: Ollee-A1B2       change   |
|----------------------------------|
|             72 °F                |
|       Updated 2:34 PM            |
|       Next update 3:34 PM        |
|         [ Update now ]           |
|----------------------------------|
|  Unit   [ °F | °C ]              |
|  Every  [ 60 ] min               |
|  Sleep  22:00 → 06:00      [x]   |
+----------------------------------+
```

- `Updated` = `tempFetchedMs`. `Next update` = `AutoUpdateSchedule.nextTemperatureFire(now,
  interval, sleep)`; if it lands after a sleep boundary, append `(after sleep)`.
- Changing the unit re-fetches (cache unit mismatch → stale) and re-pushes.
- Changing the interval / sleep window calls `AutoUpdateScheduler.reschedule`.

### Sun face

```
+----------------------------------+
| Sun event               [Faces]  |
| Watch: Ollee-A1B2       change   |
|----------------------------------|
|           Sunset                 |
|          8:12 PM                 |
|       Updated 2:34 PM            |
|    Next: sunset at 8:12 PM       |
|         [ Update now ]           |
+----------------------------------+
```

- The watch shows the next sun event; after it passes, auto-update recomputes and pushes the
  following one, so `Next` ≈ the displayed event time.
- `Updated` = last push time (`lastAutoSendMs` for background runs / the activation push time).
- No schedule config.

### Custom face

```
+----------------------------------+
| Custom                  [Faces]  |
| Watch: Ollee-A1B2       change   |
|----------------------------------|
|  [ HELLO_ ]   (up to 6 chars)    |
|       [ Send to watch ]          |
|----------------------------------|
|  Sent 'HELLO' at 2:34 PM         |
+----------------------------------+
```

- The text field is bound to `customText` (persisted on change).
- `Send` pushes via `OlleeBleClient` and stamps `customSentMs`.
- No `Updated`/`Next update` line — there is no schedule. The status line reads
  `Sent '<text>' at <time>` after a send.

### Location fallback (Temperature/Sun only)

Shown **only** when location is denied or a fix fails. Replaces the value area until coords are
available; never shown while device location works:

```
|  Location unavailable            |
|  Lat [______]  Lng [______]      |
|  [ Use these coords ]            |
|  [ Grant location ]              |
```

The launch-time location attempt (already in `MainActivity`'s `LaunchedEffect`) decides this: a
successful fix hides the inputs; a denial or failure shows them. Manually entered coords persist to
`lastLat`/`lastLng` and trigger a refresh, exactly as today.

## Faces list view

```
+----------------------------------+
| ←  Faces                         |
|----------------------------------|
| (•) Temperature              >   |
| ( ) Sun event                >   |
| ( ) Custom                   >   |
+----------------------------------+
```

- Three static rows. The active face shows a filled marker.
- No fetching to render. Tapping a row runs the activation logic above, then returns Home.

## Scheduler & worker changes

- `auto/AutoUpdateScheduler.kt`: `reschedule` reads `prefs.activeFace`. `TEMPERATURE` and `SUN`
  branches are unchanged in mechanics; the `OFF` branch becomes the `CUSTOM` branch
  (`wm.cancelUniqueWork(WORK_NAME)`). The watchdog is unchanged.
- `auto/AutoUpdateWorker.kt`: reads `prefs.activeFace` instead of `autoSource`; `CUSTOM` returns
  `Result.success()` without scheduling (mirrors the old `OFF`). The `TEMPERATURE` success path
  additionally calls `recordTempFetch(...)` so the Home "Updated" line reflects the background
  fetch. The sun path is unchanged.

## Data flow

```
Faces list tap ─► prefs.activeFace = face
                  AutoUpdateScheduler.reschedule()        (arm TEMP/SUN, cancel CUSTOM)
                  push:
                    TEMP  → fresh cache? push cached : fetch → cache → push
                    SUN   → recompute event → push
                    CUSTOM→ customText? push : wait
                  screen = Home

Background worker ─► activeFace
                    TEMP → fetch → recordTempFetch → send → recordAutoSend → enqueueNext
                    SUN  → recompute → send → recordAutoSend → enqueueNext
                    CUSTOM → success (no schedule)

Home "Update now" ─► force fetch/recompute → cache (temp) → push
```

## Testing

Mirrors the existing unit-test-only pattern (no instrumented/UI tests).

- **`StalenessTest`** (new): `isTempCacheFresh` — fresh within interval, past interval, unit
  mismatch, never-fetched (`null`).
- **`ActiveFaceTest`** (new): `fromLegacyAutoSource` mapping — `"OFF"`/`null` → `TEMPERATURE`,
  `"TEMPERATURE"` → `TEMPERATURE`, `"SUN"` → `SUN`, unknown string → `TEMPERATURE`. (Pure function;
  `Prefs` itself stays untested, consistent with the codebase.)
- **`AutoUpdateScheduleTest`** (existing): unchanged — next-fire math is reused as-is.
- The Compose UI (Home bodies, Faces list, location fallback) is **not** unit-tested, consistent
  with the current codebase. A manual verification checklist is produced under
  `docs/superpowers/` covering: default-to-active-face on launch; switching faces from the list
  pushes correctly; stale vs. fresh activation (re-fetch vs. cached push); "Update now"; the
  Custom send + persistence across relaunch; the location-failure fallback appearing only on
  failure; and `CUSTOM` cancelling the auto-update chain.

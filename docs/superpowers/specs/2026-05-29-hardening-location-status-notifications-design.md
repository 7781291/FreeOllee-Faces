# Hardening: Location Caching, In-Context Status, Background Error Notifications

**Date:** 2026-05-29
**Status:** Approved for planning
**Predecessor specs:**
- `docs/superpowers/specs/2026-05-26-ui-redesign-active-face-design.md` (active-face UI)
- `docs/superpowers/specs/2026-05-25-auto-update-scheduling-design.md` (background chain)

## Background

Three rough edges surfaced after the active-face redesign shipped, each targeting how
the app behaves when the user *isn't* looking at it:

1. **Location is requested too often.** Every cold launch fires a fresh 10 s GPS fix
   whenever location permission is held (`MainActivity.kt:246-278`), even though
   `lastLat`/`lastLng` are already cached. The only on-demand refresh control is the
   *"Use my location"* button buried inside the failure-only `LocationFallback` card.

2. **The bottom status line is detached from the design.** `HomeScreen.kt:122` pins a
   single free-floating `state.status` string at the bottom — a catch-all mixing
   location-fix status, send progress, and errors. The active-face redesign organized
   everything else around the per-face value/buttons; this line never got folded in.

3. **Background failures are invisible.** The WorkManager chain runs while the app is
   closed, but its only observability is `lastAutoSendSummary` — an in-app line you see
   *only if you open the app*. The auto-update spec listed "No per-event push
   notifications" as a non-goal; this spec reverses that so failures become visible and
   the app can be hardened against them.

These three are independent enough to ship as separate slices, but they share the Home
layout and the background path, so they are specified together and implemented in order
(§1 → §2 → §3).

## Goals

1. **Cached-first location.** Use saved coordinates by default; only fetch a fresh fix
   when they are stale (≥ 1 week) or on explicit user request. Make location freshness
   and an on-demand refresh visible on Home.
2. **In-context status.** Remove the global bottom status line; route every signal to
   where it belongs (location line, value area, inline by the button, or a transient
   Snackbar).
3. **Background error notifications.** Raise a notification when a background update
   fails, with transition-only firing and auto-clear so one outage is one buzz, not a
   stream.

## Non-goals

- No change to the BLE protocol, the Open-Meteo client, `SunCalc`, or the retry/error
  layers (`WeatherFetchError` / `withRetry` / `WeatherErrorCopy`).
- No change to the scheduling math (`AutoUpdateSchedule`) or the chain/watchdog/boot
  re-arm mechanics — only new call-sites within `AutoUpdateWorker`.
- No background-location permission and no GPS fixes from the worker. Background work
  still uses saved coordinates only.
- No navigation/settings screen added. The location line and notification permission
  prompt live within the existing Home + `MainActivity` flow.
- No notifications for *successful* background sends — failures only (with auto-clear on
  recovery).

---

## §1 — Location: cached-first, freshness-aware

### Launch behavior

The unconditional `locationSource.fetch()` in `MainActivity`'s `LaunchedEffect(Unit)`
(`MainActivity.kt:246-278`) is replaced by a freshness decision:

| Situation | Action |
|---|---|
| Saved coords, fetched < 1 week ago | Use them silently. **No GPS fix.** |
| Saved coords, fetched ≥ 1 week ago, permission held | Use saved coords immediately (the watch needs a value); kick a non-blocking background fix. Success → update coords + restamp. **Failure → surface the error** (location line + Snackbar; see §2); keep rendering with stale coords. |
| No saved coords, permission held | First-run fix (as today). |
| No saved coords, no permission | Show the existing manual-entry / grant fallback. |

The stale-refresh fix runs in the existing coroutine `scope` and does not block first
paint — the UI renders saved coords first, then updates in place if the fix succeeds.

### New Prefs field

| Property | Key | Type | Notes |
|---|---|---|---|
| `lastLocationFetchedMs` | `location_fetched_ms` | `Long?` | When coordinates were last refreshed. |

Stamped to `now` on:
- a successful device fix (launch stale-refresh, first-run fix, or on-demand `refresh`), and
- a manual coord commit in `onCoordEdit` (manual entry resets staleness — the user just set them).

A staleness constant `LOCATION_STALE_MS = 7L * 24 * 60 * 60 * 1000` lives alongside the
launch logic (or in a small pure helper — see Testing).

### Location line on Home

A persistent line directly under the watch line in `HomeScreen`, above the
`HorizontalDivider()`:

```
Temperature                    [Faces]
Watch: Ollee-A1B2              change
Location: 47.6062, -122.3321 · 3h ago   refresh
─────────────────────────────────────
```

- Freshness label derived from `lastLocationFetchedMs`: `just now` / `3h ago` / `5d ago`.
- `refresh` → on-demand fix; reuses the existing `onUseMyLocation` callback logic
  (requests permission if not held). While fetching, the line reads `Locating…`.
- No coords yet → `Location: not set`, with the affordance reading `set`.
- Stale-refresh failure (launch) → `Location: 47.6062, -122.3321 · 8d ago · refresh failed`
  plus a Snackbar (§2).

`HomeState` gains: `locationLabel: String`, `locationFreshness: String?`,
`locating: Boolean`. The failure-only manual lat/lng `LocationFallback` card is
**unchanged** — still shown only on denial/failure of an explicit attempt.

---

## §2 — Status folded into context

The global `HomeState.status` field and the bottom `Text(state.status, …)`
(`HomeScreen.kt:122`) are **removed**. Each message routes to where it belongs:

| Today's bottom-line message | New home |
|---|---|
| "Getting location fix…" / "Got fix…" / "Location failed" | The **location line** (§1) — `Locating…`, coords + freshness, or failure marker |
| "Sending '…'" (in progress) | Inline near the button; `HomeState.sending` already drives button state |
| "Sent '…'" / "Send failed: …" | **Snackbar** (auto-dismiss) |
| Weather fetch error | Already shown in the value area via `PreviewState.Error` (`tempPreview`/`sunPreview`) — unchanged, no longer duplicated |
| "Bluetooth permission denied" | Snackbar |
| Stale-refresh failure (§1) | Location line marker **+** Snackbar |
| "Selected <watch>" | Dropped — the watch line already updates to the device name |

### Snackbar plumbing

- `MainActivity` adds a `SnackbarHostState` and wires a `SnackbarHost` into the existing
  `Scaffold` (`MainActivity.kt:56`).
- A small `showSnackbar(message)` helper launches on the existing `scope`.
- `sendAndReport` (`MainActivity.kt:422-432`) stops writing `status` and instead calls
  `showSnackbar(...)` on success/failure; the in-progress `sending = true` still drives
  the button. The BT-permission-denied branch and the stale-refresh-failure branch also
  call `showSnackbar(...)`.

All `state.copy(status = …)` writes throughout `MainActivity` are removed; the affected
copies now write to the location line fields, set `sending`, set `PreviewState.Error`, or
call `showSnackbar`.

---

## §3 — Background error notifications

A new `notify/` package, following the codebase's pure-core + thin-Android-glue pattern
(`SunCalc`, `AutoUpdateSchedule`, `Staleness`).

### Pure decision core — `notify/NotifyDecision.kt`

Unit-tested, no Android dependencies:

```kotlin
sealed interface NotifyAction {
    data class Notify(val kind: FailureKind) : NotifyAction
    data object Clear : NotifyAction
    data object Nothing : NotifyAction
}

enum class FailureKind { WATCH_UNREACHABLE, WEATHER_FETCH_FAILED, SETUP_INCOMPLETE, SUN_UNREACHABLE }

fun decide(current: FailureKind?, lastNotified: FailureKind?, inSleep: Boolean): NotifyAction
```

Rules:

- **Success** (`current == null`): `Clear` if something was showing (`lastNotified != null`),
  else `Nothing`. → **auto-clears on recovery.**
- **Failing + `inSleep`** → `Nothing` (don't post, don't advance state; re-evaluated at the
  next awake tick).
- **Failing + nothing shown** (`lastNotified == null`) → `Notify(current)`. → **one buzz per
  outage** (the healthy→failing transition).
- **Failing + same kind already shown** → `Nothing` (no re-alert while stuck).
- **Failing + different kind shown** → `Notify(current)` (the failure mode changed).

### Android glue — `notify/ErrorNotifier.kt`

- Creates one `NotificationChannel` ("Background update problems") on first use.
- Carries out a `NotifyAction` against **one reused notification id** (at most one error
  notification exists at a time).
- `Notify` builds a notification whose tap `PendingIntent` opens `MainActivity`; copy maps
  per `FailureKind` (e.g. *"Watch unreachable — last update didn't reach Ollee-A1B2"*).
- `Clear` cancels the notification id.

### Worker integration — `auto/AutoUpdateWorker.kt`

At each existing outcome site (where `recordAutoSend(...)` is already called), compute the
matching `FailureKind?` (null on success), then:

```kotlin
val inSleep = prefs.sleepEnabled &&
    AutoUpdateSchedule.isInSleepWindow(nowMinOfDay, prefs.sleepStartMin, prefs.sleepEndMin)
val action = NotifyDecision.decide(kind, prefs.lastNotifiedKind, inSleep)
ErrorNotifier.apply(ctx, action)          // posts/clears the notification
if (action !is NotifyAction.Nothing) prefs.lastNotifiedKind = action.resultingKind()
```

Mapping of existing summaries → kinds:

| Worker outcome | `FailureKind?` |
|---|---|
| `Sent '<payload>'` (temp or sun) | `null` (success → clears) |
| `Asleep (power saving)` | `null` (not a failure; temp produces no overnight errors) |
| `Skipped: set location/watch in app` | `SETUP_INCOMPLETE` |
| `Skipped: weather fetch failed…` | `WEATHER_FETCH_FAILED` |
| `Skipped: watch unreachable` (temp) | `WATCH_UNREACHABLE` |
| `Retry N: watch unreachable` (sun, intermediate) | `null` (still trying — no notify, no clear*) |
| `Skipped: watch unreachable` (sun, retries exhausted) | `SUN_UNREACHABLE` |
| `Skipped: no sun event (polar)` | `null` (not user-actionable) |

\* The intermediate-retry case must not *clear* an existing notification while a real
outage is ongoing. It maps to a third state, `Pending` (≈ `Nothing` for both notify and
state-advance), so `decide` is not called with `null` mid-outage. Simplest encoding:
the worker skips the `decide`/`apply` call entirely on intermediate retries.

### Permission & manifest

- `AndroidManifest.xml` gains `<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />`.
- Runtime request (Android 13+) is **lazy, from `MainActivity`**: on launch, if a
  background config is active (`activeFace != ActiveFace.CUSTOM` && watch selected) and the
  permission is not yet granted/requested, launch a `RequestPermission` for
  `POST_NOTIFICATIONS` with a one-line rationale. Reuses the existing
  `rememberLauncherForActivityResult` pattern (`MainActivity.kt:280-309`).
- **Graceful degradation:** if denied, the app is unaffected — failures still record to
  `lastAutoSendSummary`; only the push notification is suppressed. No cold prompt at first
  open (privacy-conscious GrapheneOS default).

### New Prefs field

| Property | Key | Type | Notes |
|---|---|---|---|
| `lastNotifiedKind` | `last_notified_kind` | `FailureKind?` (String-backed, defensive parse → null) | What the error notification currently shows; survives across worker runs. |

---

## New / changed files

| File | Change |
|---|---|
| `prefs/Prefs.kt` | Add `lastLocationFetchedMs`, `lastNotifiedKind` (+ keys). |
| `location/LocationSource.kt` | Unchanged (still the fix mechanism). |
| `MainActivity.kt` | Freshness-gated launch logic; location-line state + `refresh`; remove `status`; `SnackbarHostState` + `showSnackbar`; lazy `POST_NOTIFICATIONS` request. |
| `ui/HomeScreen.kt` | Add location line; remove bottom status `Text`; `HomeState` field changes (`locationLabel`, `locationFreshness`, `locating`; drop `status`). |
| `notify/NotifyDecision.kt` *(new)* | Pure transition logic + `FailureKind` / `NotifyAction`. |
| `notify/ErrorNotifier.kt` *(new)* | Channel + single-id post/clear + tap intent + per-kind copy. |
| `auto/AutoUpdateWorker.kt` | Compute `FailureKind?` at each outcome; call `decide` + `ErrorNotifier`; honor `inSleep`. |
| `AndroidManifest.xml` | `POST_NOTIFICATIONS` permission. |
| `app/src/test/.../notify/NotifyDecisionTest.kt` *(new)* | Pure unit tests. |
| `app/src/test/.../location/LocationFreshnessTest.kt` *(new, if helper extracted)* | Staleness + freshness-label tests. |

## Testing

Mirrors the existing unit-test-only pattern (pure JVM; no instrumented/UI tests).

- **`NotifyDecisionTest`** (new) — every `decide` branch: healthy→fail (Notify),
  same-kind persists (Nothing), kind-changes (Notify), fail→success (Clear),
  success→success (Nothing), fail-while-`inSleep` (Nothing), recover-while-`inSleep`
  (Clear still fires — recovery isn't suppressed). 
- **`LocationFreshnessTest`** (new, if the staleness check + `Long → "3h ago"` label are
  extracted to a pure helper) — under/over the 1-week threshold; label buckets
  (`just now`, hours, days).
- **Existing tests** (`StalenessTest`, `ActiveFaceTest`, `AutoUpdateScheduleTest`,
  `SunCalc`, `DisplayFormatter`, `OpenMeteoClient.buildUrl`) — unchanged, must keep
  passing.
- Compose UI (location line, Snackbar wiring) and the `ErrorNotifier`/worker glue are
  **not** unit-tested, consistent with the codebase. A manual on-device verification
  checklist is appended under `docs/superpowers/` covering:
  - launch with fresh saved coords → no GPS fix; with week-old coords → silent refresh
    (and, when it fails, line marker + Snackbar);
  - the location line freshness label + on-demand `refresh`;
  - send confirmations appear as Snackbars, nothing left at the bottom;
  - watch off across a tick → one notification appears (not per-tick), and clears on the
    next successful tick;
  - the same failure persisting → no re-alert; overnight (sleep window) → suppressed,
    surfacing at wake if still broken;
  - `POST_NOTIFICATIONS` denied → app still works, no notifications.

## Implementation order

1. **§1 Location** — Prefs field, launch logic, location line. Establishes the location
   line that §2 routes fix-status into.
2. **§2 Status folding** — Snackbar host, remove `status`, reroute every message. Shares
   the Home rewrite with §1, so done back-to-back to avoid rework.
3. **§3 Notifications** — additive: new `notify/` package, worker hooks, Prefs field,
   manifest + lazy permission.

Each slice is a separate plan task, executed sequentially via subagent-driven development
(shared files — `MainActivity.kt`, `HomeScreen.kt`, `Prefs.kt` — make parallel agents a
poor fit).

## References

- Active-face redesign: `docs/superpowers/specs/2026-05-26-ui-redesign-active-face-design.md`
- Background chain: `docs/superpowers/specs/2026-05-25-auto-update-scheduling-design.md`
- Android 13 notification runtime permission: `https://developer.android.com/develop/ui/views/notifications/notification-permission`

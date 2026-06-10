# Home/complications merge — design

**Date:** 2026-06-10
**Status:** Implemented
**Branch:** `feat/kmp-cross-platform`

## Problem

The Phase 8 device smoke test surfaced two issues:

1. **An inert card.** On Home, card taps only expand/collapse a config drawer
   (`onToggle`). The Sun card has no config, so it is built with `onToggle = null`
   (`HomeScreen.kt`) — no click handler, no ripple, nothing. Meanwhile the ACTIVE
   chip and highlight make cards *look* like the primary control surface. Tapping
   the Sun card does literally nothing, which reads as a broken app. (The separate
   complications picker works — verified on device — but users reasonably tap the
   cards instead.)
2. **A fake reading.** The Notifications card shows "0 unread" before notification
   access is granted. `notificationCount` is only ever written by
   `NotificationCountService` (a `NotificationListenerService` Android won't run
   pre-grant), so the displayed zero is the pref default, not data.

Root decision (user): the picker screen is redundant — activation should happen
directly on Home, and the picker goes away.

## Design

### 1. Home layout & interaction

The scroll column adopts the picker's two section labels, which explain the
control semantics:

- **"Name tag"** above the four complication cards — mutually exclusive, so each
  card gets a `RadioButton`.
- **"Weekday slot"** above the Notifications card — independent overlay, keeps
  its `Switch`.

Card header layout: `◉ radio · title · ▸ chevron`.

- **Radio click → activate.** Calls `activate(...)` on `AppViewModel` via a new
  `HomeCallbacks.onActivate: (ActiveComplication) -> Unit`. No navigation —
  selection reflects in place (radio moves, card highlight moves), same as the
  Phase 8 picker behavior.
- **Card body tap → expand/collapse config drawer** (unchanged `onToggle`
  behavior). The chevron remains the visual cue and flips ▸/▾.
- **Sun card:** no config, so no chevron and body tap stays a no-op — but it now
  has the radio, so it is never inert.

Active indication: checked radio + the existing `primaryContainer` fill and
2 dp `primary` border. **Removed:** the `Active: X` header line and the
`ActiveChip` pill — redundant with the radio.

Top bar: the `Complications` `TextButton` is removed (`Timers` and ⚙ remain).
The `Complications` headline stays — the screen *is* the complications list now.
The `Update active now` button and version footer are unchanged.

```
  Complications            Timers  ⚙
  ──────────────────────────────────
  Name tag
  ┌──────────────────────────────┐
  │ ○ Temperature           [▸]  │   body tap = expand (°F/°C)
  │   Currently: 81.9°F          │   radio = activate
  └──────────────────────────────┘
  ┌──────────────────────────────┐
  │ ○ Sun event                  │   no chevron, no drawer
  │   Sunset at 8:29 PM          │
  └──────────────────────────────┘
  ┏━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┓
  ┃ ◉ Steps                 [▸]  ┃   active: filled + bordered
  ┃   Today: 2,625 steps         ┃
  ┗━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┛
  ┌──────────────────────────────┐
  │ ○ Custom                [▸]  │
  └──────────────────────────────┘
  Weekday slot
  ┌──────────────────────────────┐
  │ Notifications   ● on    [▾]  │
  │ 3 unread                     │
  │ ──────────────────────────── │
  │ Show count in weekday  [on]  │
  │ [Update now]                 │
  └──────────────────────────────┘
  [ Update active now ]
```

### 2. Notifications card absorbs the detail screen

Collapsed status line (replaces today's `if (enabled) "$count unread" else "Off"`):

| State | Line |
|---|---|
| enabled && access granted | `"N unread"` |
| enabled && no access | `"Needs notification access"` |
| disabled | `"Off"` |

This kills the fake "0 unread": a count renders only when the listener service
can actually have written one.

Drawer contents: the existing switch + explainer, the grant-access button
(shown when enabled && !granted, unchanged), plus the detail screen's
**"Update now"** button — a new `HomeCallbacks.onNotificationsUpdateNow`
wired to `viewModel.pushCountIfWatch()`, enabled when
`state.watchSelected && state.notificationAccessGranted && state.notificationsEnabled`.

Refresh-on-return: the old screen called `onResumeNotifications()` on entry to
re-check access after the user returns from system settings. After the merge,
MainActivity observes lifecycle `ON_RESUME` and calls
`viewModel.onResumeNotifications()` so the card updates promptly (the existing
60 s Home poll alone would lag).

### 3. Deletions

- `ui/ComplicationsListScreen.kt`, `ui/NotificationsScreen.kt`
- `Screen.ComplicationsList`, `Screen.Notifications` (and their `when` branches
  + the screen-keyed Notifications `LaunchedEffect` in `MainActivity`)
- `HomeCallbacks.onOpenComplications` and the top-bar button wired to it
- `ComplicationLabel.displayLabel()` stays — Home cards still use it

`AppViewModel.activate()` is untouched. **Frozen invariants hold:** persisted
pref key strings, pref file names, enum constant names, `applicationId`,
package segments — this change is UI + wiring only.

### 4. Tests

- `AppViewModelTest.activate_temperature_orderingAndPersistence` navigates to
  `Screen.ComplicationsList` to make its stays-put assertion non-vacuous; it
  switches to `Screen.Settings` (a surviving screen). Assertion strength is
  identical.
- No other unit test references the deleted screens (`VersionInfoTest`,
  `PrefsTest`, etc. unaffected).
- Gates: `./gradlew :app:assembleDebug` + `:app:testDebugUnitTest`. Device
  smoke (radio activation per card, drawer expansion, notifications status
  line pre/post grant, Update now push) is user-side — no device on the
  build host.

## Out of scope

- Watch BLE protocol, background engine, Settings/Timers screens.
- The deferred open items from the KMP plan (ViewModel factory scoping,
  snackbar Channel) stay deferred.

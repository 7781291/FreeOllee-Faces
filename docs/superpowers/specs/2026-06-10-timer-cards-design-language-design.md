# Timer cards adopt the complications design language — design

**Date:** 2026-06-10
**Status:** Approved (design)
**Branch:** `feat/kmp-cross-platform`

## Problem

After the Home/complications merge, the app's design language is: **radio =
activate, body tap = configure, active card = `primaryContainer` fill + 2 dp
`primary` border**. The Timer Sets screen still uses the old idiom: a plain
card, a `"● active"` text label, and an Edit/Send/Duplicate/Delete button row.
The user wants the active timer set to share the complications design language.

Semantic wrinkle: a timer set becomes active by being **successfully sent to
the watch** (`sendTimerSet` — async BLE, in-flight guard, `timerActiveId`
updates only on success), not by an instant local toggle.

## Design

All changes in `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/TimerSetsScreen.kt`
(`TimerSetRow`) plus one wiring line in `MainActivity.kt`.

### Card

- Active card (`set.id == activeId`): `primaryContainer` fill +
  `BorderStroke(2.dp, primary)` — identical to `ComplicationCard`'s treatment.
- The `"● active"` label is removed (checked radio + highlight carry it).

### Radio = send

- Header becomes `RadioButton(selected = active, ...) + set name`.
- Radio `onClick` fires the existing `onSend(set)` → `viewModel.sendTimerSet(set)`.
  Because `timerActiveId` updates only on a successful send, the radio
  *honestly* reflects what's on the watch: it moves when the push lands, stays
  put on failure (existing snackbar covers no-watch and send errors).
- Radios are `enabled = !sending` — visible feedback for the VM's existing
  in-flight guard. `TimerSetsScreen` gains a `sending: Boolean` parameter,
  wired from `state.sending` in MainActivity.
- The `Send` `TextButton` is removed (redundant with the radio).

### Body tap = configure

- Card body gets `.clickable { onOpen() }` (opens the editor; the radio's own
  `onClick` wins over it — same nested pattern as Home).
- The `Edit` `TextButton` is removed; the button row shrinks to
  `Duplicate · Delete`.

### Untouched

`AppViewModel` (including `sendTimerSet()`), `TimerSetsRepository`, persisted
`timer_sets` storage, `TimerSetEditScreen`, and the editor's own Send flow
(`MainActivity.kt` `Screen.TimerSetEdit` branch). Pure UI + one wiring param.

## Tests

No unit-test changes expected — 216 stay green; gates are
`./gradlew :app:assembleDebug` + `:app:testDebugUnitTest`. Device smoke
(user-side): radio tap sends and moves only on success; no-watch tap shows the
snackbar and the radio stays; radios grey out mid-send; body tap opens the
editor; Duplicate/Delete unchanged.

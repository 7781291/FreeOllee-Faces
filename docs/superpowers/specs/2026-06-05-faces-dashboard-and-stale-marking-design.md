# Faces dashboard + stale/error marking — design

**Date:** 2026-06-05
**Status:** Approved (brainstorm), pending implementation plan

## Summary

Four related "nits" that together reshape the Faces UX and make pushed values
self-describing about their freshness:

1. **Home becomes a live dashboard.** Instead of showing only the active face, Home
   shows all faces at once (Temperature, Sun event, Steps, Custom, Notifications),
   each polling and previewing the exact payload it *would* send — even when nothing
   is being sent to the watch.
2. **Stale/error `E` prefix.** When a name-tag push falls back to a cached value
   because this cycle's fetch failed, the payload is marked with a leading `E` so the
   watch face itself signals "this number is not current."
3. **Steps abbreviation.** Six-digit step counts, which can't fit alongside the `E `
   prefix, abbreviate to thousands (`100234` → `E 100k`). Without an error they show
   in full (`100234`).
4. **Timer "Save & send" closes Edit set.** The send action now navigates back to the
   timer-sets list like "Save" already does.

Scope: a single coherent change ("Faces dashboard + stale-marking"), one
implementation plan.

## Non-goals

- No change to how the **active face** is chosen or how **notifications** are toggled —
  those stay on the existing Faces screen (`FacesListScreen`) and Notifications screen.
  The dashboard is additive: it *shows* state and exposes each face's own settings.
- No change to the background delivery model: `AutoUpdateWorker` still fetches and
  pushes **only the active face** on its interval. The dashboard's "poll everything"
  behavior is foreground-only.
- Notification count is **out of scope** for the `E` prefix: it rides the weekday slot
  (`0x34`, 1–2 chars, no fetch to fail), not the 6-char name tag.

---

## 1. Home becomes a live dashboard

### Structure

`HomeScreen` stops switching on `activeFace`. It renders a stacked, scrollable list of
**face cards** in fixed order: **Temperature, Sun event, Steps, Custom, Notifications**.

Each card shows:

- **Title** and a status **badge**: `●active` on the active name-tag face; `⦿on` on the
  Notifications card when `notificationsEnabled`.
- **Human value** (`72°F`, `6:41p sunset`, `8,432`, `3 unread`) and the **mono watch
  payload** preview (`'  72#F'`), reusing the existing
  `PreviewState.Ready(payload, human)` rendering (`FaceValue`).
- Per-card **loading / error / no-event** states (the existing `PreviewState` arms).

### Interaction

- Tapping a card **expands inline controls** for that face:
  - **Temperature** → °F/°C segmented toggle.
  - **Steps** → the Health-access grant card (when not granted).
  - **Custom** → the custom text field + "Send to watch" button.
  - **Notifications** → enable switch + notification-access grant (or a link to the
    existing Notifications screen — see Open questions for plan).
- Picking the **active** face and toggling notifications still happen on the **Faces**
  screen; the dashboard reflects that choice via the badge. This keeps the change
  additive and avoids duplicating the single-select logic.

### Sends

- **Non-active cards are preview-only** — no send button. This is the core of the nit:
  "preview what they would send and be polling even if there is no intent to send it."
- The bottom **`[Update active now]`** button pushes the **active** face (today's
  `onUpdateNow` → `refreshActive(force = true, push = true)`).
- **Custom** keeps its own manual "Send to watch" button (send-on-demand by nature;
  works regardless of which face is active).

### Polling

- New `refreshAll(push = false)` calls the existing `refreshTemp` / `refreshSun` /
  `refreshSteps` (with `push = false`) and reads the live notification count.
- `refreshAll` runs:
  - when **Home becomes visible** (the existing `Screen.Home` `LaunchedEffect`), and
  - on a **~60 s foreground ticker** while Home is visible, cancelled when leaving Home.
- **Cost note:** only Temperature hits the network (Open-Meteo). Sun is computed locally
  (`SunCalc`), Steps is a local Health Connect read, Notifications is already live via
  the listener. So polling all faces while Home is open is cheap. Temperature still
  honors its existing within-interval cache (`isTempCacheFresh`) to avoid redundant
  network calls.
- **Background is unchanged:** `AutoUpdateWorker` fetches/pushes only the active face.

---

## 2. Stale/error `E` marking

### Trigger ("error fallback only")

`E` appears **only** when this cycle's fetch fails and we fall back to a cached value.

| Situation | Payload |
|---|---|
| Fresh fetch succeeds | normal (`'  72#F'`) |
| Within-interval cache reused (normal optimization) | normal (`'  72#F'`) |
| **Fetch fails, cache exists → push the cache** | **`E`-marked** (`'E 72#F'`) |
| Fetch fails, no cache | skip the push (no value to send) |

This applies in **both** paths so the watch and the dashboard agree:

- **Foreground preview** (`refreshAll`, `push = false`): on fetch-fail-with-cache, the
  refresh function yields `PreviewState.Ready(<E-payload>, "<human> (stale)")` instead of
  `PreviewState.Error`. You *see* the `E`-marked value you would send; nothing is sent.
  No cache → still `PreviewState.Error` + skip.
- **Actual send** (`AutoUpdateWorker`, and "Update active now" with `push = true`):
  build the same `E`-marked payload from the cached value.

### Placement rules

Format the value as today, then mark it stale:

- **Replace the leading pad space with `E`.**
  - Temp 2-digit `"  67#F"` → `"E 67#F"`.
  - Temp 3-digit `" 100#F"` → `"E100#F"`. (Temps are ≤3 digits, so `%4d#F` always leaves
    at least one pad space — `E` always fits without loss.)
  - Steps ≤5 digits `"  8432"` → `"E 8432"`; `" 12345"` → `"E12345"`.
- **Steps with no pad (6 digits):** abbreviate to thousands first to make room.
  - `"100234"` → abbreviate `"100k"` → prefix → `"E 100k"`.
  - Range: 6-digit steps are `100000..999999` → `"100k".."999k"` (4 chars) → `"E " +`
    4 chars = 6. (Counts already clamp to `999999`, so `"999k"` is the max.)
  - **Abbreviation only happens in `E` mode.** Without an error a 6-digit count shows in
    full: `"100234"`.
- **Sun (fills all 6 chars, no pad):** drop the trailing rise/set event char to free a
  slot for `E`.
  - `"6:41ps"` (sunset, 1-digit hour) → `"E6:41p"` (loses the `s`).
  - `"6:41ar"` (sunrise) → `"E6:41a"` (loses the `r`).
  - `"10:30s"` (2-digit hour) → `"E10:30"` (loses the `s`).
  - The time survives; only the rise-vs-set letter is sacrificed while stale.

### Scope

`E` applies to the fetched name-tag faces: **Temperature, Sun, Steps**.

- **Custom** is excluded — you type it and send it manually; there is no fetch to fail,
  so "stale" has no meaning.
- **Notifications** is excluded — weekday slot, local count, no fetch.

---

## 3. Timer "Save & send" closes Edit set

`MainActivity` `Screen.TimerSetEdit` wiring: `onSend` saves + sends but never navigates,
unlike `onSave`. Add the same navigation:

```kotlin
onSend = { s -> timerRepo.save(s); refreshTimers(); sendTimerSet(s); screen = Screen.TimerSets },
```

---

## 4. Files touched & testing

| File | Change |
|---|---|
| `ui/HomeScreen.kt` | Rewrite: render all five cards (Temp/Sun/Steps/Custom/Notifications) with badges, live values, and tap-to-expand controls; remove the `when(activeFace)` body switch. |
| `MainActivity.kt` | Add `refreshAll(push = false)` + a ~60 s foreground ticker tied to Home visibility; wire the dashboard callbacks; on fetch-fail-with-cache yield `Ready(E-payload, "… (stale)")`; the one-line timer `onSend` navigation fix. |
| `format/DisplayFormatter.kt` | Add a `stale` flag to `temperature` / `sunTime` / `steps`; a leading-pad→`E` helper; steps thousands-abbreviation; sun event-char drop. |
| `auto/AutoUpdateWorker.kt` | On fetch-fail fallback to cache, build the pushed payload with `stale = true`. |

### Testing

`DisplayFormatter` is pure → unit tests carry the weight (`DisplayFormatterTest`):

- Temp `E`: 2-digit (`E 67#F`) and 3-digit (`E100#F`).
- Steps `E`: ≤5-digit pad replacement (`E 8432`, `E12345`) and 6-digit abbreviation
  (`100234` → `E 100k`, `999999` → `E 999k`).
- Steps non-error baseline: `100234` stays full.
- Sun `E`: 1-digit hour drops event char (`6:41ps` → `E6:41p`), 2-digit hour
  (`10:30s` → `E10:30`).
- No-`E` baselines for all faces unchanged.

Dashboard rendering and foreground polling are UI wiring — verified on-device over adb.

## Open questions (for the implementation plan)

- Notifications card expansion: inline enable/grant controls vs. a link that opens the
  existing `NotificationsScreen`. Decide during planning; either keeps the screen.
- Exact foreground-ticker lifecycle (DisposableEffect vs. LaunchedEffect keyed on
  screen) — an implementation detail.

# Addendum: rebrand to "Super FreeOllee", rename faces→complications, fix active-complication switching

**Date:** 2026-06-06
**Status:** Approved (design)
**Branch:** `feat/kmp-cross-platform`
**Extends:** `2026-06-06-kmp-cross-platform-design.md`

## Summary

Three user-requested changes folded into the in-flight KMP/Compose Multiplatform
migration, scheduled as a new **Phase 8** that runs *after* Phase 6 (UI in `commonMain`
and stable), so the work is a clean reviewable pass rather than entangled with the
source-set moves:

1. **Rebrand** the app's display name to **"Super FreeOllee"**.
2. **Rename** the domain term **"face" → "complication"** (the correct smartwatch term)
   across code identifiers and UI text.
3. **Fix** the long-standing bug where it is unclear which complication is active and
   switching the active complication "doesn't seem to work."

## Constraints (carried from the base spec + release-signing requirements)

- **Package and `applicationId` stay `com.blizzardcaron.freeolleefaces`.** Changing
  either breaks the signed-release in-place update path for existing GrapheneOS users.
  The rebrand is a **display-name/docs** change only.
- **Persisted preference-key strings are unchanged.** The Kotlin identifier
  `KEY_ACTIVE_FACE` (or its rename) keeps the stored string value `active_face`, and the
  enum constant *names* stay `TEMPERATURE/SUN/STEPS/CUSTOM`, so a user's saved selection
  survives the update with no migration.

## The switching bug — root cause

The state plumbing is correct: `MainActivity.activate()` writes `prefs.activeFace`,
updates the `mutableStateOf` `HomeState`, and the Home cards bind to it. The problem is
**feedback**, two-fold:

- **Weak active indicator.** On Home the active complication is shown only by a faint
  `"active"` text badge on one `FaceCard` (`HomeScreen.kt`), with no header and no strong
  highlight — easy to miss.
- **No selection confirmation.** Tapping a row in the faces screen runs `activate()`,
  which synchronously sets `screen = Screen.Home`, instantly bouncing the user back. They
  get no confirmation on the selection screen, and the only Home feedback is the faint
  badge moving. Together it reads as "nothing happened."

Both symptoms share one root cause: **ambiguous feedback about which complication is
active.** The fix is a UX change, which is why it belongs in the UI refactor.

(If on-device testing shows the watch *nameplate itself* fails to change after the UX fix,
that is a separate BLE-push fault to diagnose with device logs — out of scope for this
addendum, captured as a follow-up if it surfaces.)

## Phase 8 — tasks

- **8.1 Rename enum & logic.** `ActiveFace` → `ActiveComplication`; `activeFace` →
  `activeComplication`; rename `ActiveFaceTest`. Enum value names and the persisted
  pref-key string are unchanged. Touches the already-in-`commonMain` enum plus its
  references in workers/scheduler/Prefs/UI.
- **8.2 Rename UI.** `FacesListScreen`→`ComplicationsListScreen`,
  `FaceRow`→`ComplicationRow`, `FaceCard`→`ComplicationCard`,
  `Screen.FacesList`→`Screen.ComplicationsList`, `onOpenFaces`→`onOpenComplications`; all
  visible labels "Faces"→"Complications".
- **8.3 Active-complication UX fix.** Make the active complication unmistakable: a
  prominent "Active: <name>" indicator on Home and a clear highlight on the active card
  (beyond the faint text badge), plus visible confirmation of a selection instead of an
  immediate silent bounce. **Acceptance:** switching is obviously reflected and
  demonstrably persists across app restart.
- **8.4 Rebrand.** `app_name` → "Super FreeOllee"; rename the `Theme.FreeOlleeFaces`
  style; update README/docs. Package and `applicationId` untouched.
- **8.5 On-device verification.** User confirms switching works and the watch updates;
  final regression and signed-release sanity check.

## Testing strategy

- Rename-only changes (8.1, 8.2, 8.4) are covered by the existing unit suite compiling
  and passing; `ActiveFaceTest`→`ActiveComplicationTest` keeps the migration assertions.
- The UX fix (8.3) is verified by on-device smoke (8.5) since the active indicator and the
  watch push are not unit-testable. Any pure logic introduced (e.g. an "active label"
  helper) gets a `commonTest`.

## Risks

- **Rename churn vs. mid-migration moves** — mitigated by sequencing Phase 8 *after*
  Phase 6, when the UI files are already settled in `commonMain`.
- **Saved-selection loss** — mitigated by keeping persisted key strings and enum value
  names unchanged (only Kotlin identifiers change).
- **Display-name vs. package confusion** — explicitly: only the display name and docs
  change; package/`applicationId` are frozen for release continuity.

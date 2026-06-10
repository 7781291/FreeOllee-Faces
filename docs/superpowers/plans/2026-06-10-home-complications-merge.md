# Home/Complications Merge Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Activate complications directly from Home via per-card radios, fold the Notifications detail into the Home card (fixing the fake "0 unread"), and delete the now-redundant picker and detail screens.

**Architecture:** Evolve `HomeScreen` in place (spec approach A). The card header gains a `RadioButton` wired to `AppViewModel.activate()` through a new `HomeCallbacks.onActivate`; card-body tap keeps its expand/collapse role. The Notifications card absorbs the detail screen's "Update now" and gains an access-aware status line. Then `ComplicationsListScreen`, `NotificationsScreen`, and their `Screen` entries are deleted.

**Tech Stack:** Kotlin Multiplatform (single `androidTarget()`), Compose Multiplatform 1.8.2, Material3. Spec: `docs/superpowers/specs/2026-06-10-home-complications-merge-design.md`.

**Build host constraints:** Headless Raspberry Pi 500 (ARM64), no device attached — gates are `./gradlew :app:assembleDebug` and `./gradlew :app:testDebugUnitTest` (use 600000 ms timeouts; warm builds take 1–3 min). NEVER run `installDebug` or device tasks. Work in place on branch `feat/kmp-cross-platform` (no worktree). Every commit message ends with the trailer `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`.

**Frozen invariants (do not touch):** persisted pref key strings (`"active_face"`, `"NOTIFICATIONS"`, `"auto_source"`), pref file names (`"freeollee_faces_prefs"`, `"timer_sets"`), enum constant names `TEMPERATURE/SUN/STEPS/CUSTOM`, `applicationId`/package `com.blizzardcaron.freeolleefaces`, the androidMain WorkManager engine. This plan is UI + wiring only; `AppViewModel.activate()` is not modified.

---

### Task 1: Home cards activate via radios; drop Active header/chip and the top-bar picker button

**Files:**
- Modify: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/HomeScreen.kt`
- Modify: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/Callbacks.kt`
- Modify: `app/src/androidMain/kotlin/com/blizzardcaron/freeolleefaces/MainActivity.kt` (the `homeCallbacks` construction only)

Note: `ComplicationsListScreen` becomes unreachable after this task but still exists and compiles; it is deleted in Task 4.

- [ ] **Step 1: Replace `onOpenComplications` with `onActivate` in `Callbacks.kt`**

In `HomeCallbacks`, replace the line

```kotlin
    val onOpenComplications: () -> Unit,
```

with

```kotlin
    val onActivate: (ActiveComplication) -> Unit,
```

and add the import:

```kotlin
import com.blizzardcaron.freeolleefaces.auto.ActiveComplication
```

- [ ] **Step 2: Add `onNotificationsUpdateNow` to `HomeCallbacks` now (used by Task 2)**

So `Callbacks.kt` only changes once, also add at the end of `HomeCallbacks`:

```kotlin
    val onNotificationsUpdateNow: () -> Unit,
```

- [ ] **Step 3: Rework `HomeScreen.kt`**

3a. In the top bar `Row` (currently title + Timers/Complications/⚙), delete the line:

```kotlin
                TextButton(onClick = callbacks.onOpenComplications) { Text("Complications") }
```

3b. Delete the `Active:` header block entirely:

```kotlin
        Text(
            "Active: ${state.activeComplication.displayLabel()}",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
```

(`displayLabel` is still used for card titles — keep its import.)

3c. Add the picker's section-label helper as a private composable (verbatim from the old `ComplicationsListScreen`):

```kotlin
@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp),
    )
}
```

Inside the scrolling `Column`, insert `SectionLabel("Name tag")` immediately before the Temperature `ComplicationCard`, and `SectionLabel("Weekday slot")` immediately before `NotificationsCard(...)`. (The `SettingsHint` no-watch line stays above the first label.)

3d. Add `onActivate: () -> Unit` to `ComplicationCard` and render a radio. New signature:

```kotlin
@Composable
private fun ComplicationCard(
    title: String,
    active: Boolean,
    onActivate: () -> Unit,
    preview: PreviewState,
    updated: String?,
    next: String?,
    expanded: Boolean,
    onToggle: (() -> Unit)?,
    expandedContent: (@Composable () -> Unit)? = null,
)
```

Replace the header `Row` (the one containing `Text(title, ...)`, the `ActiveChip`, and the chevron) with:

```kotlin
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = active, onClick = onActivate)
                        Text(title, style = MaterialTheme.typography.titleMedium)
                    }
                    if (onToggle != null) {
                        Text(if (expanded) "▾" else "▸", style = MaterialTheme.typography.bodyMedium)
                    }
                }
```

The `RadioButton`'s own `onClick` wins over the surrounding header's `clickable { onToggle() }`, so radio = activate, body tap = expand. The `cardColors`/`border` active styling is unchanged. Add the import `androidx.compose.material3.RadioButton`.

3e. Delete the `ActiveChip` private composable and its KDoc — nothing references it after 3d.

3f. Wire `onActivate` at each of the four call sites, e.g. for Temperature:

```kotlin
            ComplicationCard(
                title = ActiveComplication.TEMPERATURE.displayLabel(),
                active = state.activeComplication == ActiveComplication.TEMPERATURE,
                onActivate = { callbacks.onActivate(ActiveComplication.TEMPERATURE) },
                ...
```

Same pattern with `SUN`, `STEPS`, `CUSTOM`. Sun keeps `expanded = false, onToggle = null` (no chevron, body tap is a no-op — the radio is its activation affordance).

- [ ] **Step 4: Update `MainActivity.kt` `homeCallbacks`**

Replace

```kotlin
        onOpenComplications = { viewModel.navigateTo(Screen.ComplicationsList) },
```

with

```kotlin
        onActivate = { viewModel.activate(it) },
```

and add (for the Step 2 field; the button itself arrives in Task 2):

```kotlin
        onNotificationsUpdateNow = { viewModel.pushCountIfWatch() },
```

- [ ] **Step 5: Gates**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest` (timeout 600000)
Expected: BUILD SUCCESSFUL, 216 tests, 0 failures.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: activate complications from Home via per-card radios

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 2: Notifications card absorbs the detail screen; honest pre-grant status

**Files:**
- Modify: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/HomeScreen.kt` (`NotificationsCard` only)
- Modify: `app/src/androidMain/kotlin/com/blizzardcaron/freeolleefaces/MainActivity.kt` (resume observer + card wiring)

- [ ] **Step 1: Status line + Update now in `NotificationsCard`**

1a. Add `onUpdateNow: () -> Unit` to the signature:

```kotlin
@Composable
private fun NotificationsCard(
    state: HomeState,
    expanded: Boolean,
    onToggle: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onGrantAccess: () -> Unit,
    onUpdateNow: () -> Unit,
)
```

1b. Replace the status line

```kotlin
                val human = if (state.notificationsEnabled) "${state.notificationCount} unread" else "Off"
```

with the access-aware version (a count renders only when the listener service can actually have written one):

```kotlin
                val human = when {
                    !state.notificationsEnabled -> "Off"
                    !state.notificationAccessGranted -> "Needs notification access"
                    else -> "${state.notificationCount} unread"
                }
```

1c. In the expanded drawer `Column`, after the existing grant-access `if` block, add the detail screen's push button:

```kotlin
                    Button(
                        onClick = onUpdateNow,
                        enabled = state.watchSelected && state.notificationAccessGranted && state.notificationsEnabled,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Update now") }
```

1d. At the `NotificationsCard(...)` call site in `HomeScreen`, add:

```kotlin
                onUpdateNow = callbacks.onNotificationsUpdateNow,
```

- [ ] **Step 2: Refresh notification state on activity resume**

In `MainActivity.kt`'s `AppRoot`, after the existing `LaunchedEffect` blocks, add a lifecycle observer so the card updates promptly when the user returns from granting access in system settings (`refreshAllPreviews`'s 60 s poll alone would lag):

```kotlin
    // Re-check notification access/count when the activity resumes — e.g. returning from the
    // system notification-access settings page. The 60 s dashboard poll alone would lag here.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.onResumeNotifications()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
```

Imports to add:

```kotlin
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
```

(`androidx.compose.ui.platform.LocalLifecycleOwner` may be flagged deprecated; if it does not resolve or you prefer the new home, use `androidx.lifecycle.compose.LocalLifecycleOwner` — whichever compiles. `viewModel.onResumeNotifications()` already exists and only re-reads prefs + the access checker, so calling it on every resume is cheap.)

Leave the existing `LaunchedEffect(screen) { if (screen == Screen.Notifications) ... }` block alone — Task 4 deletes it.

- [ ] **Step 3: Gates**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest` (timeout 600000)
Expected: BUILD SUCCESSFUL, 216 tests, 0 failures.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "feat: fold notifications detail into the Home card; honest pre-grant status

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 3: Retarget the AppViewModelTest stays-put assertion to a surviving screen

`activate_temperature_orderingAndPersistence` navigates to `Screen.ComplicationsList` so its "activate() does not navigate" assertion can't pass vacuously (the screen defaults to `Home`). `ComplicationsList` dies in Task 4, so retarget to `Screen.Settings` first — identical assertion strength. Doing this *before* the deletion keeps every task green.

**Files:**
- Modify: `app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/AppViewModelTest.kt`

- [ ] **Step 1: Swap the screen**

Replace

```kotlin
        // Navigate to the picker first; activate() must NOT move us off it. (screen defaults to
        // Home, so without this the "stays put" assertion below would be vacuous.)
        vm.navigateTo(Screen.ComplicationsList)
```

with

```kotlin
        // Navigate away from Home first; activate() must NOT move us. (screen defaults to Home,
        // so without this the "stays put" assertion below would be vacuous.)
        vm.navigateTo(Screen.Settings)
```

and replace

```kotlin
        // 3. screen UNCHANGED — activate() no longer navigates; the selection reflects in place.
        assertEquals(Screen.ComplicationsList, vm.screen,
            "screen should STAY on ComplicationsList after activate() (no silent bounce to Home)")
```

with

```kotlin
        // 3. screen UNCHANGED — activate() no longer navigates; the selection reflects in place.
        assertEquals(Screen.Settings, vm.screen,
            "screen should STAY on Settings after activate() (no silent bounce to Home)")
```

Also update the KDoc line above the test that says the screen stays on "(ComplicationsList)" to say "(Settings)".

- [ ] **Step 2: Run the test class**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.blizzardcaron.freeolleefaces.AppViewModelTest'` (timeout 600000)
Expected: BUILD SUCCESSFUL, 3 tests pass.

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "test: retarget activate() stays-put assertion to Settings

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 4: Delete the picker and notifications screens

**Files:**
- Delete: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/ComplicationsListScreen.kt`
- Delete: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/NotificationsScreen.kt`
- Modify: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/Screen.kt`
- Modify: `app/src/androidMain/kotlin/com/blizzardcaron/freeolleefaces/MainActivity.kt`

Keep: `auto/ComplicationLabel.kt` and its test (`displayLabel()` is used by the Home cards).

- [ ] **Step 1: Remove the files**

```bash
git rm app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/ComplicationsListScreen.kt \
       app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/NotificationsScreen.kt
```

- [ ] **Step 2: Shrink `Screen.kt`**

Delete the two entries so it reads:

```kotlin
sealed interface Screen {
    data object Home : Screen
    data object Settings : Screen
    data object TimerSets : Screen
    data object TimerSetEdit : Screen
}
```

- [ ] **Step 3: Clean `MainActivity.kt`**

3a. Delete the imports:

```kotlin
import com.blizzardcaron.freeolleefaces.ui.ComplicationsListScreen
import com.blizzardcaron.freeolleefaces.ui.NotificationsScreen
```

3b. Delete the entire `Screen.ComplicationsList -> ComplicationsListScreen(...)` branch and the entire `Screen.Notifications -> NotificationsScreen(...)` branch from the `when (screen)` block.

3c. Delete the now-dead screen-keyed effect (the Task 2 resume observer replaces it):

```kotlin
    // The Notifications detail screen refreshes its count on entry. Home is covered by the
    // dashboard-polling effect above, so this only needs to handle the Notifications screen.
    LaunchedEffect(screen) {
        if (screen == Screen.Notifications) {
            viewModel.onResumeNotifications()
        }
    }
```

- [ ] **Step 4: Verify no stragglers**

Run: `grep -rn 'ComplicationsList\|NotificationsScreen\|Screen.Notifications' app/src docs/superpowers/plans/2026-06-06-kmp-cross-platform.md --include='*.kt'`
Expected: no hits in `app/src` (plan-doc prose hits are fine; run the grep against `app/src` alone if the flag mixes output).

- [ ] **Step 5: Gates**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest` (timeout 600000)
Expected: BUILD SUCCESSFUL, 216 tests, 0 failures.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "refactor: delete the complications picker and notifications screens

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 5: Final verification + spec status

**Files:**
- Modify: `docs/superpowers/specs/2026-06-10-home-complications-merge-design.md` (Status line only)

- [ ] **Step 1: Clean-ish full gate**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest` (timeout 600000)
Expected: BUILD SUCCESSFUL, 216 tests, 0 failures.

- [ ] **Step 2: Mark the spec Implemented**

Change `**Status:** Approved (design)` to `**Status:** Implemented`.

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "docs: mark Home/complications merge spec implemented

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

- [ ] **Step 4 (USER-side, not the implementer):** Device smoke — install the debug APK, then: each radio activates in place (highlight + radio move, no navigation); body tap expands Temperature/Steps/Custom drawers; Sun has no chevron; Notifications card shows "Needs notification access" pre-grant (no fake "0 unread"), real count + working "Update now" post-grant; access state updates promptly on return from system settings; active complication survives restart.


# Connection Status in Shared AppBar — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show watch connection status on every screen by adding a compact, tappable connection chip to the shared `AppBar`, reusing the existing `ConnectionStatus`/`connectionChip()` infrastructure.

**Architecture:** `AppBar` gains optional `connectionStatus`/`onReconnect` params and renders a compact chip (driven by `connectionChip()`) in its trailing area when a status is supplied. The status→color mapping is extracted from `HomeScreen`'s `ConnectionRow` into one shared `@Composable` helper so the chip and the Home row stay in sync. Timer/Alarms/Edit screens get the status threaded in from `MainActivity`; Settings already has it via `state`; Home is unchanged (keeps its detailed row).

**Tech Stack:** Kotlin Multiplatform (commonMain UI), Jetpack Compose Material3. Build: `./gradlew`. No Compose UI test harness exists — verification is compile + visual.

**Spec:** `docs/superpowers/specs/2026-06-17-connection-status-app-bar-design.md`

**Note on testing:** This change is pure Compose UI with no unit-testable branching beyond the
already-tested pure `connectionChip()` function. Following the existing codebase pattern (no Compose
UI tests; see the alarm-mode card task), verification is `:app:compileDebugKotlinAndroid` /
`:app:assembleDebug` plus a visual/hardware check. No new unit tests are added.

---

## File Structure

- **Modify** `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/AppBar.kt` — add
  `connectionStatus`/`onReconnect` params, the compact chip, and a shared `connectionStatusColor`
  helper (Task 1).
- **Modify** `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/HomeScreen.kt` — use the
  shared `connectionStatusColor` helper in `ConnectionRow` (Task 1).
- **Modify** `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/TimerSetsScreen.kt`,
  `AlarmsScreen.kt`, `TimerSetEditScreen.kt`, `SettingsScreen.kt` — pass status to their `AppBar`
  (Task 2).
- **Modify** `app/src/androidMain/kotlin/com/blizzardcaron/freeolleefaces/MainActivity.kt` — wire
  `viewModel.state.connectionStatus` / `viewModel.onReconnect()` into the screen calls (Task 2).

---

## Task 1: AppBar connection chip + shared color helper

**Files:**
- Modify: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/AppBar.kt`
- Modify: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/HomeScreen.kt:198-224` (the `ConnectionRow` color block)

**Context:** `AppBar` currently takes `(title, modifier, onBack, actions)` and renders a `Row` with
optional back button, the title (weight 1f), then `actions()`. The `actions` slot is currently
unused by any screen. `connectionChip(status)` returns `ConnectionChip(label, clickable, showSpinner)`
and is already defined in `ble/ConnectionStatus.kt`. `HomeScreen.ConnectionRow` (lines 198-224)
holds the status→color `when` that we extract.

- [ ] **Step 1: Extract the shared color helper into AppBar.kt**

In `AppBar.kt`, add this `internal` helper at file scope (below `AppBar`). Add imports
`androidx.compose.ui.graphics.Color` and `com.blizzardcaron.freeolleefaces.ble.ConnectionStatus`:

```kotlin
/** Status→color used by both the AppBar connection chip and Home's ConnectionRow, kept in one place. */
@Composable
internal fun connectionStatusColor(status: ConnectionStatus): Color = when (status) {
    ConnectionStatus.Connected -> MaterialTheme.colorScheme.primary
    ConnectionStatus.Connecting -> MaterialTheme.colorScheme.onSurfaceVariant
    ConnectionStatus.NotReachable, ConnectionStatus.NoWatch -> MaterialTheme.colorScheme.error
}
```

- [ ] **Step 2: Add the params and the compact chip to `AppBar`**

Replace the `AppBar` function in `AppBar.kt` with the version below (new params + trailing chip).
Add imports: `androidx.compose.foundation.layout.Spacer`, `androidx.compose.foundation.layout.size`,
`androidx.compose.foundation.layout.width`, `androidx.compose.material3.CircularProgressIndicator`,
`androidx.compose.material3.TextButton`, `com.blizzardcaron.freeolleefaces.ble.connectionChip`.

```kotlin
@Composable
fun AppBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    connectionStatus: ConnectionStatus? = null,
    onReconnect: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
) {
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Text("‹", style = MaterialTheme.typography.headlineSmall)
            }
        }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(start = if (onBack == null) 0.dp else 4.dp),
        )
        if (connectionStatus != null) {
            ConnectionChipCompact(connectionStatus, onReconnect)
        }
        actions()
    }
}

/** Compact connection indicator for the AppBar trailing area. Tappable to reconnect only when the
 *  chip is clickable (NotReachable / NoWatch). Mirrors Home's ConnectionRow look at smaller size. */
@Composable
private fun ConnectionChipCompact(status: ConnectionStatus, onReconnect: (() -> Unit)?) {
    val chip = connectionChip(status)
    val color = connectionStatusColor(status)
    if (chip.clickable && onReconnect != null) {
        TextButton(onClick = onReconnect) {
            Text(chip.label, color = color, style = MaterialTheme.typography.labelLarge)
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (chip.showSpinner) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(6.dp))
            }
            Text(chip.label, color = color, style = MaterialTheme.typography.labelLarge)
        }
    }
}
```

- [ ] **Step 3: Update `HomeScreen.ConnectionRow` to use the shared helper**

In `HomeScreen.kt`, in `ConnectionRow` (lines ~199-205), replace the inline color `when`:

```kotlin
    val color = when (status) {
        ConnectionStatus.Connected -> MaterialTheme.colorScheme.primary
        ConnectionStatus.Connecting -> MaterialTheme.colorScheme.onSurfaceVariant
        ConnectionStatus.NotReachable, ConnectionStatus.NoWatch -> MaterialTheme.colorScheme.error
    }
```

with:

```kotlin
    val color = connectionStatusColor(status)
```

(`connectionStatusColor` resolves from the same `ui` package — no import needed. Leave the rest of
`ConnectionRow`, including `wakeHint`, unchanged. If the `ConnectionStatus` import in HomeScreen.kt
becomes unused after this, leave it — it is still referenced by the `ConnectionRow` signature.)

- [ ] **Step 4: Compile to verify**

Run: `./gradlew :app:compileDebugKotlinAndroid`
Expected: BUILD SUCCESSFUL. (AppBar's new params are optional/defaulted, so all existing AppBar
call sites still compile unchanged; HomeScreen behavior is identical.)

- [ ] **Step 5: Commit**

```bash
git add app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/AppBar.kt \
        app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/HomeScreen.kt
git commit -m "feat(ui): add compact connection chip to shared AppBar

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 2: Wire connection status into the remaining screens

**Files:**
- Modify: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/TimerSetsScreen.kt`
- Modify: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/AlarmsScreen.kt`
- Modify: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/TimerSetEditScreen.kt`
- Modify: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/SettingsScreen.kt`
- Modify: `app/src/androidMain/kotlin/com/blizzardcaron/freeolleefaces/MainActivity.kt`

**Context:** `MainActivity` hosts each screen and has `viewModel.state.connectionStatus`
(`ConnectionStatus`) and `viewModel.onReconnect()`. `SettingsScreen` already receives
`state: HomeState` (which has `connectionStatus`). `TimerSetsScreen`, `AlarmsScreen`, and
`TimerSetEditScreen` take individual params and need two new ones. Each screen renders its own
`AppBar(title = ...)` — `AppBar(title = "Timer")` at `TimerSetsScreen.kt:68`,
`AppBar(title = "Alarms")` at `AlarmsScreen.kt:54`, `AppBar(title = "Edit set", onBack = onBack)` at
`TimerSetEditScreen.kt:57`, `AppBar(title = "Settings")` at `SettingsScreen.kt:52`. READ each call
site and screen signature first to confirm exact param names and the MainActivity call sites before
editing; line numbers are approximate.

- [ ] **Step 1: Thread status into `TimerSetsScreen`**

Add two params to the `TimerSetsScreen` composable signature (alongside the existing params):

```kotlin
    connectionStatus: ConnectionStatus,
    onReconnect: () -> Unit,
```

Update its `AppBar` call:

```kotlin
        AppBar(title = "Timer", connectionStatus = connectionStatus, onReconnect = onReconnect)
```

Add `import com.blizzardcaron.freeolleefaces.ble.ConnectionStatus` if not already present.

- [ ] **Step 2: Thread status into `AlarmsScreen`**

Add the same two params to `AlarmsScreen`'s signature and update its `AppBar` call:

```kotlin
        AppBar(title = "Alarms", connectionStatus = connectionStatus, onReconnect = onReconnect)
```

Add `import com.blizzardcaron.freeolleefaces.ble.ConnectionStatus` if not already present.

- [ ] **Step 3: Thread status into `TimerSetEditScreen`**

Add the same two params to `TimerSetEditScreen`'s signature and update its `AppBar` call (it already
passes `onBack`):

```kotlin
        AppBar(title = "Edit set", onBack = onBack, connectionStatus = connectionStatus, onReconnect = onReconnect)
```

Add `import com.blizzardcaron.freeolleefaces.ble.ConnectionStatus` if not already present.

- [ ] **Step 4: Pass status from `SettingsScreen`'s existing `state`**

`SettingsScreen` already has `state: HomeState`. Add one param `onReconnect: () -> Unit` to its
signature, and update its `AppBar` call to use the status it already holds:

```kotlin
        AppBar(title = "Settings", connectionStatus = state.connectionStatus, onReconnect = onReconnect)
```

(No `ConnectionStatus` import needed — `state.connectionStatus` is already typed via `HomeState`.)

- [ ] **Step 5: Wire all four call sites in `MainActivity`**

In `MainActivity.kt`, add to each screen call the new arguments, using `viewModel.state.connectionStatus`
and `viewModel.onReconnect()`:

- `TimerSetsScreen(...)`: add
  ```kotlin
              connectionStatus = viewModel.state.connectionStatus,
              onReconnect = { viewModel.onReconnect() },
  ```
- `AlarmsScreen(...)`: add the same two arguments.
- `TimerSetEditScreen(...)`: add the same two arguments.
- `SettingsScreen(...)`: add
  ```kotlin
              onReconnect = { viewModel.onReconnect() },
  ```
  (it already receives `state`).

Confirm the exact value used elsewhere for state — if MainActivity reads `viewModel.state` into a
local `val state = viewModel.state`, use `state.connectionStatus` to match the surrounding style.

- [ ] **Step 6: Build the debug APK to verify the host compiles**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/TimerSetsScreen.kt \
        app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/AlarmsScreen.kt \
        app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/TimerSetEditScreen.kt \
        app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/SettingsScreen.kt \
        app/src/androidMain/kotlin/com/blizzardcaron/freeolleefaces/MainActivity.kt
git commit -m "feat(ui): show connection chip on Timer, Alarms, Edit, and Settings

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Self-Review

**Spec coverage:**
- AppBar gains `connectionStatus`/`onReconnect`, renders compact chip from `connectionChip()`,
  tappable only when clickable, color via shared helper → Task 1. ✓
- Shared color helper extracted; Home's ConnectionRow uses it → Task 1 (Steps 1, 3). ✓
- Timer/Alarms/Edit threaded from MainActivity; Settings uses existing `state`; Home unchanged →
  Task 2. ✓
- Backward compatible (optional params; `actions` slot unused) → Task 1 Step 2/4. ✓
- Verification by compile (no Compose UI tests) → Task 1 Step 4, Task 2 Step 6. ✓

**Placeholder scan:** No TBD/TODO; every code step shows full code. ✓

**Type consistency:** `connectionStatusColor(status: ConnectionStatus): Color`,
`AppBar(..., connectionStatus: ConnectionStatus?, onReconnect: (() -> Unit)?, ...)`, screen params
`connectionStatus: ConnectionStatus` + `onReconnect: () -> Unit`, wired from
`viewModel.state.connectionStatus` / `viewModel.onReconnect()` — consistent across Tasks 1–2 and
matches the existing `connectionChip`/`ConnectionStatus`/`onReconnect` identifiers in the codebase. ✓

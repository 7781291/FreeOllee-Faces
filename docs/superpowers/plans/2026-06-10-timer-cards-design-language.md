# Timer Cards Design Language Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restyle timer set cards to the complications design language: radio = send/activate, body tap = open editor, active card = `primaryContainer` fill + 2 dp border.

**Architecture:** Pure UI change in `TimerSetRow` (`TimerSetsScreen.kt`) plus a `sending: Boolean` parameter wired from `state.sending` in MainActivity. `AppViewModel`, `sendTimerSet()`, the repo, and persisted storage are untouched.

**Tech Stack:** Kotlin Multiplatform (single `androidTarget()`), Compose Multiplatform 1.8.2, Material3. Spec: `docs/superpowers/specs/2026-06-10-timer-cards-design-language-design.md`.

**Build host constraints:** Headless Raspberry Pi 500 (ARM64), no device — gates are `./gradlew :app:assembleDebug` and `:app:testDebugUnitTest` (600000 ms timeouts). NEVER run `installDebug`. Work in place on `feat/kmp-cross-platform`. Commit messages end with `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`.

---

### Task 1: Restyle TimerSetRow to the complications card language

**Files:**
- Modify: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/TimerSetsScreen.kt`
- Modify: `app/src/androidMain/kotlin/com/blizzardcaron/freeolleefaces/MainActivity.kt` (the `Screen.TimerSets ->` branch only)

- [ ] **Step 1: Add the `sending` parameter to `TimerSetsScreen`**

In the `TimerSetsScreen` signature, after `activeId: String?,` add:

```kotlin
    sending: Boolean,
```

and in the `for (set in sets)` loop, pass it through to the row after `active = set.id == activeId,`:

```kotlin
                    sending = sending,
```

- [ ] **Step 2: Rewrite `TimerSetRow`**

Replace the entire `TimerSetRow` composable with:

```kotlin
@Composable
private fun TimerSetRow(
    set: TimerSet,
    active: Boolean,
    sending: Boolean,
    onOpen: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onSend: () -> Unit,
) {
    val cardColors = if (active) {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    } else {
        CardDefaults.cardColors()
    }
    val border = if (active) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
    Card(modifier = Modifier.fillMaxWidth(), colors = cardColors, border = border) {
        Column(
            modifier = Modifier.fillMaxWidth().clickable { onOpen() }.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Radio = send: timerActiveId only updates on a successful send, so the radio
                // moves when the push lands and stays put on failure (snackbar covers errors).
                RadioButton(selected = active, onClick = onSend, enabled = !sending)
                Text(if (set.name.isBlank()) "(unnamed)" else set.name,
                    style = MaterialTheme.typography.titleMedium)
            }
            val count = set.slots.count { it.durationSeconds > 0 }
            val first = set.slots.firstOrNull { it.durationSeconds > 0 }?.durationSeconds
            val summary = if (first != null) {
                "$count of 10 set · first ${TimerSetEditing.formatHms(first)}"
            } else "all blank"
            Text(summary, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDuplicate) { Text("Duplicate") }
                TextButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }
}
```

This removes the `"● active"` label and the `Edit`/`Send` buttons; the radio's
own `onClick` wins over the body's `clickable { onOpen() }` (same nested
pattern as `ComplicationCard` on Home), and `Duplicate`/`Delete` consume their
own taps.

- [ ] **Step 3: Add the new imports to `TimerSetsScreen.kt`**

```kotlin
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.RadioButton
```

(`Card`, `MaterialTheme`, `TextButton`, `dp` etc. are already imported.)

- [ ] **Step 4: Wire `sending` in `MainActivity.kt`**

In the `Screen.TimerSets -> TimerSetsScreen(` branch, after `activeId = viewModel.timerActiveId,` add:

```kotlin
            sending = state.sending,
```

- [ ] **Step 5: Gates**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest` (timeout 600000)
Expected: BUILD SUCCESSFUL, 216 tests, 0 failures.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: timer set cards adopt the complications design language

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

- [ ] **Step 7 (USER-side, not the implementer):** Device smoke — radio tap
sends and only moves on success; no-watch tap shows the snackbar and the radio
stays; radios grey out mid-send; body tap opens the editor; the active card is
filled + bordered; Duplicate/Delete behave as before.

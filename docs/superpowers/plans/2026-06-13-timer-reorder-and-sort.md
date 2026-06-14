# Timer Reorder & Sort Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let users sort a Timer Set's 10 slots by duration and manually reorder both the slots (in the editor) and the sets list (on the dashboard), all via ▲/▼ buttons.

**Architecture:** New pure, unit-tested logic (`TimerSetEditing.sortByTime`, a generic `Reorder` util, `TimerSetsRepository.reorder`) drives two `AppViewModel` actions and small Compose UI additions. Slot reordering is local editor state delivered to the watch through the existing Save/Save & send path; set-list reordering persists immediately by id.

**Tech Stack:** Kotlin Multiplatform (androidTarget only), Compose Multiplatform (material3), `multiplatform-settings`, `kotlin.test` + `kotlinx-coroutines-test` + `multiplatform-settings-test` (`MapSettings`).

**Spec:** `docs/superpowers/specs/2026-06-13-timer-reorder-and-sort-design.md`

**How tests run:** `./gradlew :app:testDebugUnitTest` (single class: append `--tests "FQN"`). UI-only tasks compile-check with `./gradlew :app:assembleDebug`.

---

## File Structure

| File | Change | Responsibility |
|------|--------|----------------|
| `app/src/commonMain/.../timer/TimerSetEditing.kt` | Modify | Add `sortByTime(slots)` pure transform. |
| `app/src/commonMain/.../timer/Reorder.kt` | Create | Generic `moveUp`/`moveDown` list helpers. |
| `app/src/commonMain/.../timer/TimerSetsRepository.kt` | Modify | Add `reorder(orderedIds)` persistence. |
| `app/src/commonMain/.../AppViewModel.kt` | Modify | Add `moveTimerSetUp/Down(set)` actions. |
| `app/src/commonMain/.../ui/TimerSetsScreen.kt` | Modify | Per-set ▲/▼ controls + callbacks. |
| `app/src/commonMain/.../ui/TimerSetEditScreen.kt` | Modify | "Sort by time" button + per-slot ▲/▼. |
| `app/src/androidMain/.../MainActivity.kt` | Modify | Wire dashboard move callbacks to the ViewModel. |
| `app/src/commonTest/.../timer/TimerSetEditingTest.kt` | Modify | `sortByTime` tests. |
| `app/src/commonTest/.../timer/ReorderTest.kt` | Create | `moveUp`/`moveDown` tests. |
| `app/src/commonTest/.../timer/TimerSetsRepositoryTest.kt` | Modify | `reorder` tests. |
| `app/src/commonTest/.../AppViewModelTest.kt` | Modify | `moveTimerSet*` tests. |

Package root: `com.blizzardcaron.freeolleefaces`. Path root: `app/src/<sourceset>/kotlin/com/blizzardcaron/freeolleefaces/`.

---

## Task 1: `sortByTime` slot transform

**Files:**
- Modify: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/timer/TimerSetEditing.kt`
- Test: `app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/timer/TimerSetEditingTest.kt`

- [ ] **Step 1: Write the failing tests**

Add these test methods inside the existing `TimerSetEditingTest` class (the class and its `slots()` helper already exist):

```kotlin
    @Test
    fun `sortByTime orders non-blank slots ascending and keeps labels with durations`() {
        val input = listOf(
            TimerSlot("a", 90), TimerSlot("b", 30), TimerSlot("c", 60),
        ) + List(7) { TimerSlot("z$it", 0) }
        val result = TimerSetEditing.sortByTime(input)
        assertEquals(listOf(30, 60, 90), result.take(3).map { it.durationSeconds })
        assertEquals(listOf("b", "c", "a"), result.take(3).map { it.label }) // labels travel
        assertEquals(10, result.size)
    }

    @Test
    fun `sortByTime pushes blank slots to the bottom`() {
        val input = listOf(
            TimerSlot("blank1", 0), TimerSlot("real", 45), TimerSlot("blank2", 0),
        ) + List(7) { TimerSlot("z$it", 0) }
        val result = TimerSetEditing.sortByTime(input)
        assertEquals(45, result[0].durationSeconds)                 // only non-blank first
        assertTrue(result.drop(1).all { it.durationSeconds == 0 })  // everything else blank
    }

    @Test
    fun `sortByTime is stable for equal durations`() {
        val input = listOf(
            TimerSlot("first", 60), TimerSlot("second", 60), TimerSlot("third", 60),
        ) + List(7) { TimerSlot("", 0) }
        val result = TimerSetEditing.sortByTime(input)
        assertEquals(listOf("first", "second", "third"), result.take(3).map { it.label })
    }

    @Test
    fun `sortByTime keeps blank slots in their original relative order`() {
        val input = listOf(
            TimerSlot("real", 60), TimerSlot("blankA", 0), TimerSlot("blankB", 0),
        ) + (0 until 7).map { TimerSlot("pad$it", 0) }
        val result = TimerSetEditing.sortByTime(input)
        assertEquals("blankA", result[1].label) // first blank stays ahead of second
        assertEquals("blankB", result[2].label)
    }

    @Test
    fun `sortByTime on all-blank input is unchanged`() {
        val input = (0 until 10).map { TimerSlot("L$it", 0) }
        assertEquals(input, TimerSetEditing.sortByTime(input))
    }

    @Test
    fun `sortByTime does not mutate the input list`() {
        val input = listOf(TimerSlot("a", 90), TimerSlot("b", 30)) + List(8) { TimerSlot("", 0) }
        val snapshot = input.toList()
        TimerSetEditing.sortByTime(input)
        assertEquals(snapshot, input)
    }
```

Add the import if missing (top of file): `import kotlin.test.assertTrue`.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.blizzardcaron.freeolleefaces.timer.TimerSetEditingTest"`
Expected: FAIL — compilation error, `sortByTime` unresolved.

- [ ] **Step 3: Implement `sortByTime`**

Add this function to the `TimerSetEditing` object in `TimerSetEditing.kt`:

```kotlin
    /**
     * Stable ascending sort of the slots by [TimerSlot.durationSeconds], with blank (0s) slots
     * pushed to the bottom. Labels travel with their durations; equal durations — and blanks
     * among themselves — keep their relative order. Returns a new list; input untouched.
     */
    fun sortByTime(slots: List<TimerSlot>): List<TimerSlot> {
        val (active, blank) = slots.partition { it.durationSeconds > 0 }
        return active.sortedBy { it.durationSeconds } + blank
    }
```

(`partition` and `sortedBy` are stable and non-mutating, satisfying the stability and no-mutation tests.)

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.blizzardcaron.freeolleefaces.timer.TimerSetEditingTest"`
Expected: PASS (all old + 6 new tests green).

- [ ] **Step 5: Commit**

```bash
git add app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/timer/TimerSetEditing.kt \
        app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/timer/TimerSetEditingTest.kt
git commit -m "feat: add TimerSetEditing.sortByTime (ascending, blanks to bottom)"
```

---

## Task 2: `Reorder` generic move helper

**Files:**
- Create: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/timer/Reorder.kt`
- Test: `app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/timer/ReorderTest.kt`

- [ ] **Step 1: Write the failing tests**

Create `ReorderTest.kt`:

```kotlin
package com.blizzardcaron.freeolleefaces.timer

import kotlin.test.Test
import kotlin.test.assertEquals

class ReorderTest {

    @Test fun moveUp_interior_swapsWithPrevious() {
        assertEquals(listOf("a", "c", "b", "d"), Reorder.moveUp(listOf("a", "b", "c", "d"), 2))
    }

    @Test fun moveUp_atTop_isNoOp() {
        assertEquals(listOf("a", "b", "c"), Reorder.moveUp(listOf("a", "b", "c"), 0))
    }

    @Test fun moveUp_negativeIndex_isNoOp() {
        assertEquals(listOf("a", "b"), Reorder.moveUp(listOf("a", "b"), -1))
    }

    @Test fun moveDown_interior_swapsWithNext() {
        assertEquals(listOf("a", "c", "b", "d"), Reorder.moveDown(listOf("a", "b", "c", "d"), 1))
    }

    @Test fun moveDown_atBottom_isNoOp() {
        assertEquals(listOf("a", "b", "c"), Reorder.moveDown(listOf("a", "b", "c"), 2))
    }

    @Test fun moveDown_indexBeyondEnd_isNoOp() {
        assertEquals(listOf("a", "b"), Reorder.moveDown(listOf("a", "b"), 5))
    }

    @Test fun move_onSingleElement_isNoOp() {
        assertEquals(listOf("x"), Reorder.moveUp(listOf("x"), 0))
        assertEquals(listOf("x"), Reorder.moveDown(listOf("x"), 0))
    }

    @Test fun move_onEmptyList_isNoOp() {
        assertEquals(emptyList(), Reorder.moveUp(emptyList<String>(), 0))
        assertEquals(emptyList(), Reorder.moveDown(emptyList<String>(), 0))
    }

    @Test fun moveUp_doesNotMutateInput() {
        val input = listOf("a", "b", "c")
        val snapshot = input.toList()
        Reorder.moveUp(input, 1)
        assertEquals(snapshot, input)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.blizzardcaron.freeolleefaces.timer.ReorderTest"`
Expected: FAIL — compilation error, `Reorder` unresolved.

- [ ] **Step 3: Implement `Reorder`**

Create `Reorder.kt`:

```kotlin
package com.blizzardcaron.freeolleefaces.timer

/** Pure list reordering — returns a new list; a no-op when the move is out of bounds. */
object Reorder {

    /** Swap the element at [index] with the one before it. No-op if [index] <= 0 or out of range. */
    fun <T> moveUp(list: List<T>, index: Int): List<T> = swap(list, index, index - 1)

    /** Swap the element at [index] with the one after it. No-op if [index] is last or out of range. */
    fun <T> moveDown(list: List<T>, index: Int): List<T> = swap(list, index, index + 1)

    private fun <T> swap(list: List<T>, a: Int, b: Int): List<T> {
        if (a !in list.indices || b !in list.indices) return list
        val out = list.toMutableList()
        out[a] = list[b]
        out[b] = list[a]
        return out
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.blizzardcaron.freeolleefaces.timer.ReorderTest"`
Expected: PASS (10 tests green).

- [ ] **Step 5: Commit**

```bash
git add app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/timer/Reorder.kt \
        app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/timer/ReorderTest.kt
git commit -m "feat: add generic Reorder.moveUp/moveDown list helper"
```

---

## Task 3: `TimerSetsRepository.reorder`

**Files:**
- Modify: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/timer/TimerSetsRepository.kt`
- Test: `app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/timer/TimerSetsRepositoryTest.kt`

- [ ] **Step 1: Write the failing tests**

Add these methods inside the existing `TimerSetsRepositoryTest` class (the `repo()` and `makeSet()` helpers already exist):

```kotlin
    // ---------------------------------------------------------------------------
    // reorder
    // ---------------------------------------------------------------------------

    @Test fun reorder_newOrder_persistsAndRoundtrips() {
        val r = repo()
        r.save(makeSet("a", "Alpha"))
        r.save(makeSet("b", "Beta"))
        r.save(makeSet("c", "Gamma"))
        r.reorder(listOf("c", "a", "b"))
        assertEquals(listOf("c", "a", "b"), r.getAll().map { it.id })
    }

    @Test fun reorder_idsAbsentFromOrder_areAppendedNotLost() {
        val r = repo()
        r.save(makeSet("a", "Alpha"))
        r.save(makeSet("b", "Beta"))
        r.save(makeSet("c", "Gamma"))
        r.reorder(listOf("c"))                       // only mention one id
        val ids = r.getAll().map { it.id }
        assertEquals("c", ids.first())               // requested id leads
        assertEquals(setOf("a", "b", "c"), ids.toSet()) // nothing lost
        assertEquals(3, ids.size)
    }

    @Test fun reorder_unknownIds_areIgnored() {
        val r = repo()
        r.save(makeSet("a", "Alpha"))
        r.save(makeSet("b", "Beta"))
        r.reorder(listOf("ghost", "b", "a"))
        assertEquals(listOf("b", "a"), r.getAll().map { it.id })
    }

    @Test fun reorder_preservesActiveId() {
        val r = repo()
        r.save(makeSet("a", "Alpha"))
        r.save(makeSet("b", "Beta"))
        r.setActive("a")
        r.reorder(listOf("b", "a"))
        assertEquals("a", r.activeId(), "reorder must not touch the active id")
    }

    @Test fun reorder_preservesSetContents() {
        val r = repo()
        val alpha = makeSet("a", "Alpha", durationSeconds = 200)
        r.save(alpha)
        r.save(makeSet("b", "Beta"))
        r.reorder(listOf("b", "a"))
        assertEquals(alpha, r.get("a"), "reorder must not alter set contents")
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.blizzardcaron.freeolleefaces.timer.TimerSetsRepositoryTest"`
Expected: FAIL — compilation error, `reorder` unresolved.

- [ ] **Step 3: Implement `reorder`**

Add this method to `TimerSetsRepository` (e.g. just after `save`):

```kotlin
    /**
     * Persist a new ordering of the existing sets. Sets are reordered by id following
     * [orderedIds]; any current set whose id is absent from [orderedIds] is appended in its
     * existing relative order (contents can never be lost). Unknown ids are ignored. The active
     * id is untouched.
     */
    fun reorder(orderedIds: List<String>) {
        val byId = getAll().associateBy { it.id }
        val ordered = orderedIds.mapNotNull { byId[it] }
        val remaining = byId.values.filter { it.id !in orderedIds }
        settings.putString(KEY_SETS, TimerSetsJson.encode(ordered + remaining))
    }
```

(`byId` from `associateBy` preserves insertion order, so `remaining` keeps the sets' existing relative order.)

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.blizzardcaron.freeolleefaces.timer.TimerSetsRepositoryTest"`
Expected: PASS (all old + 5 new tests green).

- [ ] **Step 5: Commit**

```bash
git add app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/timer/TimerSetsRepository.kt \
        app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/timer/TimerSetsRepositoryTest.kt
git commit -m "feat: add TimerSetsRepository.reorder(orderedIds)"
```

---

## Task 4: ViewModel `moveTimerSetUp` / `moveTimerSetDown`

**Files:**
- Modify: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/AppViewModel.kt`
- Test: `app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/AppViewModelTest.kt`

**Context:** `var timerSets` (line ~93) holds the dashboard list; `refreshTimers()` (line ~149) re-reads it from the repo. The existing `duplicateTimerSet`/`deleteTimerSet` (lines ~200–210) are the pattern to mirror: mutate repo, then `refreshTimers()`.

- [ ] **Step 1: Write the failing tests**

Add a small VM builder + tests to `AppViewModelTest`. The class already imports `MapSettings`, `Prefs`, `TimerSet`, `TimerSlot`, `TimerSetsRepository`, the fakes, and `runTest`/`testScheduler`. Add this helper and the tests inside the class:

```kotlin
    /** Minimal VM wired to a shared MapSettings, pre-seeded with [ids] as timer sets in order. */
    private fun vmWithSets(vararg ids: String): AppViewModel {
        val settings = MapSettings()
        val timerRepo = TimerSetsRepository(settings)
        ids.forEach { timerRepo.save(makeTimerSet(id = it, name = "Set $it")) }
        return AppViewModel(
            prefs = Prefs(settings),
            ble = FakeBleClient(mutableListOf()),
            steps = FakeStepsProvider(),
            location = FakeLocationProvider(),
            notificationAccess = FakeNotificationAccessChecker(),
            timerRepo = timerRepo,
            scheduler = FakeScheduler(mutableListOf()),
            alarmRepo = AlarmsRepository(MapSettings()),
            alarmScheduler = FakeAlarmScheduler(mutableListOf()),
        )
    }

    @Test
    fun moveTimerSetDown_reordersStateAndPersists() = runTest(testScheduler) {
        val vm = vmWithSets("a", "b", "c")
        val target = vm.timerSets.first { it.id == "a" }
        vm.moveTimerSetDown(target)
        assertEquals(listOf("b", "a", "c"), vm.timerSets.map { it.id }, "state reflects the move")
    }

    @Test
    fun moveTimerSetUp_reordersStateAndPersists() = runTest(testScheduler) {
        val vm = vmWithSets("a", "b", "c")
        val target = vm.timerSets.first { it.id == "c" }
        vm.moveTimerSetUp(target)
        assertEquals(listOf("a", "c", "b"), vm.timerSets.map { it.id })
    }

    @Test
    fun moveTimerSetUp_atTop_isNoOp() = runTest(testScheduler) {
        val vm = vmWithSets("a", "b", "c")
        vm.moveTimerSetUp(vm.timerSets.first { it.id == "a" })
        assertEquals(listOf("a", "b", "c"), vm.timerSets.map { it.id })
    }

    @Test
    fun moveTimerSetDown_atBottom_isNoOp() = runTest(testScheduler) {
        val vm = vmWithSets("a", "b", "c")
        vm.moveTimerSetDown(vm.timerSets.first { it.id == "c" })
        assertEquals(listOf("a", "b", "c"), vm.timerSets.map { it.id })
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.blizzardcaron.freeolleefaces.AppViewModelTest"`
Expected: FAIL — compilation error, `moveTimerSetDown`/`moveTimerSetUp` unresolved.

- [ ] **Step 3: Implement the actions**

Add `import com.blizzardcaron.freeolleefaces.timer.Reorder` near the other `timer.*` imports, then add these methods next to `duplicateTimerSet`/`deleteTimerSet`:

```kotlin
    fun moveTimerSetUp(set: TimerSet) {
        val index = timerSets.indexOfFirst { it.id == set.id }
        if (index < 0) return
        val newOrder = Reorder.moveUp(timerSets.map { it.id }, index)
        timerRepo.reorder(newOrder)
        refreshTimers()
    }

    fun moveTimerSetDown(set: TimerSet) {
        val index = timerSets.indexOfFirst { it.id == set.id }
        if (index < 0) return
        val newOrder = Reorder.moveDown(timerSets.map { it.id }, index)
        timerRepo.reorder(newOrder)
        refreshTimers()
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.blizzardcaron.freeolleefaces.AppViewModelTest"`
Expected: PASS (all old + 4 new tests green).

- [ ] **Step 5: Commit**

```bash
git add app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/AppViewModel.kt \
        app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/AppViewModelTest.kt
git commit -m "feat: add moveTimerSetUp/Down ViewModel actions"
```

---

## Task 5: Dashboard ▲/▼ reorder (`TimerSetsScreen` + `MainActivity`)

**Files:**
- Modify: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/TimerSetsScreen.kt`
- Modify: `app/src/androidMain/kotlin/com/blizzardcaron/freeolleefaces/MainActivity.kt:291-310`

No unit test (Compose UI; this project has no UI-test harness). Verified by compile + manual check. Do all edits, then build once, then commit.

- [ ] **Step 1: Add move callbacks to `TimerSetsScreen`'s signature**

In `TimerSetsScreen(...)`, add two parameters right after `onStart: (TimerSet) -> Unit,`:

```kotlin
    onStart: (TimerSet) -> Unit,
    onMoveUp: (TimerSet) -> Unit,
    onMoveDown: (TimerSet) -> Unit,
    onBack: () -> Unit,
```

- [ ] **Step 2: Pass index + move state into each row**

Replace the sets loop:

```kotlin
            for (set in sets) {
                TimerSetRow(
                    set = set,
                    active = set.id == activeId,
                    sending = sending,
                    onOpen = { onOpen(set) },
                    onDuplicate = { onDuplicate(set) },
                    onDelete = { onDelete(set) },
                    onSend = { onSend(set) },
                    onStart = { onStart(set) },
                )
            }
```

with:

```kotlin
            sets.forEachIndexed { index, set ->
                TimerSetRow(
                    set = set,
                    active = set.id == activeId,
                    sending = sending,
                    canMoveUp = index > 0,
                    canMoveDown = index < sets.lastIndex,
                    onOpen = { onOpen(set) },
                    onDuplicate = { onDuplicate(set) },
                    onDelete = { onDelete(set) },
                    onSend = { onSend(set) },
                    onStart = { onStart(set) },
                    onMoveUp = { onMoveUp(set) },
                    onMoveDown = { onMoveDown(set) },
                )
            }
```

- [ ] **Step 3: Add the ▲/▼ controls to `TimerSetRow`**

Update the `TimerSetRow` signature — add `canMoveUp`, `canMoveDown`, `onMoveUp`, `onMoveDown`:

```kotlin
@Composable
private fun TimerSetRow(
    set: TimerSet,
    active: Boolean,
    sending: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onOpen: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onSend: () -> Unit,
    onStart: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
```

Then replace the title row inside it:

```kotlin
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Radio = send: timerActiveId only updates on a successful send, so the radio
                // moves when the push lands and stays put on failure (snackbar covers errors).
                RadioButton(selected = active, onClick = onSend, enabled = !sending)
                Text(if (set.name.isBlank()) "(unnamed)" else set.name,
                    style = MaterialTheme.typography.titleMedium)
            }
```

with (radio + name on the left, ▲/▼ pinned right):

```kotlin
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    // Radio = send: timerActiveId only updates on a successful send, so the radio
                    // moves when the push lands and stays put on failure (snackbar covers errors).
                    RadioButton(selected = active, onClick = onSend, enabled = !sending)
                    Text(if (set.name.isBlank()) "(unnamed)" else set.name,
                        style = MaterialTheme.typography.titleMedium)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onMoveUp, enabled = canMoveUp) { Text("▲") }
                    TextButton(onClick = onMoveDown, enabled = canMoveDown) { Text("▼") }
                }
            }
```

(`Arrangement`, `Alignment`, `Modifier`, `TextButton`, `Text` are already imported in this file.)

- [ ] **Step 4: Wire the callbacks in `MainActivity`**

In the `Screen.TimerSets -> TimerSetsScreen(...)` call (around line 291), add right after `onStart = { viewModel.startTimerSet(it) },`:

```kotlin
            onStart = { viewModel.startTimerSet(it) },
            onMoveUp = { viewModel.moveTimerSetUp(it) },
            onMoveDown = { viewModel.moveTimerSetDown(it) },
            onBack = { viewModel.navigateTo(Screen.Home) },
```

- [ ] **Step 5: Compile**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL (no unresolved references; the new `TimerSetsScreen` params are all supplied by `MainActivity`).

- [ ] **Step 6: Manual verification**

Install (`./gradlew :app:installDebug` or adb) → open **Timer Sets**. With ≥2 sets: ▲ is disabled on the top set, ▼ disabled on the bottom; tapping ▼ on the top set swaps it down and the new order survives leaving and re-entering the screen (persisted). Tapping ▲/▼ must NOT open the set editor (buttons consume the tap; the card body still opens on tap elsewhere).

- [ ] **Step 7: Commit**

```bash
git add app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/TimerSetsScreen.kt \
        app/src/androidMain/kotlin/com/blizzardcaron/freeolleefaces/MainActivity.kt
git commit -m "feat: reorder timer sets on the dashboard with up/down controls"
```

---

## Task 6: Slot editor — "Sort by time" + per-slot ▲/▼ (`TimerSetEditScreen`)

**Files:**
- Modify: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/TimerSetEditScreen.kt`

No unit test (Compose UI). Self-contained: mutates the editor's local `working` state, so no ViewModel/MainActivity change. Save/Save & send already push `working.slots` in its current order.

- [ ] **Step 1: Add the `Reorder` import**

At the top of the file, with the other `com.blizzardcaron.freeolleefaces.timer.*` imports:

```kotlin
import com.blizzardcaron.freeolleefaces.timer.Reorder
```

- [ ] **Step 2: Add the "Sort by time" toolbar button**

Immediately after the `OutlinedTextField` for `working.name` (and before the slots `Column`), insert:

```kotlin
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { working = working.copy(slots = TimerSetEditing.sortByTime(working.slots)) },
            ) { Text("Sort by time") }
        }
```

(`Row`, `Arrangement`, `OutlinedButton`, `Text`, `Modifier`, `dp` are already imported.)

- [ ] **Step 3: Pass move callbacks into each `SlotEditor`**

In the `working.slots.forEachIndexed { index, slot -> SlotEditor(...) }` call, add three arguments after `onDuplicate = ...`:

```kotlin
                SlotEditor(
                    index = index,
                    slot = slot,
                    onLabelChange = { newLabel -> updateSlot(index) { s -> s.copy(label = newLabel) } },
                    onDurationChange = { secs -> updateSlot(index) { s -> s.copy(durationSeconds = secs) } },
                    onFillDown = { working = working.copy(slots = TimerSetEditing.fillDown(working.slots, index)) },
                    onDuplicate = { working = working.copy(slots = TimerSetEditing.duplicateToNext(working.slots, index)) },
                    canMoveUp = index > 0,
                    canMoveDown = index < working.slots.lastIndex,
                    onMoveUp = { working = working.copy(slots = Reorder.moveUp(working.slots, index)) },
                    onMoveDown = { working = working.copy(slots = Reorder.moveDown(working.slots, index)) },
                )
```

- [ ] **Step 4: Add ▲/▼ to the `SlotEditor` header**

Update the `SlotEditor` signature — add `canMoveUp`, `canMoveDown`, `onMoveUp`, `onMoveDown`:

```kotlin
@Composable
private fun SlotEditor(
    index: Int,
    slot: TimerSlot,
    onLabelChange: (String) -> Unit,
    onDurationChange: (Int) -> Unit,
    onFillDown: () -> Unit,
    onDuplicate: () -> Unit,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
```

Then replace the header row's trailing `Box { ... }` so ▲/▼ sit before the "..." menu. Replace:

```kotlin
                Text("Slot ${index + 1}", style = MaterialTheme.typography.titleSmall)
                Box {
                    TextButton(onClick = { menu = true }) { Text("...") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Fill down") },
                            onClick = { menu = false; onFillDown() })
                        DropdownMenuItem(text = { Text("Duplicate to next") },
                            onClick = { menu = false; onDuplicate() })
                    }
                }
```

with:

```kotlin
                Text("Slot ${index + 1}", style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onMoveUp, enabled = canMoveUp) { Text("▲") }
                    TextButton(onClick = onMoveDown, enabled = canMoveDown) { Text("▼") }
                    Box {
                        TextButton(onClick = { menu = true }) { Text("...") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Fill down") },
                                onClick = { menu = false; onFillDown() })
                            DropdownMenuItem(text = { Text("Duplicate to next") },
                                onClick = { menu = false; onDuplicate() })
                        }
                    }
                }
```

(`Row`, `Alignment` are already imported in this file.)

- [ ] **Step 5: Compile**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Manual verification**

Open a Timer Set in the editor. **Sort by time**: set a few slots to out-of-order durations plus some blanks, tap "Sort by time" → non-blank slots reorder ascending, blanks fall to the bottom, labels stay attached to their durations. **Manual**: ▲ disabled on Slot 1, ▼ disabled on Slot 10; ▼ on Slot 1 swaps slots 1↔2. Then **Save & send** → watch receives the reordered durations (confirm against the watch's interval sequence per the `0x26` path).

- [ ] **Step 7: Commit**

```bash
git add app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/TimerSetEditScreen.kt
git commit -m "feat: sort-by-time and per-slot reorder in the timer set editor"
```

---

## Task 7: Full-suite verification

- [ ] **Step 1: Run the whole unit-test suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL — all tests green (existing + Tasks 1–4 additions).

- [ ] **Step 2: Confirm a clean debug build**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3 (optional): update README**

If the README's Timer Sets section enumerates editor/dashboard actions, add a line for sort-by-time and reordering. Commit separately as `docs:` if changed.

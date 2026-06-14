# Design: Sortable & Reorderable Timers

**Date:** 2026-06-13
**Status:** Approved (design)
**Depends on:** Timer Sets (`2026-06-03-timer-sets-design.md`) — `TimerSet`/`TimerSlot`
models, `TimerSetsRepository`, `TimerSetsScreen`, `TimerSetEditScreen`,
`TimerSetEditing`, and the `0x26` send path (`OlleeProtocol.buildTimerPacket`).

## 1. Problem & goal

Today the 10 slots in a Timer Set sit in fixed creation order, and the library of
sets on the dashboard sits in JSON-array order. Neither can be rearranged. A user
building interval workouts wants slots in a sensible sequence (e.g. shortest →
longest), and a user with several sets wants to arrange the library to taste.

**Goal:** make both lists rearrangeable.

- **Slots inside a set** (`TimerSetEditScreen`): a **"Sort by time"** button orders
  the 10 slots by duration, plus per-slot **▲/▼** manual reordering. The reordered
  durations are what "Save & send" pushes to the watch.
- **Sets list on the dashboard** (`TimerSetsScreen`): per-set **▲/▼** manual
  reordering. **Manual only — no sort button.**

**Out of scope:** the sets-list order is phone-only and is **never** sent to the
watch (the watch only ever receives one set's 10 durations). Drag-and-drop is
explicitly rejected in favour of ▲/▼ buttons (no new dependency, identical on every
platform, pure-testable; the lists are only 10 items).

## 2. Decisions (locked during brainstorming)

| Question | Decision |
|----------|----------|
| What is reorderable? | **Both** slots-within-a-set and the sets list. |
| Manual reorder mechanism | **▲/▼ move buttons** (no drag-and-drop, no new dependency). |
| Slot "sort by time" behaviour | **Ascending**, blank (0s) slots **to the bottom**; labels travel with their durations. |
| Sets-list sort button | **None** — manual ▲/▼ reordering only. |
| Editor save semantics | **Unchanged.** Keep `Save` (persist) / `Save & send` (persist + push). Reordering mutates the working set; "Save & send" delivers the new order to the watch. |
| Set-order persistence | Reorder **by id** via a new `reorder(orderedIds)`; no schema change to `TimerSet`. |

## 3. Pure logic (commonMain — fully unit-testable, no UI/Android deps)

### 3.1 `TimerSetEditing.sortByTime`

```kotlin
/**
 * Stable ascending sort of the 10 slots by [TimerSlot.durationSeconds], with blank
 * (0s) slots pushed to the bottom. Labels travel with their durations. Equal
 * durations — and blanks among themselves — keep their relative order.
 */
fun sortByTime(slots: List<TimerSlot>): List<TimerSlot>
```

Implementation note: partition into non-blank (`durationSeconds > 0`) and blank,
`sortedBy { it.durationSeconds }` the non-blanks (Kotlin's `sortedBy` is stable),
then concatenate blanks unchanged. Returns a new list; input untouched.

### 3.2 `Reorder` (new file `timer/Reorder.kt`)

A tiny generic helper, used by both the slot list and the sets list:

```kotlin
/** Pure list reordering — returns a new list; no-op when the move is out of bounds. */
object Reorder {
    fun <T> moveUp(list: List<T>, index: Int): List<T>     // no-op if index <= 0
    fun <T> moveDown(list: List<T>, index: Int): List<T>   // no-op if index >= lastIndex
}
```

Swaps the element at `index` with its neighbour. Out-of-range indices return the list
unchanged.

## 4. Repository — persist set order

Add to `TimerSetsRepository`:

```kotlin
/**
 * Persist a new ordering of the existing sets. Sets are reordered by id following
 * [orderedIds]; any current set whose id is absent from [orderedIds] is appended
 * (in its existing relative order) so contents can never be lost. Unknown ids in
 * [orderedIds] are ignored. The active id is untouched.
 */
fun reorder(orderedIds: List<String>)
```

Reordering by id (rather than a `saveAll(list)` taking full objects) guarantees a UI
bug can only reshuffle, never corrupt set contents. Re-encodes via `TimerSetsJson`.

## 5. ViewModel — `AppViewModel`

Mirror the existing immediate-persist actions (`duplicateTimerSet`/`deleteTimerSet`):

```kotlin
fun moveTimerSetUp(set: TimerSet)    // reorder(ids with set swapped up),   refreshTimers()
fun moveTimerSetDown(set: TimerSet)  // reorder(ids with set swapped down), refreshTimers()
```

Each computes the current id order from `timerSets`, applies `Reorder.moveUp/Down` at
the set's index, calls `timerRepo.reorder(newOrder)`, then `refreshTimers()`. Persist
is immediate — the dashboard has no "Save" button, matching duplicate/delete.

Slot reordering needs **no ViewModel change**: it mutates the editor's local `working`
state and flows out through the existing `saveTimerSet` / `sendTimerSet` paths.

## 6. UI — dashboard (`TimerSetsScreen`)

- `TimerSetRow` gains **▲/▼** controls. ▲ is disabled on the first row, ▼ on the last.
- New row callbacks `onMoveUp` / `onMoveDown`, wired through `Callbacks` →
  `MainActivity` to `viewModel.moveTimerSetUp/Down`.
- Existing radio-as-send, Start/Duplicate/Delete row, and active highlighting unchanged.

## 7. UI — slot editor (`TimerSetEditScreen`)

- A small toolbar row above the slot list holds a **"Sort by time"** button:
  `working = working.copy(slots = TimerSetEditing.sortByTime(working.slots))`.
- Each `SlotEditor` card header gains **▲/▼** buttons next to the `Slot N` label
  (▲ disabled on slot 1, ▼ on slot 10):
  `working = working.copy(slots = Reorder.moveUp/moveDown(working.slots, index))`.
- The existing "..." menu (Fill down / Duplicate to next) is unchanged.
- **`Save` / `Save & send` are unchanged.** They push `working.slots` in its current
  order, so "Save & send" delivers the reordered durations to the watch via the
  existing `0x26` path.

## 8. Error handling

All reordering is local state + SharedPreferences; there are **no new BLE failure
modes**. Edge moves (▲ on first, ▼ on last) are no-ops. The watch receives durations
only on an explicit send (the existing `sendTimerSet`/`startTimerSet` path), now in
the new slot order.

## 9. Tests

- **`TimerSetEditingTest`** — `sortByTime`: ascending order; blanks pushed to bottom;
  stability for equal durations; stability among blanks; labels travel with durations;
  all-blank input unchanged; all-equal input unchanged; input list not mutated.
- **`ReorderTest`** (new) — `moveUp`/`moveDown`: interior swap; no-op at top/bottom;
  out-of-range index returns input; single-element and empty lists.
- **`TimerSetsRepositoryTest`** — `reorder`: new order persists and round-trips;
  ids absent from `orderedIds` are appended (no loss); unknown ids ignored; active id
  preserved across reorder.
- **`AppViewModelTest`** — `moveTimerSetUp`/`moveTimerSetDown` reshuffle `timerSets`
  state and persist; no-op at the ends.

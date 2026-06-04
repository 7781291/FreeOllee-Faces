# Design: Timer Sets

**Date:** 2026-06-03
**Status:** Approved (design)
**Depends on:** v0.8.0 chunked-write foundation (`OlleeProtocol.buildRawPacket`,
`OlleeBleClient.sendPacket` → `deliver` retry).

## 1. Problem & goal

The Ollee watch's **Timer face** holds **10 countdown/interval durations**, and the official
app exposes exactly those 10 slots — no more. The watch has real memory limits, but the phone
does not: it can store many sets of 10 and push whichever one the user wants active.

**Goal:** a standalone phone-side library of **up to 10 named Timer Sets**, each holding the
watch's 10 timer slots. The user edits sets freely on the phone and taps **Send to watch** to
push a set's 10 durations to the watch in one BLE write. Exactly one set may be marked
**active** (the one last pushed). This is "mostly on the Android side": the watch only ever
receives 10 durations.

This is **not** an auto-update face. Timers are static config, not time-varying data like
Temperature/Steps, so there is no periodic re-push — pushing is a manual, on-demand action
(mirroring the official app's "Send to watch" button).

## 2. Protocol (captured & verified 2026-06-03)

The Timer slots are written with command **`02 26`** (acknowledged at `0x46`). The official app
transmits the **entire 10-slot table** in one fragmented write — no deltas.

```
inner = 02 26 │ HH MM SS 00 │ d1 d2 … d10
                └─ header ──┘  └ 10 × little-endian uint32, seconds ┘
```

- Each `dN` is a **little-endian uint32 = duration in seconds**; a blank/unused slot is
  `00000000`.
- The 4-byte **header** `[00, MM, SS, 00]` observed in captures (`00 01 17 00`, `00 00 2D 00`)
  echoes the **last-edited timer's** minutes:seconds. It carries **no slot data** (the 10 words
  persist regardless — verified §9), but it is **not** pure scratch: on-device it seeds the Timer
  face's **default/primary countdown** (the timer shown before you scroll into the 10 slots). We
  seed it from **Slot 1's MM:SS** so the face comes up showing the first interval (§9 gate 1b). It
  has only a minutes and a seconds byte (no hour), so Slot 1 ≥ 1 h clamps the display seed.
- Standard framing `00 LEN AA55 CRChi CRClo …` (`LEN = inner.size + 4 = 0x32`), CRC-16/CCITT-FALSE
  over the inner bytes. The 50-byte frame fragments into 20+20+12-byte BLE writes and reassembles
  by `LEN` — handled by the existing chunked `writePacket`.
- **Per-slot labels never cross BLE.** The segment-LCD watch cannot render per-timer text; the
  official app keeps names ("Interval N") on the phone. Our labels are likewise phone-only.

Verification: two controlled edits decoded exactly — slot 1 `00:01:23` → `53 00 00 00` (83 s),
slot 2 `00:00:45` → `2D 00 00 00` (45 s); each unchanged slot resent verbatim.

## 3. Data model

```kotlin
data class TimerSlot(val label: String, val durationSeconds: Int)   // label may be ""; 0 = blank
data class TimerSet(val id: String, val name: String, val slots: List<TimerSlot>)  // slots.size == 10
```

- **Up to 10 sets**; each set **always has exactly 10 slots** (the watch's fixed count).
- `durationSeconds` ∈ `0..359_999` (caps at `99:59:59`; comfortably within LE-uint32).
- Exactly zero-or-one **active** set, tracked separately by id.

## 4. Persistence — `TimerSetsRepository`

Sets serialize to JSON via Android's built-in `org.json` (already on the test classpath via
`testImplementation(libs.org.json)`; no new production dependency). Stored in the existing
`Prefs` SharedPreferences:

- `timer_sets` → JSON array of sets.
- `active_timer_set_id` → the active set's id (or absent).

Repository API (pure over SharedPreferences):

```kotlin
fun getAll(): List<TimerSet>
fun get(id: String): TimerSet?
fun save(set: TimerSet)          // insert or replace; enforces ≤10 sets and slots.size==10
fun delete(id: String)           // also clears active if it was active
fun setActive(id: String)
fun activeId(): String?
```

Malformed/missing JSON degrades to an empty list (never throws to the UI).

## 5. BLE encoding — extend `OlleeProtocol`

Mirror the existing `buildWeekdayPacket`:

```kotlin
const val TARGET_TIMERS = 0x26

/** 10 durations (seconds) → a framed 02 26 packet. The 4-byte header [00, MM, SS, 00] seeds the
 *  face's default countdown from Slot 1 (minutes clamped to one byte); see §9 gate 1b. */
fun buildTimerPacket(durationsSeconds: List<Int>): ByteArray {
    require(durationsSeconds.size == 10) { "timer table needs exactly 10 slots" }
    require(durationsSeconds.all { it in 0..359_999 }) { "duration out of range" }
    val slot1 = durationsSeconds[0]
    val header = byteArrayOf(0, (slot1 / 60).coerceAtMost(0xFF).toByte(), (slot1 % 60).toByte(), 0)
    val payload = header +
        durationsSeconds.flatMap { s ->
            listOf(s, s ushr 8, s ushr 16, s ushr 24).map { (it and 0xFF).toByte() }
        }.toByteArray()
    return buildRawPacket(TARGET_TIMERS, payload)
}
```

Reuses `buildRawPacket` (framing + CRC + LEN guard) verbatim.

## 6. Editing transforms — `TimerSetEditing` (pure)

Quick-fill conveniences for the editor, kept pure so they're unit-testable without the UI:

```kotlin
/** Copy slots[fromIndex].durationSeconds into every slot below it (labels untouched). */
fun fillDown(slots: List<TimerSlot>, fromIndex: Int): List<TimerSlot>

/** Copy slots[index] (label + duration) into slot index+1; no-op if index == 9. */
fun duplicateToNext(slots: List<TimerSlot>, index: Int): List<TimerSlot>
```

`fillDown` is the fast path for interval mode: set slot 1 = `00:01:40`, fill down → all 10 =
`00:01:40`, then trim the tail (e.g. slot 7 = `00:00:00` "Interval stop"). Both always return a
list of size 10.

## 7. Push flow — reuse v0.8.0 plumbing

```kotlin
val packet = OlleeProtocol.buildTimerPacket(set.slots.map { it.durationSeconds })
OlleeBleClient(ctx).sendPacket(address, packet)   // → deliver() retry → chunked writePacket
    .onSuccess { repo.setActive(set.id); /* record status line */ }
    .onFailure { /* reuse WATCH_UNREACHABLE wake notification + Retry */ }
```

- The 50-byte frame fragments automatically; the existing `deliver` provides connect/write
  retry with backoff.
- On failure, reuse the existing `ErrorNotifier` **WATCH_UNREACHABLE** copy ("Long-press the
  ALARM button to wake the watch, then tap Retry").
- No WorkManager scheduling — manual push only.

## 8. UI

State-based nav (matches the existing `var screen by mutableStateOf<Screen>` + `when(screen)`
switch in `MainActivity`):

- Add `Screen.TimerSets` (list) and `Screen.TimerSetEdit(setId: String)` to the `Screen` sealed
  interface and the `when(screen)` switch.
- `HomeScreen` gains an `onOpenTimerSets` callback and a "Timer Sets" entry alongside Faces /
  Settings.

**List screen (`TimerSets`):**
- Up to 10 set rows: name, **active** badge, summary ("10 timers · 5 set").
- "New set" (disabled at 10 sets; seeds 10 blank slots).
- Per-row actions: Edit (tap), Duplicate set, Delete, **Send to watch**.

**Editor screen (`TimerSetEdit`):**
- Set-name field.
- 10 slot rows, each = label field + **HH:MM:SS** picker (0 allowed = blank).
- Per-row overflow: **Duplicate to next slot**, **Fill down** (§6).
- Save; **Send to watch** (pushes and marks active).

## 9. Error handling & validation gates

- Push failures reuse the v0.8.0 WATCH_UNREACHABLE wake notification + Retry.
- Repository never throws to the UI (malformed JSON → empty list; caps enforced on save).
- **On-device gate 1 — durations persist:** ✅ **PASS (2026-06-04).** Built a set of 10 × `00:01:40`
  (Slot 1 entered, then *Fill down*), selected the bonded Ollee Watch, tapped **Send** — succeeded
  first try ("Sent 'Set 1' to watch", row flipped to **● active**). User confirmed on the watch:
  all 10 timer slots read `1:40`.
- **On-device gate 1b — header / default timer:** ✅ **PASS (2026-06-04).** The zero header we
  originally sent left the Timer face's **default/primary countdown** (shown before scrolling into
  the 10 slots) at `00:00:00`. This revealed the 4-byte header is *not* pure scratch — it seeds that
  default timer. Fixed: `buildTimerPacket` now seeds the header from Slot 1's MM:SS (clamped to one
  minute-byte; the stored Slot 1 word stays full-precision). User confirmed after re-send: the
  face's default countdown now reads `1:40`. The 10 slot words were unaffected by the header in
  either case.
- **On-device gate 2 — face enabled:** ✅ the watch's Timer face was enabled, so the user could
  step through and read the 10 slots. (Enabling remains the user's responsibility — via the
  official app or a future faces-table write — and is documented, not automated.)
- **Known limitation — no read-back / no sync:** only the *write* (`02 26`→`46`) is decoded; no
  timer *read* command was ever captured. FreeOllee-Faces and the official Ollee app therefore do
  **not** reconcile — the watch's 10 slots are simply whatever app wrote last (last-writer-wins).
  Each app treats its own storage as the source of truth. See §10.

## 10. Out of scope (YAGNI)

- Reading current timers back from the watch (no read command captured — only the write).
- Auto-enabling the Timer face via the faces table (`02 36`).
- Reordering sets; import/export; a per-slot enable flag (a `0` duration already means blank).

## 11. Testing

- `OlleeProtocolTest`: byte-exact `buildTimerPacket` (against a recomputed CRC), validation
  rejects (size ≠ 10, negative, > max), and a round-trip that decodes the 10 LE words via
  `parseFrame` back to the input seconds.
- `TimerSetsRepositoryTest`: CRUD, active-id behavior (set/clear on delete), JSON round-trip,
  cap at 10 sets, slots always size 10, malformed-JSON → empty.
- `TimerSetEditingTest`: `fillDown` (mid-list, last index), `duplicateToNext` (mid-list, no-op
  at index 9), size invariants.

## 12. Components summary

| Unit | Responsibility | Depends on |
|------|----------------|------------|
| `TimerSlot` / `TimerSet` | immutable data model | — |
| `TimerSetsRepository` | persist/load sets + active id (JSON in Prefs) | `org.json`, `Prefs` |
| `OlleeProtocol.buildTimerPacket` | 10 durations → framed `02 26` packet | `buildRawPacket` |
| `TimerSetEditing` | pure fill-down / duplicate transforms | — |
| `TimerSets` / `TimerSetEdit` screens | list + editor UI | repo, editing, BLE client |
| push action | encode + `sendPacket` + active/notify | `OlleeBleClient`, `ErrorNotifier` |

# Temperature target toggle + in-app version display

**Date:** 2026-06-01
**Branch:** `experiment/temp-to-face-field` (continue here, then clean up before merge)
**Status:** Design — pending review

## Summary

Promote the confirmed `0x2E` write experiment into a real feature: a per-face toggle that
sends the Temperature face's outdoor value to **either the real Temperature face field
(`0x2E`) or the Name-tag/nameplate (`0x2F`)**. The default is the **real Temperature face**.
Also add an always-visible app version label for debugging across installed variants.

This replaces the experiment's blanket hardcode (which routed *every* push — including Sun —
to `0x2E`) with explicit, per-face target routing.

## Background (from on-device investigation, 2026-06-01)

- **`0x2E` is a write-and-hold display register.** It held a pushed value (`"  77 F"`)
  overnight and held `"  55 F"` unchanged for 4.5 min with all writers killed and two
  official-app syncs in between. The firmware does **not** reclaim it on a
  minutes-to-hours timescale.
- The watch's live onboard/body temperature (shown on the official app's TODAY screen as
  e.g. `35.1 °C` → `32.4 °C`) is a **separate** reading that never overwrote `0x2E`.
- The official app **only ever reads** `0x2E`; its single write during sync targets `0x23`
  (time/timezone). **No app-level command was found** that writes the onboard sensor back
  into `0x2E` on demand — the firmware populates it on its own slow/event-driven cycle
  (likely tied to a physical measurement on the watch, not triggerable over adb).
- The `0x2F` nameplate is the watch's **"Name tag"** (Device Settings → Name tag; "Hold the
  ALARM button on the Clock face to see your name tag"). It showed `"55 F"` during testing.
- The official app's **Settings → Temperature ("Enables temperature measurements")** toggle
  should stay **ON**: it drives the onboard sampling that the Nameplate mode's face still
  shows, and it has no adverse effect on Temperature-face mode (no reclaim observed).

## Goals

1. User can choose, per the Temperature face, whether outdoor weather is written to the
   **Temperature face (`0x2E`)** or the **Nameplate (`0x2F`)**. Default: Temperature face.
2. Switching back to Nameplate restores a real onboard reading to the face where possible
   (snapshot & restore), instead of leaving it frozen on the last outdoor value.
3. The app shows its version (and variant) on screen for debugging.
4. Sun and Custom faces are unaffected — they always use the Nameplate (`0x2F`).

## Non-goals

- Discovering/implementing a firmware "release/measure" command for `0x2E` (probe found
  none; out of scope).
- Changing the Sun or Custom face routing.
- Any change to the official Ollee app or the `ollee-graphene` capture harness.

## Design

### 1. Data model — `TempTarget`

New enum (in `auto/` alongside `ActiveFace`, or `prefs/`):

```kotlin
/** Where the Temperature face's outdoor value is written on the watch. */
enum class TempTarget(val bleTarget: Int) {
    TEMPERATURE_FACE(OlleeProtocol.TARGET_TEMPERATURE), // 0x2E — the real face
    NAMEPLATE(OlleeProtocol.TARGET_NAMEPLATE);          // 0x2F — the "name tag"

    companion object { val DEFAULT = TEMPERATURE_FACE }
}
```

`Prefs` additions (same string-enum + migration pattern as `activeFace`):

- `var tempTarget: TempTarget` — stored by `name`; absent/unknown → `TempTarget.DEFAULT`
  (`TEMPERATURE_FACE`). **Behavior change:** existing users previously got the nameplate;
  after upgrade the default flips to the Temperature face. Intentional, per design.
- `var savedSensorTemp: String?` — the snapshot of the firmware's `0x2E` value captured
  before our first-ever write, used to restore the onboard reading on switch → Nameplate.

### 2. Routing — kill the blanket hardcode

The experiment made the shared `pushIfWatch` always send `0x2E`. Replace with explicit
per-call targets:

- `MainActivity.pushIfWatch(payload, target = OlleeProtocol.TARGET_NAMEPLATE)` — add a
  `target` param defaulting to nameplate.
- Temperature callers (`refreshTemp`) pass `prefs.tempTarget.bleTarget`.
- Sun (`refreshSun`) and Custom keep the default (nameplate, `0x2F`).
- `AutoUpdateWorker.runTemperature` sends with `prefs.tempTarget.bleTarget` instead of the
  hardcoded `OlleeProtocol.TARGET_TEMPERATURE`.

### 3. Response parsing — `OlleeProtocol`

The snapshot read needs to extract the ASCII value from a `02 4E <value>` response frame.
Add a minimal parser (it does **not** exist yet):

```kotlin
/** Parses a framed response, returning the ASCII payload after the 0x02 <respTarget> header,
 *  or null if framing/CRC is invalid. Response target = request target + 0x20 (0x2E -> 0x4E). */
fun parseValue(frame: ByteArray): String?
```

It validates `AA 55`, the length byte, and CRC-16 over the inner bytes, then returns the
payload as the **raw** ASCII string (no trimming — the snapshot must be written back
verbatim, preserving leading spaces). Unit-tested against the real capture
`000CAA557F55024E202037372046` → `"  77 F"` and `000CAA55FC5D024E202035352046` → `"  55 F"`.

### 4. GATT read — `OlleeBleClient`

`OlleeBleClient` currently only writes. Add:

```kotlin
suspend fun read(deviceAddress: String, target: Int): Result<String>
```

Flow: connect → `discoverServices` → enable notifications on notify char
`6e400003-b5a3-f393-e0a9-e50e24dcca9e` (write the `0x2902` CCCD) → write the read request to
the write char `…0002` → first `onCharacteristicChanged` delivers the response → `parseValue`
→ resume. Reuses the existing connect/timeout/cleanup structure. Add `CHAR_NOTIFY_UUID`.

The read request is the empty-payload frame `00 06 AA 55 crcHi crcLo 02 <target>`, which the
existing `buildPacket(target, "")` already produces unchanged (a 0-length value passes the
length/ASCII validators) — it matches the captured `0006AA55BEC1022E` read of `0x2E`. No new
builder needed, though `OlleeBleClient.send`'s `value.padEnd(...)` must be bypassed on the
read path so the payload stays empty.

### 5. Snapshot & restore

- **Capture (once):** the first time we are about to write `0x2E` and `savedSensorTemp` is
  null, first `read(0x2E)` and store the result as `savedSensorTemp`, then proceed with the
  outdoor write. This captures the firmware's pristine ambient value before we overwrite it.
  (If the read fails, leave `savedSensorTemp` null and proceed; restore will be skipped.)
- **Restore:** when the user switches the toggle to **Nameplate**, if `savedSensorTemp` is
  non-null, write it to `0x2E` once so the face shows a real (if older) ambient reading
  instead of a frozen outdoor value; thereafter temperature routes to `0x2F`.
- The capture/restore lives in a shared helper so both the manual push and the background
  worker honor it. Caveat (documented): the snapshot is the value at first use and may be
  stale; the firmware refreshes `0x2E` only on its own slow/physical cycle.

### 6. UI

**Target selector** — in `ui/HomeScreen.kt` `TemperatureBody`, a second
`SingleChoiceSegmentedButtonRow` immediately below the existing °F/°C unit row and above the
"Every (minutes, min 15)" field:

```
[ °F | °C ]                              (existing)
Send temperature to:
[ Temperature face | Nameplate ]         (new; Temperature face selected by default)
[ Every (minutes, min 15) ]
```

Changing it: persist `prefs.tempTarget`, run the snapshot/restore edge logic, reschedule the
worker, and refresh the preview. Only rendered in `TemperatureBody`, so Sun/Custom are
unaffected.

**Version label** — a small, always-visible footer (e.g. bottom of the home `Column`,
`bodySmall`, muted) reading e.g. `v0.6.2 · com.blizzardcaron.freeolleefaces.exp`. Source the
strings at runtime via `PackageManager` (`packageManager.getPackageInfo(packageName, 0)
.versionName` and `applicationContext.packageName`) — no gradle/buildConfig change, and the
`applicationId` distinguishes the `.exp` variant from the real install.

### 7. Cleanup before merge (on `experiment/temp-to-face-field`)

- Remove the experiment-only `applicationIdSuffix = ".exp"` from the `debug` buildType in
  `app/build.gradle.kts`.
- Replace the experiment hardcode (commit `07b8b7b`) with the per-call target routing above.
- Keep the clean groundwork commits (`a34e098` parameterized target, `928eece` send
  overload, `81ee273` protocol doc).
- Fold the relevant findings from `docs/reference/temp-face-experiment.md` into
  `docs/reference/ollee-ble-protocol.md` (write-and-hold behavior; nameplate = name tag).

## Testing

- **Prefs:** `tempTarget` round-trips; absent → `TEMPERATURE_FACE`; unknown string → default.
  `savedSensorTemp` round-trips and clears.
- **TempTarget:** `bleTarget` maps `TEMPERATURE_FACE → 0x2E`, `NAMEPLATE → 0x2F`.
- **OlleeProtocol.parseValue:** real-capture frames decode to `"  77 F"` / `"  55 F"`;
  bad CRC / bad framing → null.
- **Routing (unit-level where feasible):** temperature push uses `prefs.tempTarget.bleTarget`;
  Sun/Custom always `0x2F`.
- **GATT read & snapshot/restore:** thin Android BLE layer — manually verified on-device
  (write distinctive value, switch to Nameplate, confirm face restores the snapshot).

## Known limitations

- `0x2E` is write-and-hold; the firmware won't restore a live sensor reading on any short
  cycle we can trigger. Snapshot-restore mitigates the "frozen outdoor value" but the
  restored value may be stale.
- Snapshot capture depends on a successful BLE read at first use; if the watch is unreachable
  then, restore is unavailable until a future successful capture.
- Requires the official app's **Temperature ("Enables temperature measurements")** setting to
  stay **ON** for the Nameplate mode's face to keep showing a live onboard reading.

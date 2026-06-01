# Experiment: outdoor temperature on the *real* Temperature face

**Goal:** make the watch's **Temperature face** display outdoor weather instead of (or alongside)
its onboard sensor — rather than the current nameplate (`0x2F`) workaround.

## What we know (from protocol capture)

See [`ollee-ble-protocol.md`](ollee-ble-protocol.md). The official app **reads** the temperature
at `02 2E` and the watch answers `02 4E "  54 F"`. It **never writes** a temperature value — the
face renders the onboard sensor in firmware. So the whole feature hinges on one untested question:

> **Does writing `02 2E <value>` override what the Temperature face shows?**

If yes → outdoor temp on the real face is possible. If no → the nameplate (`0x2F`) overlay is the
ceiling, and "outdoor temp face" stays a nameplate UX.

## What's already wired (committed)

- `OlleeProtocol.buildPacket(target, value)` + `TARGET_TEMPERATURE = 0x2E` + `formatTemperatureF()`
  (matches the watch read format `"  54 F"`). Unit-tested.
- `OlleeBleClient.send(address, value, target)` — pass `OlleeProtocol.TARGET_TEMPERATURE`.

Default behaviour is unchanged (everything still routes to the `0x2F` nameplate).

## The test (≈5 min, needs the watch) — ALREADY WIRED

Branch **`experiment/temp-to-face-field`** already routes the Temperature face's sends (both the
manual push in `MainActivity.pushIfWatch` and the background `AutoUpdateWorker.runTemperature`) to
`OlleeProtocol.TARGET_TEMPERATURE` (`0x2E`) instead of the `0x2F` nameplate. Sun/Custom are
unchanged. A debug APK is **already built** at `app/build/outputs/apk/debug/app-debug.apk`.

To run the test:
1. Install: `adb install -r app/build/outputs/apk/debug/app-debug.apk`
   (if it refuses on signature mismatch with your existing install, `adb uninstall
   com.blizzardcaron.freeolleefaces` first — you'll re-enter the watch address + coords).
2. Open the app with the watch connected, make sure **Temperature** is the active face, and tap
   **Update now** (or let the auto-update fire). The app fetches outdoor temp and now sends it to
   `0x2E`.
3. **Look at the watch's Temperature face.** Does it show the value the app sent (e.g. `"  72 F"`)?

### Decision tree
- **Temperature face shows the pushed value** → hypothesis confirmed. Promote to a real feature:
  add an `ActiveFace`/Prefs option "Temperature source: onboard sensor | outdoor weather", routing
  to `0x2E` when outdoor. (New FreeOllee-Faces spec.)
- **Face still shows the sensor / ignores it** → try, in order:
  1. payload formats: bare `"72"`, `"72F"`, `" 72 F"`, raw bytes instead of ASCII;
  2. a different write target near `0x2E` (`0x2D`, or the face ID `0x0B`);
  3. write `0x2E` then immediately read `0x2E`/`0x4E` to see if the value "stuck".
- **Nothing works** → the face is firmware-locked to the sensor; keep outdoor temp on the
  nameplate (`0x2F`) and optionally make that the documented "outdoor temp" UX.

## Capture while testing
Reinstall the `ollee-graphene` `CAPTURE=1` build of the *official* app is **not** needed here —
FreeOllee-Faces is our own code. But you can confirm the exact bytes leaving the phone with the
HCI snoop (`adb bugreport` → Wireshark, filter ATT writes to `6e400002-…`).

---

## RESULT (2026-06-01): DISCONFIRMED ❌

> **Correction.** An earlier note here claimed this was CONFIRMED. That was a **false
> positive**: it only verified that the *official app could read back* what we wrote to
> `0x2E` — it never verified the **physical watch face**. With the watch in hand, the face
> ignores `0x2E` entirely.

**Writing `0x2E` does NOT change what the Temperature face displays.** The face is
firmware-locked to the onboard sensor.

On-device test (watch `00:80:E1:26:DC:86`), two independent observations:

| Onboard measurements | Watch face shows | `0x2E` register reads | Match? |
|---|---|---|---|
| ON  | `84 °F` live, refreshing every few seconds | `"  55 F"` (≈13 °C) | ✗ |
| OFF*| `23 °C` (sensor cooling at rest)           | `"  55 F"` (≈13 °C) | ✗ |

\* the official app's "Enables temperature measurements" toggle did not reliably persist OFF
to the watch; the ON-state result alone is conclusive.

Decisive detail: pressing **ALARM on the Temperature face** toggled the display `84 °F ↔ 29 °C`
— a correct conversion of the face's **own** 84 °F value. If it were converting our injected
`0x2E` value (`55 °F`) it would have shown `13 °C`. It never did. A full register sweep found
**no** writable register holding the face's value; `0x2E` is the only ASCII temperature field
and the face does not render it.

### Conclusions
- **Outdoor temperature cannot be placed on the watch's real Temperature face** over BLE.
- The only surface for outdoor temp is the **`0x2F` nameplate / "Name tag"** (hold **ALARM on
  the Clock face** to view it) — which the shipping FreeOllee-Faces app already uses.
- The `experiment/temp-to-face-field` `0x2E` routing was reverted (it displayed nowhere the
  user sees and wrongly routed the Sun face too). See
  [`2026-06-01-temperature-target-toggle-design.md`](../superpowers/specs/2026-06-01-temperature-target-toggle-design.md)
  for the re-scoped outcome (findings + in-app version display).

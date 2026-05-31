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

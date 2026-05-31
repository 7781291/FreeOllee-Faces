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

## The test (≈5 min, needs the watch)

On branch `experiment/temp-to-face-field`, route the temperature send to `0x2E` and observe the
watch. The single change is in `auto/AutoUpdateWorker.kt` `runTemperature(...)` (and the manual
send in `MainActivity`): replace

```kotlin
OlleeBleClient(ctx).send(address, payload)
```
with
```kotlin
OlleeBleClient(ctx).send(address, payload, OlleeProtocol.TARGET_TEMPERATURE)
```

Then:
1. Build + install: `./gradlew :app:assembleDebug` → `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
2. In the app, trigger a temperature send (the normal flow / manual send).
3. **Look at the watch's Temperature face.** Compare with the value the app sent
   (e.g. it sent `"  72 F"`).

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

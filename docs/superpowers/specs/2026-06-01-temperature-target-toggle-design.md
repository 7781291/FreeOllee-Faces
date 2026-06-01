# Temperature-face override: findings + in-app version display

**Date:** 2026-06-01
**Branch:** `experiment/temp-to-face-field`
**Status:** Re-scoped after on-device disconfirmation

> **Note.** An earlier revision of this file specced a "Temperature target toggle" (route
> outdoor temp to the Temperature face `0x2E` vs the nameplate `0x2F`). On-device testing
> **disconfirmed** the premise — see Findings. The feature is infeasible and has been dropped.
> What remains shippable from this effort is the **in-app version display** plus documentation
> of the findings.

## Findings — the Temperature face cannot be overridden over BLE

The watch's Temperature face is **firmware-locked to the onboard sensor**. Writing the `0x2E`
register changes only what the *official app reads back*, never the physical face.

Evidence (watch `00:80:E1:26:DC:86`):

| Onboard measurements | Watch face shows | `0x2E` reads | Match? |
|---|---|---|---|
| ON  | `84 °F`, live, refreshing every few seconds | `"  55 F"` (≈13 °C) | ✗ |
| OFF | `23 °C` (sensor cooling at rest)             | `"  55 F"` (≈13 °C) | ✗ |

- Pressing **ALARM on the Temperature face** toggled `84 °F ↔ 29 °C` — a correct conversion of
  the face's *own* live value. Our injected `0x2E = 55 °F` would convert to `13 °C`; it never
  appeared.
- A full register sweep found no writable register holding the face's value. `0x2E` is the only
  ASCII temperature field and the face does not render it.
- The original "CONFIRMED" result was a false positive (read-back ≠ face). Corrected in
  [`../reference/temp-face-experiment.md`](../reference/temp-face-experiment.md).

**Consequence:** outdoor temperature can only be surfaced on the **`0x2F` nameplate / "Name
tag"** (hold **ALARM on the Clock face**), which the shipping app already does. No new
temperature-routing feature is warranted. The experiment's `0x2E` routing has been reverted.

## Shippable: in-app version display

**Goal:** show the app's version (and variant) on screen, so multiple installed builds are
distinguishable while debugging.

### Design

- A small, muted footer at the bottom of the Home screen `Column`, `bodySmall` style, e.g.
  `v0.6.2 · com.blizzardcaron.freeolleefaces`.
- Source strings at runtime via `PackageManager` — no gradle/`buildConfig` change:
  - version: `context.packageManager.getPackageInfo(context.packageName, 0).versionName`
  - variant: `context.packageName` (so any future `applicationIdSuffix` is visible).
- A tiny pure helper formats the label so it's unit-testable:
  `fun versionLabel(versionName: String?, packageName: String): String` →
  `"v${versionName ?: "?"} · $packageName"`.
- Render it once in `ui/HomeScreen.kt` near the bottom of the main content, below the
  per-face body, visible on every face.

### Testing

- Unit-test `versionLabel`: normal name, null name → `"v? · …"`.
- Manual: launch the app, confirm the footer shows the current `VERSION` (0.6.2) and package.

## Cleanup performed on this branch

- Reverted the `0x2E` routing in `MainActivity.pushIfWatch` and
  `AutoUpdateWorker.runTemperature` back to the nameplate default (matches `main` behavior).
- Removed the experiment-only `applicationIdSuffix = ".exp"` from `app/build.gradle.kts`.
- Uninstalled the `com.blizzardcaron.freeolleefaces.exp` app from the test phone.
- Kept harmless, tested groundwork: `OlleeProtocol.buildPacket(target, value)` +
  `TARGET_TEMPERATURE`/`TARGET_NAMEPLATE` constants, the `OlleeBleClient.send(target)`
  overload, and `sendAndReport`'s defaulted `target` param — all default to the nameplate.

## Reference doc update

`docs/reference/ollee-ble-protocol.md` gains a note: the Temperature face is firmware-locked to
the onboard sensor; `0x2E`/`0x4E` is a read-back ambient field that does **not** drive the
face; `0x2F` is the "Name tag" (ALARM on the Clock face).

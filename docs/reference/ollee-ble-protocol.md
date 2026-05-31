# Ollee Watch BLE Protocol (reverse-engineered)

Captured 2026-05-31 from the **official Ollee app** instrumented via the `ollee-graphene`
capture harness (a `CAPTURE=1` build that logs every `BluetoothGattCharacteristic` write/notify
as hex under logcat tag `OLLEE_BLE`). Raw log: [`ollee-capture-2026-05-31.log`](ollee-capture-2026-05-31.log).

## Transport
- Nordic UART service `6e400001-b5a3-f393-e0a9-e50e24dcca9e`
- Write (app→watch) char `6e400002-…` (RX) · Notify (watch→app) char `6e400003-…` (TX)

## Frame

```
00 │ LEN │ AA 55 │ CRC16_hi CRC16_lo │ CMD TARGET │ payload…
```

- `LEN = (CMD TARGET payload).length + 4`  (e.g. a bare read = 2-byte inner → `LEN = 0x06`)
- `CMD` is always `0x02` in everything observed.
- `CRC` = CRC-16/CCITT-FALSE (poly `0x1021`, init `0xFFFF`, no reflect/xorout) over the inner
  bytes `CMD TARGET payload`.
- Large messages fragment across BLE writes (~20 B each) and reassemble by `LEN`.

## Request/response pairing

A **read** is `02 <target>` with no payload; the watch answers on the notify characteristic
with the **same data at `target + 0x20`** (`2A→4A`, `2E→4E`, `37→57`, …). A **write** is
`02 <target> <payload>` and is acknowledged at `target + 0x20`.

## Command catalogue (observed)

| CMD·TARGET | Dir | Decoded payload | Meaning |
|------------|-----|-----------------|---------|
| `02 2A` → `4A` | R | `…DEADBEEF…01.05.0000.01.07…` | firmware / version string |
| `02 2B` → `4B` | R | `00010017007E0A05C0FF0FFF` | (unknown config block) |
| `02 2C` → `4C` | R | `00012800` | (unknown) |
| **`02 2E` → `4E`** | R | ASCII `"  54 F"` | **Temperature (onboard sensor), right-justified °F** |
| `02 2F` | W | ≤6 ASCII | **nameplate text** (the field FreeOllee-Faces writes) |
| `02 30` → `50` | R | `0000138800` | (unknown) |
| `02 32` → `52` | R | `0206 7182 0000 0078 FFFF 0000` | world-time config |
| `02 33` | W | `0206 7182 …` | write world-time config |
| `02 34` | W | `00007E90` + `"MOTUWETHFRSASU"` | write weekday-name strings |
| `02 35` → `55` | R | `00007E90` + `"MOTUWETHFRSASU"` | read weekday-name strings |
| `02 36` | W | face-record table (below) | **write enabled-faces config** |
| `02 37` → `57` | R | face-record table | read enabled-faces config |
| `02 39` → `59` | R | `002D` | (unknown) |
| `02 23` | W | `xxxxxxxx A0AB FFFF CC9C 0000` + `"He…"` | set clock / time |

## Faces table (`02 36` write / `02 37`→`57` read)

Header `04 00000000 00`, then **6-byte records** in slot (display) order:

```
ID │ 01 │ ENABLED │ 01 │ ?? │ SLOT
```

`ENABLED` (3rd byte) is `00`/`01`. Confirmed by toggling Temperature in the UI: exactly one
byte changed (the `ENABLED` byte of `ID 0x0B`). Mapping (Clock is the always-on base and has
no record):

| Slot | ID | Face | | Slot | ID | Face |
|------|----|----|----|------|----|----|
| 01 | `05` | Alarm | | 08 | `0E` | Flashlight |
| 02 | `07` | Stopwatch | | 09 | `0A` | Step Counter |
| 03 | `09` | Timer | | 0A | **`0B`** | **Temperature** |
| 04 | `11` | Sunrise/Sunset | | 0B | `0C` | Heart Rate |
| 05 | `06` | World Time | | 0C | `0F` | Game A (PING) |
| 06 | `0D` | Counter | | 0D | `10` | Game B (Blackjack) |
| 07 | `08` | Set Clock | | 0E | `12` | Game C (Poker) |

Cross-check: the disabled faces shown in the UI (Alarm, Stopwatch, Timer, Sunrise/Sunset,
World Time) are exactly the records with `ENABLED=0` (IDs `05,07,09,11,06`).

## Temperature face — can it show outdoor weather?

**Finding:** the app only ever **reads** temperature (`02 2E` → `024E "  54 F"`). No write to
a temperature target was observed; the face renders the onboard sensor in firmware.

**Open hypothesis to test (the goal):** does **writing** the temp field override what the face
shows? Candidate writes, in priority order:
1. `02 2E` + payload `"  72 F"` (same target as the read, ASCII like the read response).
2. `02 0B …` (the face's ID) or a temp-specific write target adjacent to `0x2E`.
3. Fallback (known-good): `02 2F` nameplate text — the current FreeOllee-Faces approach.

If (1)/(2) make the **Temperature face** display the pushed value, outdoor-temp-on-the-real-face
is achievable. If only (3) works, outdoor temp must remain a nameplate/text overlay.

> Test harness: `OlleeProtocol.buildPacket` is hard-wired to `02 2F`; an experimental build
> parameterizes `(cmd,target)` so the value can be sent to `0x2E`/`0x0B` and observed on-watch.

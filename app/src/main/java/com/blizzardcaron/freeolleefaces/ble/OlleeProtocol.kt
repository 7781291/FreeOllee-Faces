package com.blizzardcaron.freeolleefaces.ble

object OlleeProtocol {

    const val MAX_VALUE_LENGTH = 6

    /** BLE field selectors (the second inner byte, after cmd 0x02). */
    const val TARGET_NAMEPLATE = 0x2f
    // Experimental: the Temperature face's field. The official app *reads* it at 0x2E
    // (response 0x4E, e.g. "  54 F"); whether *writing* 0x2E overrides what the face shows
    // is the open question this parameterization exists to test.
    // See docs/reference/ollee-ble-protocol.md.
    const val TARGET_TEMPERATURE = 0x2e

    // The weekday table. The watch's upper-left letter pair (the only BLE-writable text in the
    // upper panel) renders the current day's 2-char slot from this 7-entry table. The official
    // app writes it at 0x34 behind a 4-byte 00 00 7E 90 prefix. Foundation for a future custom
    // 2-char always-on label; no UI/face uses it yet.
    const val TARGET_WEEKDAYS = 0x34
    private val WEEKDAY_PREFIX = byteArrayOf(0x00, 0x00, 0x7E, 0x90.toByte())

    /** Timer-face slots (10 countdown durations) — write target. Ack at 0x46. */
    const val TARGET_TIMERS = 0x26

    fun crc16(data: ByteArray): Int {
        var crc = 0xFFFF
        for (b in data) {
            crc = crc xor ((b.toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if ((crc and 0x8000) != 0)
                    (crc shl 1) xor 0x1021
                else
                    crc shl 1
                crc = crc and 0xFFFF
            }
        }
        return crc
    }

    /** Builds a nameplate (0x2F) packet — the original FreeOllee behaviour. */
    fun buildPacket(value: String): ByteArray = buildPacket(TARGET_NAMEPLATE, value)

    /**
     * Builds a framed packet writing [value] to an arbitrary [target] field.
     * Framing: 00, len, AA, 55, crcHi, crcLo, 0x02, target, value… (len = inner.size + 4),
     * CRC-16/CCITT-FALSE over the inner bytes.
     */
    fun buildPacket(target: Int, value: String): ByteArray {
        require(target in 0..0xFF) { "target must be a single byte (got $target)" }
        require(value.length <= MAX_VALUE_LENGTH) {
            "value must be <= $MAX_VALUE_LENGTH chars (got ${value.length})"
        }
        require(value.all { it.code in 0..127 }) {
            "value must be ASCII (got '$value')"
        }

        return buildRawPacket(target, value.toByteArray(Charsets.US_ASCII))
    }

    /**
     * Builds a framed packet writing arbitrary raw [payload] bytes to [target]. Unlike
     * [buildPacket] this imposes no ASCII or 6-char limit, so it can carry binary-prefixed
     * fields like the weekday table (0x34). Framing/CRC are identical.
     */
    fun buildRawPacket(target: Int, payload: ByteArray): ByteArray {
        require(target in 0..0xFF) { "target must be a single byte (got $target)" }

        val inner = byteArrayOf(0x02, target.toByte()) + payload
        // LEN is a single byte the firmware uses to reassemble fragments; guard against a payload
        // large enough to truncate it (no current caller approaches this).
        require(inner.size + 4 <= 0xFF) { "payload too large for single-byte LEN (inner ${inner.size})" }
        val crc = crc16(inner)

        return byteArrayOf(
            0x00,
            (inner.size + 4).toByte(),
            0xaa.toByte(),
            0x55,
            (crc shr 8).toByte(),
            (crc and 0xFF).toByte()
        ) + inner
    }

    /**
     * Builds the weekday-table write (0x34). [slots] must be 7 entries of exactly 2 ASCII chars,
     * in Mon..Sun order (captured default: `MO TU WE TH FR SA SU`). The firmware shows the slot
     * matching the current date in the upper-left letter pair. Pass all-identical slots (e.g.
     * `List(7){"TE"}`) to make the panel show a fixed 2-char label regardless of weekday.
     */
    fun buildWeekdayPacket(slots: List<String>): ByteArray {
        require(slots.size == 7) { "weekday table needs 7 slots (got ${slots.size})" }
        require(slots.all { it.length == 2 && it.all { c -> c.code in 0..127 } }) {
            "each slot must be exactly 2 ASCII chars (got $slots)"
        }
        val payload = WEEKDAY_PREFIX + slots.joinToString("").toByteArray(Charsets.US_ASCII)
        return buildRawPacket(TARGET_WEEKDAYS, payload)
    }

    /**
     * Builds the Timer-slots write (0x26). [durationsSeconds] must be exactly 10 entries, each a
     * countdown length in seconds (0 = blank slot). Emits a 4-byte header followed by ten
     * little-endian uint32 durations, then delegates to [buildRawPacket]. Per-slot labels are
     * phone-side only and never sent.
     *
     * The header is `[00, MM, SS, 00]` and seeds the Timer face's **default/primary countdown**
     * (the one shown before you scroll into the ten slots) — verified on-device: a zero header
     * left that timer at 00:00:00. We seed it from Slot 1's minutes:seconds so the face comes up
     * showing the first interval, ready to start (matching the official app, which parks the
     * last-edited timer there). The header carries no slot data — the ten words persist regardless.
     * It only has a minutes and a seconds byte (no hour), so Slot 1 durations ≥ 1 h are clamped to
     * MM:SS for the *display* seed only; the stored Slot 1 word stays full-precision.
     */
    fun buildTimerPacket(durationsSeconds: List<Int>): ByteArray {
        require(durationsSeconds.size == 10) {
            "timer table needs exactly 10 slots (got ${durationsSeconds.size})"
        }
        require(durationsSeconds.all { it in 0..359_999 }) {
            "each duration must be 0..359999 s (got $durationsSeconds)"
        }
        val payload = ByteArray(4 + 10 * 4) // 4-byte header + 10 LE-uint32 words
        // Seed the face's default countdown from Slot 1 (MM:SS; minutes clamped to one byte).
        val slot1 = durationsSeconds[0]
        payload[1] = (slot1 / 60).coerceAtMost(0xFF).toByte() // MM
        payload[2] = (slot1 % 60).toByte()                    // SS
        durationsSeconds.forEachIndexed { i, s ->
            val off = 4 + i * 4
            payload[off] = (s and 0xFF).toByte()
            payload[off + 1] = ((s shr 8) and 0xFF).toByte()
            payload[off + 2] = ((s shr 16) and 0xFF).toByte()
            payload[off + 3] = ((s shr 24) and 0xFF).toByte()
        }
        return buildRawPacket(TARGET_TIMERS, payload)
    }

    /** °F string matching the watch's read format ("  54 F"): right-justified to 6 chars. */
    fun formatTemperatureF(tempF: Int): String = "%4d F".format(tempF)

    /** A parsed frame. [crcOk] reports whether the CRC validated over the inner bytes. */
    data class Frame(val cmd: Int, val target: Int, val payload: ByteArray, val crcOk: Boolean)

    /**
     * Parses a complete framed message (00, len, AA, 55, crcHi, crcLo, cmd, target, payload…).
     * Returns null if it is too short or lacks the AA 55 magic. The CRC is checked over the inner
     * bytes (cmd, target, payload) and surfaced as [Frame.crcOk] rather than rejected, so callers
     * can inspect malformed/partial frames during reverse-engineering. Use to decode watch
     * responses, e.g. a temperature read-back at target 0x4E with payload "  54 F".
     */
    fun parseFrame(bytes: ByteArray): Frame? {
        if (bytes.size < 8) return null
        if (bytes[2] != 0xAA.toByte() || bytes[3] != 0x55.toByte()) return null
        val crc = ((bytes[4].toInt() and 0xFF) shl 8) or (bytes[5].toInt() and 0xFF)
        val inner = bytes.copyOfRange(6, bytes.size)
        val cmd = inner[0].toInt() and 0xFF
        val target = inner[1].toInt() and 0xFF
        val payload = inner.copyOfRange(2, inner.size)
        return Frame(cmd, target, payload, crc16(inner) == crc)
    }
}

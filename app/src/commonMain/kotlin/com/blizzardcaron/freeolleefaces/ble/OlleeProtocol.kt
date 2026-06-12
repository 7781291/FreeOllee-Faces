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

    /** Header byte 3 of the 0x26 timer write: configure-only, or start now in interval/single mode. */
    enum class TimerStartMode(val byte3: Int) { SAVE(0x00), START_INTERVAL(0x01), START_SINGLE(0x02) }

    /** Alarm-face record — write target. Ack at 0x45; the chime preview shares this format. */
    const val TARGET_ALARM = 0x25

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
     * The header is `[00, MM, SS, byte3]` where MM:SS seeds the Timer face's **Quick-timer**
     * (the standalone countdown shown before scrolling into the ten slots) from [headerSeconds] —
     * independent of the ten slot durations. A 2026-06-10 BLE capture confirmed the header MM:SS
     * is a separate "Quick timer" value, not derived from slot 1. Minutes are clamped to one byte
     * (0xFF max) when [headerSeconds] ≥ 256 minutes. [startMode] drives byte 3: SAVE (0x00)
     * persists the table without starting, START_INTERVAL (0x01) starts interval mode immediately,
     * START_SINGLE (0x02) starts a single countdown immediately.
     */
    fun buildTimerPacket(
        durationsSeconds: List<Int>,
        headerSeconds: Int,
        startMode: TimerStartMode = TimerStartMode.SAVE,
    ): ByteArray {
        require(durationsSeconds.size == 10) {
            "timer table needs exactly 10 slots (got ${durationsSeconds.size})"
        }
        require(durationsSeconds.all { it in 0..359_999 }) {
            "each duration must be 0..359999 s (got $durationsSeconds)"
        }
        require(headerSeconds >= 0) { "headerSeconds must be >= 0 (got $headerSeconds)" }
        val payload = ByteArray(4 + 10 * 4) // 4-byte header + 10 LE-uint32 words
        payload[1] = (headerSeconds / 60).coerceAtMost(0xFF).toByte() // MM (Quick-timer primary)
        payload[2] = (headerSeconds % 60).toByte()                    // SS
        payload[3] = startMode.byte3.toByte()                         // start/mode selector
        durationsSeconds.forEachIndexed { i, s ->
            val off = 4 + i * 4
            payload[off] = (s and 0xFF).toByte()
            payload[off + 1] = ((s shr 8) and 0xFF).toByte()
            payload[off + 2] = ((s shr 16) and 0xFF).toByte()
            payload[off + 3] = ((s shr 24) and 0xFF).toByte()
        }
        return buildRawPacket(TARGET_TIMERS, payload)
    }

    /**
     * Builds the alarm record (target 0x25). The watch stores one alarm — [hour] (0..23),
     * [minute] (0..59), and a [chimeIndex] tone selector (0x00 = Classic, 0x01 = Breeze,
     * 0x02 = Westminster, …). Set [playNow] to sound the chosen chime immediately ("Try chime"):
     * that is a transient preview the firmware does NOT persist (the play-now byte is absent from
     * the alarm read-back at 0x2B), so it never disturbs the stored alarm. [enabled] is the
     * alarm-on flag (byte 0).
     *
     * The record also carries the watch's **settings**, decoded 2026-06-11 by toggle-diffing the
     * official app's alarm screen (one "Send to watch" writes the whole record):
     *   [enable, hourlyChime, snoozeEnable, hour, minute, dayMask, chime, snoozeMin, playNow,
     *    hourMaskLo, hourMaskMid, hourMaskHi, FF]
     * - byte 1 [hourlyChime]: hourly-chime on/off. We default it ON — sending 0 here is how an
     *   earlier build kept silently disabling the watch's hourly chime on every push.
     * - byte 2: snooze enable; byte 7: snooze period in minutes (we keep the stock 5).
     * - byte 5: repeat-day mask, bit1=Mon..bit7=Sun, **1 = day active**, bit0 unused. We always
     *   send 0xFE (every day): the phone computes the true next fire and re-arms/disarms after
     *   each one. Sending 0x00 (NO active days) makes the watch show its Alarm setting as off and
     *   stay silent at the stored time — verified on hardware 2026-06-11, twice.
     * - bytes 9-11: 24-bit little-endian active-hours mask for the hourly chime; C0 FF 0F =
     *   bits 6-19 = 6:00-19:00, the stock range we preserve.
     * The final FF is a constant terminator (payload byte 12). The watch's 20-byte ATT payload
     * fragments the resulting 21-byte frame into [20][FF] — exactly how the official app sends it,
     * and how [BleClient] chunks it.
     */
    fun buildAlarmPacket(
        hour: Int,
        minute: Int,
        chimeIndex: Int,
        playNow: Boolean,
        enabled: Boolean = false,
        hourlyChime: Boolean = true,
    ): ByteArray {
        require(hour in 0..23) { "hour must be 0..23 (got $hour)" }
        require(minute in 0..59) { "minute must be 0..59 (got $minute)" }
        require(chimeIndex in 0..0xFF) { "chimeIndex must be a single byte (got $chimeIndex)" }
        val payload = byteArrayOf(
            if (enabled) 0x01 else 0x00,
            if (hourlyChime) 0x01 else 0x00,
            0x00,                       // snooze off
            hour.toByte(),
            minute.toByte(),
            0xFE.toByte(),              // repeat every day — see KDoc; 0x00 would disable
            chimeIndex.toByte(),
            0x05,                       // snooze period (minutes), stock value
            if (playNow) 0x01 else 0x00,
            0xC0.toByte(), 0xFF.toByte(), 0x0F, // hourly-chime hours 6:00-19:00
            0xFF.toByte(),              // terminator
        )
        return buildRawPacket(TARGET_ALARM, payload)
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

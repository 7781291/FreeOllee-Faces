package com.blizzardcaron.freeolleefaces.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OlleeProtocolTest {

    // CRC-16/CCITT-FALSE reference vector: ASCII "123456789" -> 0x29B1
    @Test
    fun `crc16 matches the CCITT-FALSE reference vector for 123456789`() {
        val input = "123456789".toByteArray(Charsets.US_ASCII)
        assertEquals(0x29B1, OlleeProtocol.crc16(input))
    }

    // The payload FreeOllee sends for a value like "Hello " is
    //   inner = 0x02 0x2f 'H' 'e' 'l' 'l' 'o' ' '
    // CRC-16/CCITT-FALSE of that byte sequence is 0xC0C2 (verified with
    // two independent computations: Python's binascii.crc_hqx(..., 0xFFFF)
    // and a hand-rolled CCITT-FALSE implementation).
    @Test
    fun `crc16 over the inner payload for value 'Hello ' is 0xC0C2`() {
        val inner = byteArrayOf(0x02, 0x2f) + "Hello ".toByteArray(Charsets.US_ASCII)
        assertEquals(0xC0C2, OlleeProtocol.crc16(inner))
    }

    // buildPacket must wrap a 6-char value with the exact framing
    // FreeOllee uses: [0x00, length, 0xaa, 0x55, crcHi, crcLo, 0x02, 0x2f, value...]
    // where length = inner.size + 4.
    @Test
    fun `buildPacket for 'Hello ' produces the exact framed bytes`() {
        val packet = OlleeProtocol.buildPacket("Hello ")

        // inner length is 8; length byte = 8 + 4 = 12 (0x0C).
        val expected = byteArrayOf(
            0x00,
            0x0C,
            0xaa.toByte(),
            0x55,
            0xC0.toByte(), 0xC2.toByte(), // CRC of inner
            0x02, 0x2f,                   // inner header
            0x48, 0x65, 0x6c, 0x6c, 0x6f, 0x20 // "Hello "
        )
        assertArrayEquals(expected, packet)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `buildPacket rejects values longer than 6 characters`() {
        OlleeProtocol.buildPacket("TooLong")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `buildPacket rejects non-ASCII characters`() {
        OlleeProtocol.buildPacket("café  ")
    }

    @Test
    fun `buildPacket accepts a value shorter than 6 chars without padding (caller's responsibility)`() {
        val packet = OlleeProtocol.buildPacket("Hi")
        // Length byte = inner.size + 4 = (2 + 2) + 4 = 8.
        assertEquals(8, packet[1].toInt() and 0xFF)
    }

    // --- arbitrary-target support (temperature-field experiment) ---

    @Test
    fun `default buildPacket still targets the nameplate 0x2F`() {
        val packet = OlleeProtocol.buildPacket("Hi")
        assertEquals(0x2f.toByte(), packet[7]) // inner header byte 2 = target
    }

    @Test
    fun `buildPacket can target the temperature field 0x2E with correct framing`() {
        val value = "  72 F"
        val packet = OlleeProtocol.buildPacket(OlleeProtocol.TARGET_TEMPERATURE, value)

        val inner = byteArrayOf(0x02, 0x2e) + value.toByteArray(Charsets.US_ASCII)
        val crc = OlleeProtocol.crc16(inner)
        val expected = byteArrayOf(
            0x00,
            (inner.size + 4).toByte(),
            0xaa.toByte(),
            0x55,
            (crc shr 8).toByte(),
            (crc and 0xFF).toByte()
        ) + inner

        assertArrayEquals(expected, packet)
        assertEquals(0x2e.toByte(), packet[7])
    }

    @Test(expected = IllegalArgumentException::class)
    fun `buildPacket rejects a target outside one byte`() {
        OlleeProtocol.buildPacket(0x123, "Hi")
    }

    @Test
    fun `formatTemperatureF matches the watch read format and stays 6 chars`() {
        assertEquals("  72 F", OlleeProtocol.formatTemperatureF(72))
        assertEquals(" 105 F", OlleeProtocol.formatTemperatureF(105))
        assertEquals(6, OlleeProtocol.formatTemperatureF(72).length)
        assertEquals(6, OlleeProtocol.formatTemperatureF(105).length)
    }

    // --- frame parsing ---

    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `parseFrame round-trips buildPacket`() {
        val packet = OlleeProtocol.buildPacket(OlleeProtocol.TARGET_TEMPERATURE, "  72 F")
        val f = OlleeProtocol.parseFrame(packet)!!
        assertEquals(0x02, f.cmd)
        assertEquals(0x2e, f.target)
        assertEquals("  72 F", String(f.payload, Charsets.US_ASCII))
        assertTrue(f.crcOk)
    }

    @Test
    fun `parseFrame decodes a real captured temperature read-back (target 0x4E)`() {
        // Captured from the watch: cmd 02, target 4E, payload "  54 F", CRC 0xCB6D.
        // crcOk == true validates our CRC-16 against the watch's actual bytes.
        val f = OlleeProtocol.parseFrame(hex("000CAA55CB6D024E202035342046"))!!
        assertEquals(0x02, f.cmd)
        assertEquals(0x4e, f.target)
        assertEquals("  54 F", String(f.payload, Charsets.US_ASCII))
        assertTrue(f.crcOk)
    }

    @Test
    fun `parseFrame returns null for too-short or wrong-magic input`() {
        assertNull(OlleeProtocol.parseFrame(byteArrayOf(0x00, 0x06)))
        assertNull(OlleeProtocol.parseFrame(hex("00060000022A"))) // no AA 55 magic
    }

    // --- raw / weekday-table packets (upper-left panel foundation) ---
    //
    // The watch's upper-left letter pair renders the current day's 2-char slot from a 7-entry
    // weekday table written at target 0x34, preceded by a 4-byte 00 00 7E 90 prefix. Captured
    // from the official app: inner = 02 34 | 00 00 7E 90 | "MOTUWETHFRSASU", CRC 0x7EAB.

    @Test
    fun `buildRawPacket reproduces the captured 0x34 weekday write byte-for-byte`() {
        val payload = hex("00007E90") + "MOTUWETHFRSASU".toByteArray(Charsets.US_ASCII)
        val packet = OlleeProtocol.buildRawPacket(0x34, payload)
        assertArrayEquals(hex("0018AA557EAB023400007E904D4F545557455448465253415355"), packet)
    }

    @Test
    fun `buildWeekdayPacket from the standard abbreviations matches the captured frame`() {
        val packet = OlleeProtocol.buildWeekdayPacket(
            listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU")
        )
        assertArrayEquals(hex("0018AA557EAB023400007E904D4F545557455448465253415355"), packet)
    }

    @Test
    fun `buildWeekdayPacket with all TE slots produces a valid frame the watch will accept`() {
        val packet = OlleeProtocol.buildWeekdayPacket(List(7) { "TE" })
        val f = OlleeProtocol.parseFrame(packet)!!
        assertEquals(0x34, f.target)
        assertTrue(f.crcOk)
        assertArrayEquals(hex("00007E90") + "TETETETETETETE".toByteArray(Charsets.US_ASCII), f.payload)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `buildWeekdayPacket rejects a table that is not 7 slots`() {
        OlleeProtocol.buildWeekdayPacket(listOf("MO", "TU"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `buildWeekdayPacket rejects a slot that is not exactly 2 chars`() {
        OlleeProtocol.buildWeekdayPacket(List(7) { "X" })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `buildWeekdayPacket rejects a non-ASCII slot`() {
        OlleeProtocol.buildWeekdayPacket(List(7) { "é2" }) // length 2 but non-ASCII
    }

    @Test(expected = IllegalArgumentException::class)
    fun `buildRawPacket rejects a target outside one byte`() {
        OlleeProtocol.buildRawPacket(0x123, byteArrayOf(0x00))
    }

    // --- Timer slots (0x26) ---

    @Test
    fun `buildTimerPacket with all-zero durations equals a zero-header raw 0x26 packet`() {
        val packet = OlleeProtocol.buildTimerPacket(List(10) { 0 })
        // 4-byte zero header + 10 * 4-byte zero words = 44 zero payload bytes.
        val expected = OlleeProtocol.buildRawPacket(OlleeProtocol.TARGET_TIMERS, ByteArray(44))
        assertArrayEquals(expected, packet)
    }

    @Test
    fun `buildTimerPacket encodes each slot as a little-endian uint32 of seconds`() {
        // slot 1 = 83 s (the captured 00:01:23); rest blank.
        val packet = OlleeProtocol.buildTimerPacket(listOf(83, 0, 0, 0, 0, 0, 0, 0, 0, 0))
        // Layout: [0..5] frame header, [6..7] = 02 26, [8..11] = 4-byte slot header,
        // [12..15] = slot-1 little-endian uint32.
        assertEquals(0x02.toByte(), packet[6])
        assertEquals(0x26.toByte(), packet[7])
        assertEquals(0x00.toByte(), packet[8])  // header byte 0
        assertEquals(0x53.toByte(), packet[12]) // 83 low byte
        assertEquals(0x00.toByte(), packet[13])
        assertEquals(0x00.toByte(), packet[14])
        assertEquals(0x00.toByte(), packet[15])
    }

    @Test
    fun `buildTimerPacket round-trips through parseFrame to target 0x26 with valid CRC`() {
        // slot 8 = 100_000 (0x000186A0) exercises bytes beyond the low 16 bits.
        val packet = OlleeProtocol.buildTimerPacket(listOf(83, 100, 100, 100, 100, 100, 0, 100_000, 900, 1800))
        val f = OlleeProtocol.parseFrame(packet)!!
        assertEquals(0x26, f.target)
        assertTrue(f.crcOk)
        // payload = 4-byte header + 10 LE uint32; full 4-byte decode of slot 8 (index 7).
        val base = 4 + 7 * 4
        val slot8 = (f.payload[base].toInt() and 0xFF) or
            ((f.payload[base + 1].toInt() and 0xFF) shl 8) or
            ((f.payload[base + 2].toInt() and 0xFF) shl 16) or
            ((f.payload[base + 3].toInt() and 0xFF) shl 24)
        assertEquals(100_000, slot8)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `buildTimerPacket rejects a list that is not exactly 10 slots`() {
        OlleeProtocol.buildTimerPacket(listOf(1, 2, 3))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `buildTimerPacket rejects an out-of-range duration`() {
        OlleeProtocol.buildTimerPacket(listOf(360_000, 0, 0, 0, 0, 0, 0, 0, 0, 0))
    }
}

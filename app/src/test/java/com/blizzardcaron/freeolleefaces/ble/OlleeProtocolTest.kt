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
}

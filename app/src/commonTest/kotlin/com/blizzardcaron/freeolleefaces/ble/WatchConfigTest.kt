package com.blizzardcaron.freeolleefaces.ble

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class WatchConfigTest {

    // Golden 0x52 payloads captured on-device 2026-06-18 (watch `panther`):
    //   OFF baseline: 00 04 71 82 00 00 00 78 FF FF 00 00 01 01  (auto-sleep off, period 120)
    //   ON:           00 04 71 C2 ...                            (byte 3 82->C2 = bit 6 set)
    private val OFF = byteArrayOf(0x00, 0x04, 0x71, 0x82.toByte(),
        0x00, 0x00, 0x00, 0x78, 0xFF.toByte(), 0xFF.toByte(), 0x00, 0x00, 0x01, 0x01)
    private val ON = byteArrayOf(0x00, 0x04, 0x71, 0xC2.toByte(),
        0x00, 0x00, 0x00, 0x78, 0xFF.toByte(), 0xFF.toByte(), 0x00, 0x00, 0x01, 0x01)

    private fun framed(payload: ByteArray): ByteArray {
        val inner = byteArrayOf(0x02, 0x52.toByte()) + payload
        val crc = OlleeProtocol.crc16(inner)
        return byteArrayOf(0x00, (inner.size + 4).toByte(), 0xAA.toByte(), 0x55,
            (crc shr 8).toByte(), (crc and 0xFF).toByte()) + inner
    }

    @Test
    fun `parseConfig reads autoSleep bit and period from the 0x52 reply`() {
        val on = OlleeProtocol.parseConfig(OlleeProtocol.parseFrame(framed(ON))!!)!!
        assertTrue(on.autoSleepOn)
        assertEquals(120, on.periodSec)
        val off = OlleeProtocol.parseConfig(OlleeProtocol.parseFrame(framed(OFF))!!)!!
        assertFalse(off.autoSleepOn)
        assertEquals(120, off.periodSec)
    }

    @Test
    fun `parseConfig rejects wrong target or short payload`() {
        val wrongTarget = OlleeProtocol.parseFrame(
            OlleeProtocol.buildRawPacket(0x4B, OFF)
        )!!
        assertNull(OlleeProtocol.parseConfig(wrongTarget))
        val short = OlleeProtocol.parseFrame(framed(byteArrayOf(0x00, 0x04, 0x71)))!!
        assertNull(OlleeProtocol.parseConfig(short))
    }

    @Test
    fun `withAutoSleep flips only the autoSleep bit and period, preserving other bytes`() {
        val cfg = OlleeProtocol.parseConfig(OlleeProtocol.parseFrame(framed(OFF))!!)!!
        val updated = cfg.withAutoSleep(on = true, periodSec = 60)
        assertTrue(updated.autoSleepOn)
        assertEquals(60, updated.periodSec)
        assertEquals(0xC2, updated.raw[3].toInt() and 0xFF)  // bit 6 set: 82 -> C2
        // mask bytes 0-2 and the trailer (8..13) untouched
        assertEquals(0x00, updated.raw[0].toInt() and 0xFF)
        assertEquals(0x04, updated.raw[1].toInt() and 0xFF)
        assertEquals(0x71, updated.raw[2].toInt() and 0xFF)
        assertContentEquals(OFF.copyOfRange(8, 14), updated.raw.copyOfRange(8, 14))
    }

    @Test
    fun `withAutoSleep off then buildConfigPacket round-trips through parseFrame`() {
        val cfg = OlleeProtocol.parseConfig(OlleeProtocol.parseFrame(framed(ON))!!)!!
        val packet = OlleeProtocol.buildConfigPacket(cfg.withAutoSleep(on = false, periodSec = 120))
        val reparsed = OlleeProtocol.parseFrame(packet)!!
        assertEquals(OlleeProtocol.TARGET_SET_CONFIG, reparsed.target)
        assertTrue(reparsed.crcOk)
        assertEquals(0x82, reparsed.payload[3].toInt() and 0xFF)  // bit 6 cleared: C2 -> 82
    }
}

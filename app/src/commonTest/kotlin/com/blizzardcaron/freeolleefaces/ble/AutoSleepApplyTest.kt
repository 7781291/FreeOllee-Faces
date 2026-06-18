package com.blizzardcaron.freeolleefaces.ble

import com.blizzardcaron.freeolleefaces.prefs.AutoSleepProfile
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AutoSleepApplyTest {

    /** Builds a framed 0x52 reply: 4-byte BE bitmask + 4-byte BE period + 6-byte trailer. */
    private fun reply(maskWord: Int, periodSec: Int): OlleeProtocol.Frame {
        fun be(v: Int) = byteArrayOf(
            ((v ushr 24) and 0xFF).toByte(), ((v ushr 16) and 0xFF).toByte(),
            ((v ushr 8) and 0xFF).toByte(), (v and 0xFF).toByte(),
        )
        val payload = be(maskWord) + be(periodSec) +
            byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0x00, 0x00, 0x01, 0x01)
        val inner = byteArrayOf(0x02, 0x52.toByte()) + payload
        val crc = OlleeProtocol.crc16(inner)
        val framed = byteArrayOf(0x00, (inner.size + 4).toByte(), 0xAA.toByte(), 0x55,
            (crc shr 8).toByte(), (crc and 0xFF).toByte()) + inner
        return OlleeProtocol.parseFrame(framed)!!
    }

    private class FakeBle(val readReply: Result<OlleeProtocol.Frame>) : BleClient {
        var written: ByteArray? = null
        override suspend fun send(deviceAddress: String, value: String) = Result.success(Unit)
        override suspend fun send(deviceAddress: String, value: String, target: Int) = Result.success(Unit)
        override suspend fun sendPacket(deviceAddress: String, packet: ByteArray): Result<Unit> {
            written = packet; return Result.success(Unit)
        }
        override suspend fun sendAndAwait(
            deviceAddress: String, requestPacket: ByteArray, expectedTarget: Int, timeoutMs: Long,
        ): Result<OlleeProtocol.Frame> = readReply
    }

    @Test fun alreadyMatching_noWrite() = runTest {
        // auto-sleep ON (bit 6 = 0x40) + period 120 already; desired same → no write
        val ble = FakeBle(Result.success(reply(0x40, 120)))
        val ok = AutoSleepApply.reconcile(ble, "AA", AutoSleepProfile(autoSleepOn = true, periodSec = 120))
        assertTrue(ok)
        assertNull(ble.written)
    }

    @Test fun differs_writesReadModifiedPacket_preservingOtherBits() = runTest {
        // current: other settings bits set (0x00047100), auto-sleep off, period 30; desired on @120
        val ble = FakeBle(Result.success(reply(0x00047100, 30)))
        val ok = AutoSleepApply.reconcile(ble, "AA", AutoSleepProfile(autoSleepOn = true, periodSec = 120))
        assertTrue(ok)
        val written = OlleeProtocol.parseFrame(ble.written!!)!!
        assertEquals(OlleeProtocol.TARGET_SET_CONFIG, written.target)
        assertEquals(0x04, written.payload[1].toInt() and 0xFF) // other mask bytes preserved
        assertEquals(0x71, written.payload[2].toInt() and 0xFF)
        assertEquals(0x40, written.payload[3].toInt() and 0xFF) // bit 6 set
        assertEquals(120, written.payload[7].toInt() and 0xFF)  // period BE low byte
    }

    @Test fun readFails_abortsNoWrite() = runTest {
        val ble = FakeBle(Result.failure(RuntimeException("timeout")))
        val ok = AutoSleepApply.reconcile(ble, "AA", AutoSleepProfile(autoSleepOn = true, periodSec = 120))
        assertFalse(ok)
        assertNull(ble.written)
    }
}

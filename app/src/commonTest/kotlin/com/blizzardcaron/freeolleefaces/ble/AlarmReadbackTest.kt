package com.blizzardcaron.freeolleefaces.ble

import com.blizzardcaron.freeolleefaces.fakes.FakeBleClient
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AlarmReadbackTest {
    private fun reply(enable: Int, h: Int, m: Int, ch: Int) = Result.success(
        OlleeProtocol.Frame(0x02, 0x4B, byteArrayOf(enable.toByte(), 0, 0, h.toByte(), m.toByte(), 0xFE.toByte(), ch.toByte()), true),
    )
    private val packet = byteArrayOf(0x00, 0x01) // opaque to the orchestrator

    @Test fun confirmed_on_first_read_does_not_resend() = runTest {
        val ble = FakeBleClient().apply { awaitResults.addLast(reply(1, 13, 30, 1)) }
        assertTrue(AlarmReadback.confirm(ble, "AA", packet, enabled = true, hour = 13, minute = 30, chimeIndex = 1))
        assertEquals(0, ble.sentPackets.count { it.contentEquals(packet) }) // no heal re-send
    }

    @Test fun heals_once_then_confirms() = runTest {
        val ble = FakeBleClient().apply {
            awaitResults.addLast(reply(1, 9, 0, 1))   // first read: wrong (9:00)
            awaitResults.addLast(reply(1, 13, 30, 1)) // after heal: correct
        }
        assertTrue(AlarmReadback.confirm(ble, "AA", packet, enabled = true, hour = 13, minute = 30, chimeIndex = 1))
        assertEquals(1, ble.sentPackets.count { it.contentEquals(packet) }) // exactly one heal re-send
    }

    @Test fun stays_false_after_one_failed_heal() = runTest {
        val ble = FakeBleClient().apply {
            awaitResults.addLast(reply(1, 9, 0, 1))
            awaitResults.addLast(reply(1, 9, 0, 1))
        }
        assertFalse(AlarmReadback.confirm(ble, "AA", packet, enabled = true, hour = 13, minute = 30, chimeIndex = 1))
        assertEquals(1, ble.sentPackets.count { it.contentEquals(packet) }) // healed exactly once, no loop
    }

    @Test fun read_failure_is_treated_as_mismatch() = runTest {
        val ble = FakeBleClient() // awaitResult defaults to failure
        assertFalse(AlarmReadback.confirm(ble, "AA", packet, enabled = true, hour = 13, minute = 30, chimeIndex = 1))
    }
}

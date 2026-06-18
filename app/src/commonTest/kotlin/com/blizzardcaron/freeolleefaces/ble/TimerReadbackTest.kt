package com.blizzardcaron.freeolleefaces.ble

import com.blizzardcaron.freeolleefaces.fakes.FakeBleClient
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TimerReadbackTest {
    private val packet = OlleeProtocol.buildTimerPacket(
        listOf(120, 300, 600, 0, 0, 0, 0, 0, 0, 0),
        headerSeconds = 30,
        startMode = OlleeProtocol.TimerStartMode.START_INTERVAL,
    )

    @Test fun confirms_on_matching_reply() = runTest {
        // INTERVAL → active value = slot0 = 0:02:00, running flag 0x02.
        val ble = FakeBleClient().apply {
            awaitResult = Result.success(OlleeProtocol.Frame(0x02, 0x4C, byteArrayOf(0x00, 0x02, 0x00, 0x02), true))
        }
        assertTrue(TimerReadback.confirm(ble, "AA", packet))
        assertEquals(0, ble.sentPackets.size)   // advisory read only — never re-sends
    }

    @Test fun false_on_mismatching_reply() = runTest {
        val ble = FakeBleClient().apply {
            awaitResult = Result.success(OlleeProtocol.Frame(0x02, 0x4C, byteArrayOf(0x00, 0x02, 0x00, 0x00), true))
        }
        assertFalse(TimerReadback.confirm(ble, "AA", packet))
        assertEquals(0, ble.sentPackets.size)
    }

    @Test fun false_on_read_failure() = runTest {
        val ble = FakeBleClient()   // awaitResult defaults to failure
        assertFalse(TimerReadback.confirm(ble, "AA", packet))
        assertEquals(0, ble.sentPackets.size)
    }
}

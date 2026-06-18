package com.blizzardcaron.freeolleefaces.ble

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Fixtures are real captures from watch `panther` (2026-06-17): a 10-slot set with slot0 = 120s
 * (2:00) and a quick-timer header of 30s, pushed in each mode, with the observed 0x4C reply.
 */
class TimerConfirmTest {

    private val durations = listOf(120, 300, 600, 0, 0, 0, 0, 0, 0, 0)

    private fun write(mode: OlleeProtocol.TimerStartMode) =
        OlleeProtocol.buildTimerPacket(durations, headerSeconds = 30, startMode = mode)

    private fun reply(b0: Int, b1: Int, b2: Int, b3: Int, crcOk: Boolean = true) =
        OlleeProtocol.Frame(0x02, 0x4C, byteArrayOf(b0.toByte(), b1.toByte(), b2.toByte(), b3.toByte()), crcOk)

    @Test fun save_confirms_header_value_and_idle_flag() {
        // SAVE → active value = header 0:00:30, runFlag = 0x00. Captured 4c: 00 00 1e 00.
        assertTrue(TimerConfirm.matches(write(OlleeProtocol.TimerStartMode.SAVE), reply(0x00, 0x00, 0x1e, 0x00)))
    }

    @Test fun single_confirms_header_value_and_running_flag() {
        // START_SINGLE → active value = header 0:00:30, runFlag = 0x02. Captured 4c: 00 00 1e 02.
        assertTrue(TimerConfirm.matches(write(OlleeProtocol.TimerStartMode.START_SINGLE), reply(0x00, 0x00, 0x1e, 0x02)))
    }

    @Test fun interval_confirms_slot0_value_and_running_flag() {
        // START_INTERVAL → active value = slot0 = 120s = 0:02:00, runFlag = 0x02. Captured 4c: 00 02 00 02.
        assertTrue(TimerConfirm.matches(write(OlleeProtocol.TimerStartMode.START_INTERVAL), reply(0x00, 0x02, 0x00, 0x02)))
    }

    @Test fun mismatch_when_run_flag_wrong() {
        // INTERVAL expects running (0x02); a saved/idle reply (0x00) must fail.
        assertFalse(TimerConfirm.matches(write(OlleeProtocol.TimerStartMode.START_INTERVAL), reply(0x00, 0x02, 0x00, 0x00)))
    }

    @Test fun mismatch_when_save_reads_back_running() {
        assertFalse(TimerConfirm.matches(write(OlleeProtocol.TimerStartMode.SAVE), reply(0x00, 0x00, 0x1e, 0x02)))
    }

    @Test fun mismatch_when_active_value_differs() {
        // Interval slot0 is 2:00; a 3:00 read-back must fail.
        assertFalse(TimerConfirm.matches(write(OlleeProtocol.TimerStartMode.START_INTERVAL), reply(0x00, 0x03, 0x00, 0x02)))
    }

    @Test fun bad_crc_never_matches() {
        assertFalse(TimerConfirm.matches(write(OlleeProtocol.TimerStartMode.SAVE), reply(0x00, 0x00, 0x1e, 0x00, crcOk = false)))
    }

    @Test fun wrong_target_never_matches() {
        val f = OlleeProtocol.Frame(0x02, 0x4B, byteArrayOf(0x00, 0x00, 0x1e, 0x00), true)
        assertFalse(TimerConfirm.matches(write(OlleeProtocol.TimerStartMode.SAVE), f))
    }

    @Test fun short_reply_never_matches() {
        val f = OlleeProtocol.Frame(0x02, 0x4C, byteArrayOf(0x00, 0x00), true)
        assertFalse(TimerConfirm.matches(write(OlleeProtocol.TimerStartMode.SAVE), f))
    }
}

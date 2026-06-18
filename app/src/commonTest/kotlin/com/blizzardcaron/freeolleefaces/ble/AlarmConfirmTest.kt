package com.blizzardcaron.freeolleefaces.ble

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AlarmConfirmTest {

    // Parsed 0x4B record: enabled, 13:30, chime 0x01.
    private fun frame(payload: ByteArray, crcOk: Boolean = true) =
        OlleeProtocol.Frame(cmd = 0x02, target = 0x4B, payload = payload, crcOk = crcOk)

    private val armed1330 = byteArrayOf(
        0x01, 0x00, 0x00, 0x0D, 0x1E, 0xFE.toByte(), 0x01, 0x05,
        0xC0.toByte(), 0xFF.toByte(), 0x0F, 0xFF.toByte(),
    )

    @Test fun matches_when_hour_minute_chime_and_enable_agree() {
        assertTrue(AlarmConfirm.matches(enabled = true, hour = 13, minute = 30, chimeIndex = 1, frame = frame(armed1330)))
    }

    @Test fun mismatch_on_minute() {
        assertFalse(AlarmConfirm.matches(enabled = true, hour = 13, minute = 31, chimeIndex = 1, frame = frame(armed1330)))
    }

    @Test fun mismatch_on_chime() {
        assertFalse(AlarmConfirm.matches(enabled = true, hour = 13, minute = 30, chimeIndex = 2, frame = frame(armed1330)))
    }

    @Test fun disarm_checks_only_enable_byte() {
        val disarmed = armed1330.copyOf().also { it[0] = 0x00 }
        // When disarming, hour/minute/chime are don't-cares — only the enable byte must be 0.
        assertTrue(AlarmConfirm.matches(enabled = false, hour = 0, minute = 0, chimeIndex = 0, frame = frame(disarmed)))
        assertFalse(AlarmConfirm.matches(enabled = false, hour = 0, minute = 0, chimeIndex = 0, frame = frame(armed1330)))
    }

    @Test fun bad_crc_never_matches() {
        assertFalse(AlarmConfirm.matches(enabled = true, hour = 13, minute = 30, chimeIndex = 1, frame = frame(armed1330, crcOk = false)))
    }

    @Test fun short_payload_never_matches() {
        assertFalse(AlarmConfirm.matches(enabled = true, hour = 13, minute = 30, chimeIndex = 1, frame = frame(byteArrayOf(0x01, 0x00))))
    }

    @Test fun readRequest_builds_02_2B_with_no_payload() {
        val req = OlleeProtocol.readRequest(OlleeProtocol.TARGET_GET_ALARM)
        // 00 06 AA 55 crcHi crcLo 02 2B
        assertTrue(req.size == 8 && req[6] == 0x02.toByte() && req[7] == 0x2B.toByte())
    }
}

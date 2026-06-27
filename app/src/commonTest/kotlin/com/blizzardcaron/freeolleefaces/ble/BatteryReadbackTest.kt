package com.blizzardcaron.freeolleefaces.ble

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BatteryReadbackTest {
    @Test fun parsesBigEndianMilliVolts() {
        // 0x0B22 = 2850 mV at the documented offset.
        val payload = ByteArray(BatteryReadback.VOLTAGE_OFFSET + 2)
        payload[BatteryReadback.VOLTAGE_OFFSET] = 0x0B
        payload[BatteryReadback.VOLTAGE_OFFSET + 1] = 0x22
        assertEquals(2850, BatteryReadback.parseMilliVolts(payload))
    }

    @Test fun shortPayloadIsNull() {
        assertNull(BatteryReadback.parseMilliVolts(ByteArray(BatteryReadback.VOLTAGE_OFFSET)))
        assertNull(BatteryReadback.parseMilliVolts(ByteArray(0)))
    }

    @Test fun parsesRealCapturedVersionReply() {
        // Real 0x4A payload captured from watch 00:80:E1:26:DC:86 (fw 00.01.07); the official app's
        // Version Info showed "2.843 V". The trailing big-endian uint16 (00 00 0B 1B) is 0x0B1B = 2843 mV.
        val payload = "DEADBEEF01.05.0000.01.07DEADBEEF".encodeToByteArray() +
            byteArrayOf(0x00, 0x00, 0x0B, 0x1B)
        assertEquals(2843, BatteryReadback.parseMilliVolts(payload))
    }
}

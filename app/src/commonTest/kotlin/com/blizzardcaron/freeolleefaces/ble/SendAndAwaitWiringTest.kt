package com.blizzardcaron.freeolleefaces.ble

import com.blizzardcaron.freeolleefaces.fakes.FakeBleClient
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

class SendAndAwaitWiringTest {
    @Test fun fake_reply_confirms_via_AlarmConfirm() = runTest {
        val fake = FakeBleClient().apply {
            awaitResult = Result.success(
                OlleeProtocol.Frame(
                    cmd = 0x02, target = 0x4B, crcOk = true,
                    payload = byteArrayOf(0x01, 0x00, 0x00, 0x0D, 0x1E, 0xFE.toByte(), 0x01),
                ),
            )
        }
        val reply = fake.sendAndAwait("AA:BB", OlleeProtocol.readRequest(OlleeProtocol.TARGET_GET_ALARM), 0x4B)
        assertTrue(reply.isSuccess)
        assertTrue(AlarmConfirm.matches(true, 13, 30, 1, reply.getOrThrow()))
    }
}

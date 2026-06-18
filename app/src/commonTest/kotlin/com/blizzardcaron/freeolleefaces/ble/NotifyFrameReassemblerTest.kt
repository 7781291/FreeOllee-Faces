package com.blizzardcaron.freeolleefaces.ble

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotifyFrameReassemblerTest {

    // Real captured 0x4B alarm read-back (20 bytes total: LEN=0x12 → 0x12+2). Single notify.
    private val alarm4B = byteArrayOf(
        0x00, 0x12, 0xAA.toByte(), 0x55, 0x36, 0x8A.toByte(),
        0x02, 0x4B, 0x01, 0x00, 0x00, 0x0D, 0x1E, 0xFE.toByte(),
        0x01, 0x05, 0xC0.toByte(), 0xFF.toByte(), 0x0F, 0xFF.toByte(),
    )

    @Test fun single_fragment_frame_emits_immediately() {
        val r = NotifyFrameReassembler()
        val frame = r.offer(alarm4B)
        assertTrue(frame != null && frame.crcOk)
        assertEquals(0x4B, frame.target)
    }

    @Test fun multi_fragment_frame_emits_only_when_complete() {
        // Synthetic 24-byte frame (LEN=0x16 → 0x16+2=24): header+inner, split [20][4].
        val inner = byteArrayOf(0x02, 0x4C) + ByteArray(16) { it.toByte() }
        val crc = OlleeProtocol.crc16(inner)
        val frameBytes = byteArrayOf(
            0x00, (inner.size + 4).toByte(), 0xAA.toByte(), 0x55,
            (crc shr 8).toByte(), (crc and 0xFF).toByte(),
        ) + inner
        val r = NotifyFrameReassembler()
        assertNull(r.offer(frameBytes.copyOfRange(0, 20)))      // first 20 bytes: incomplete
        val frame = r.offer(frameBytes.copyOfRange(20, frameBytes.size))
        assertTrue(frame != null && frame.crcOk)
        assertEquals(0x4C, frame.target)
    }

    @Test fun resets_between_frames() {
        val r = NotifyFrameReassembler()
        r.offer(alarm4B)
        assertEquals(0x4B, r.offer(alarm4B)?.target)           // second frame parses cleanly
    }

    @Test fun garbage_without_magic_is_dropped() {
        val r = NotifyFrameReassembler()
        assertNull(r.offer(byteArrayOf(0x00, 0x06, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66)))
    }
}

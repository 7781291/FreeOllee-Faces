package com.blizzardcaron.freeolleefaces.ble

/**
 * Reassembles fragmented Nordic-UART notify frames into complete [OlleeProtocol.Frame]s. The watch
 * splits a frame across 20-byte notifications; feed each fragment to [offer], which returns the
 * parsed frame once `LEN + 2` bytes have accumulated (else null). A leading byte that cannot begin a
 * valid `00 .. AA 55` frame is dropped as soon as that's decidable — byte0 != 0x00 the moment it
 * arrives, bad magic once 4 bytes are in — sliding the buffer forward so a stray fragment can't wedge
 * the stream or corrupt the next legitimate frame's start.
 */
class NotifyFrameReassembler {
    private val buf = mutableListOf<Byte>()

    fun offer(fragment: ByteArray): OlleeProtocol.Frame? {
        buf.addAll(fragment.toList())
        // Drop leading bytes that can't begin a valid frame, re-syncing to the next candidate
        // start rather than discarding the whole buffer.
        while (buf.isNotEmpty() && buf[0] != 0x00.toByte()) {
            buf.removeAt(0)
        }
        while (buf.size >= 4 && (buf[2] != 0xAA.toByte() || buf[3] != 0x55.toByte())) {
            buf.removeAt(0)
            while (buf.isNotEmpty() && buf[0] != 0x00.toByte()) {
                buf.removeAt(0)
            }
        }
        if (buf.size < 4) return null
        val total = (buf[1].toInt() and 0xFF) + 2 // frame length = LEN + 2
        if (buf.size < total) return null
        val frameBytes = buf.subList(0, total).toByteArray()
        buf.clear()
        return OlleeProtocol.parseFrame(frameBytes)
    }

    fun reset() = buf.clear()
}

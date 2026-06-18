package com.blizzardcaron.freeolleefaces.ble

/**
 * Reassembles fragmented Nordic-UART notify frames into complete [OlleeProtocol.Frame]s. The watch
 * splits a frame across 20-byte notifications; feed each fragment to [offer], which returns the
 * parsed frame once `LEN + 2` bytes have accumulated (else null). Drops a buffer whose header is not
 * a valid `00 .. AA 55` frame start, so a stray fragment can't wedge the stream.
 */
class NotifyFrameReassembler {
    private val buf = mutableListOf<Byte>()

    fun offer(fragment: ByteArray): OlleeProtocol.Frame? {
        buf.addAll(fragment.toList())
        if (buf.size >= 4 && (buf[2] != 0xAA.toByte() || buf[3] != 0x55.toByte())) {
            buf.clear(); return null                       // not a frame start — resync
        }
        if (buf.size < 2) return null
        val total = (buf[1].toInt() and 0xFF) + 2          // frame length = LEN + 2
        if (buf.size < total) return null
        val frameBytes = buf.subList(0, total).toByteArray()
        buf.clear()
        return OlleeProtocol.parseFrame(frameBytes)
    }

    fun reset() = buf.clear()
}

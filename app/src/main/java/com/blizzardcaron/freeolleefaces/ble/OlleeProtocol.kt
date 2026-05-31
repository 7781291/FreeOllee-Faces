package com.blizzardcaron.freeolleefaces.ble

object OlleeProtocol {

    const val MAX_VALUE_LENGTH = 6

    /** BLE field selectors (the second inner byte, after cmd 0x02). */
    const val TARGET_NAMEPLATE = 0x2f
    // Experimental: the Temperature face's field. The official app *reads* it at 0x2E
    // (response 0x4E, e.g. "  54 F"); whether *writing* 0x2E overrides what the face shows
    // is the open question this parameterization exists to test.
    // See docs/reference/ollee-ble-protocol.md.
    const val TARGET_TEMPERATURE = 0x2e

    fun crc16(data: ByteArray): Int {
        var crc = 0xFFFF
        for (b in data) {
            crc = crc xor ((b.toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if ((crc and 0x8000) != 0)
                    (crc shl 1) xor 0x1021
                else
                    crc shl 1
                crc = crc and 0xFFFF
            }
        }
        return crc
    }

    /** Builds a nameplate (0x2F) packet — the original FreeOllee behaviour. */
    fun buildPacket(value: String): ByteArray = buildPacket(TARGET_NAMEPLATE, value)

    /**
     * Builds a framed packet writing [value] to an arbitrary [target] field.
     * Framing: 00, len, AA, 55, crcHi, crcLo, 0x02, target, value… (len = inner.size + 4),
     * CRC-16/CCITT-FALSE over the inner bytes.
     */
    fun buildPacket(target: Int, value: String): ByteArray {
        require(target in 0..0xFF) { "target must be a single byte (got $target)" }
        require(value.length <= MAX_VALUE_LENGTH) {
            "value must be <= $MAX_VALUE_LENGTH chars (got ${value.length})"
        }
        require(value.all { it.code in 0..127 }) {
            "value must be ASCII (got '$value')"
        }

        val inner = byteArrayOf(0x02, target.toByte()) + value.toByteArray(Charsets.US_ASCII)
        val crc = crc16(inner)

        return byteArrayOf(
            0x00,
            (inner.size + 4).toByte(),
            0xaa.toByte(),
            0x55,
            (crc shr 8).toByte(),
            (crc and 0xFF).toByte()
        ) + inner
    }

    /** °F string matching the watch's read format ("  54 F"): right-justified to 6 chars. */
    fun formatTemperatureF(tempF: Int): String = "%4d F".format(tempF)
}

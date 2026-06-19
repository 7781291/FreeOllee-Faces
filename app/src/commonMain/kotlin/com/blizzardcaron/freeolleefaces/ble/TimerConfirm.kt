package com.blizzardcaron.freeolleefaces.ble

/**
 * Confirms a parsed `0x4C` timer read-back is consistent with a `0x26` timer push.
 *
 * The watch's `0x2C` read returns only `[HH, MM, SS, runFlag]` of the **active timer-face
 * countdown** — NOT the stored 10-slot table (verified on hardware 2026-06-17, watch `panther`).
 * The `HH:MM:SS` is the configured value of whatever the face is currently counting:
 * - **SAVE** (`byte3=0x00`) / **START_SINGLE** (`0x01`): the quick-timer header value.
 * - **START_INTERVAL** (`0x02`): the first slot's duration.
 *
 * `runFlag` is `0x00` when saved/idle and `0x02` when running. So this is a **partial**
 * confirmation: it verifies the active value and the run state match the push — catching a push
 * that did not land or did not start — but it cannot verify individual slot durations (1..9), or
 * slot0 in SAVE/SINGLE mode, which the watch does not expose on read-back.
 */
object TimerConfirm {
    private const val RUN_FLAG_RUNNING = 0x02

    fun matches(writePacket: ByteArray, frame: OlleeProtocol.Frame): Boolean {
        if (!frame.crcOk) return false
        if (frame.target != OlleeProtocol.TARGET_GET_TIMER + OlleeProtocol.RESPONSE_TARGET_OFFSET) return false
        val reply = frame.payload
        if (reply.size < 4) return false

        val intended = OlleeProtocol.parseFrame(writePacket) ?: return false
        val w = intended.payload
        if (w.size < 8) return false // [HH,MM,SS,mode] + at least slot0 (4 bytes)

        val headerSeconds = (w[0].toInt() and 0xFF) * 3600 + (w[1].toInt() and 0xFF) * 60 + (w[2].toInt() and 0xFF)
        val mode = w[3].toInt() and 0xFF
        val slot0 = (w[4].toInt() and 0xFF) or ((w[5].toInt() and 0xFF) shl 8) or
            ((w[6].toInt() and 0xFF) shl 16) or ((w[7].toInt() and 0xFF) shl 24)

        val expectedActive = if (mode == OlleeProtocol.TimerStartMode.START_INTERVAL.byte3) slot0 else headerSeconds
        val expectedRunning = mode != OlleeProtocol.TimerStartMode.SAVE.byte3

        val activeSeconds = (reply[0].toInt() and 0xFF) * 3600 +
            (reply[1].toInt() and 0xFF) * 60 +
            (reply[2].toInt() and 0xFF)
        val running = (reply[3].toInt() and 0xFF) == RUN_FLAG_RUNNING
        return activeSeconds == expectedActive && running == expectedRunning
    }
}

package com.blizzardcaron.freeolleefaces.ble

/**
 * Live status of the (foreground-only) watch link, surfaced to the UI. The phone cannot wake a
 * sleeping watch radio, so [NotReachable] is an expected resting state, not an error — it carries
 * the wake instructions via [wakeHint].
 */
enum class ConnectionStatus { NoWatch, Connecting, Connected, NotReachable }

/** Pure presentation model for the header chip-button. */
data class ConnectionChip(val label: String, val clickable: Boolean, val showSpinner: Boolean)

/** Maps a [status] to its chip label, whether tapping (re)connects, and whether to spin. */
fun connectionChip(status: ConnectionStatus): ConnectionChip = when (status) {
    ConnectionStatus.Connected -> ConnectionChip("Connected", clickable = false, showSpinner = false)
    ConnectionStatus.Connecting -> ConnectionChip("Connecting…", clickable = false, showSpinner = true)
    ConnectionStatus.NotReachable -> ConnectionChip("⟳ Reconnect", clickable = true, showSpinner = false)
    ConnectionStatus.NoWatch -> ConnectionChip("⟳ Reconnect", clickable = true, showSpinner = false)
}

/** The wake-instruction hint, shown only when a reconnect attempt has failed. */
fun wakeHint(status: ConnectionStatus): String? =
    if (status == ConnectionStatus.NotReachable) {
        "Wake the watch: long-press ALARM or triple-tap the Clock face, then tap Reconnect."
    } else {
        null
    }

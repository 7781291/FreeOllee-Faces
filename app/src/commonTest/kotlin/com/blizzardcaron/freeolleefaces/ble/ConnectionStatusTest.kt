package com.blizzardcaron.freeolleefaces.ble

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConnectionStatusTest {

    @Test
    fun chip_connected_isNotClickable_noSpinner() {
        val chip = connectionChip(ConnectionStatus.Connected)
        assertEquals("Connected", chip.label)
        assertFalse(chip.clickable, "Connected chip should not invite a reconnect tap")
        assertFalse(chip.showSpinner)
    }

    @Test
    fun chip_connecting_showsSpinner_andIsNotClickable() {
        val chip = connectionChip(ConnectionStatus.Connecting)
        assertEquals("Connecting…", chip.label)
        assertTrue(chip.showSpinner, "Connecting chip should show a spinner")
        assertFalse(chip.clickable, "no reconnect while a connect is already in flight")
    }

    @Test
    fun chip_notReachable_isClickableReconnect() {
        val chip = connectionChip(ConnectionStatus.NotReachable)
        assertEquals("⟳ Reconnect", chip.label)
        assertTrue(chip.clickable, "NotReachable chip is the reconnect action")
        assertFalse(chip.showSpinner)
    }

    @Test
    fun chip_noWatch_isClickableReconnect() {
        val chip = connectionChip(ConnectionStatus.NoWatch)
        assertEquals("⟳ Reconnect", chip.label)
        assertTrue(chip.clickable)
        assertFalse(chip.showSpinner)
    }

    @Test
    fun wakeHint_onlyForNotReachable() {
        assertEquals(
            "Wake the watch: long-press ALARM or triple-tap the Clock face, then tap Reconnect.",
            wakeHint(ConnectionStatus.NotReachable),
        )
        assertNull(wakeHint(ConnectionStatus.Connected))
        assertNull(wakeHint(ConnectionStatus.Connecting))
        assertNull(wakeHint(ConnectionStatus.NoWatch))
    }
}

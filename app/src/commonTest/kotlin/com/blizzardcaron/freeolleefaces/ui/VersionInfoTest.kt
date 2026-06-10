package com.blizzardcaron.freeolleefaces.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class VersionInfoTest {

    @Test
    fun `versionLabel formats name and package`() {
        assertEquals(
            "v0.6.2 · com.blizzardcaron.freeolleefaces",
            versionLabel("0.6.2", "com.blizzardcaron.freeolleefaces"),
        )
    }

    @Test
    fun `versionLabel renders null name as question mark`() {
        assertEquals(
            "v? · com.blizzardcaron.freeolleefaces.exp",
            versionLabel(null, "com.blizzardcaron.freeolleefaces.exp"),
        )
    }
}

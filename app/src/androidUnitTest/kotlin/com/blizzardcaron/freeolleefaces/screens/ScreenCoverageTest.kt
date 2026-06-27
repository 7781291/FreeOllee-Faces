package com.blizzardcaron.freeolleefaces.screens

import com.blizzardcaron.freeolleefaces.ui.Screen
import kotlin.test.assertEquals
import org.junit.Test

class ScreenCoverageTest {
    @Test
    fun everyScreenSubtypeIsRegistered() {
        val declared = Screen::class.sealedSubclasses
            .mapNotNull { it.objectInstance }
            .toSet()
        assertEquals(
            declared,
            allScreens.toSet(),
            "allScreens (and renderFor) must list every Screen subtype",
        )
    }
}

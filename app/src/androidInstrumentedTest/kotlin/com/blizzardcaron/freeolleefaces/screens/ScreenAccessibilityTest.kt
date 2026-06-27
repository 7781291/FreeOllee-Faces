package com.blizzardcaron.freeolleefaces.screens

import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import org.junit.Rule
import org.junit.Test

/**
 * Runs Google's Accessibility Test Framework (ATF) over every screen via Compose's
 * enableAccessibilityChecks(): color contrast, touch-target size, content labeling, and
 * traversal order. An ATF violation fails the test, naming the screen.
 *
 * Instrumented (emulator/device) only — enableAccessibilityChecks() is supported on
 * AndroidComposeTestRule. Verified in CI; the Pi cannot run an emulator.
 */
class ScreenAccessibilityTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun everyScreenPassesAccessibilityChecks() {
        composeRule.enableAccessibilityChecks()
        allScreens.forEach { screen ->
            composeRule.setContent { renderFor(screen)() }
            composeRule.waitForIdle()
            composeRule.onAllNodes(isRoot()).tryPerformAccessibilityChecks()
        }
    }
}

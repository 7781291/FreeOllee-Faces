package com.blizzardcaron.freeolleefaces.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import com.blizzardcaron.freeolleefaces.ui.Screen
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
        // A ComposeTestRule accepts setContent only once, so drive the screen under test
        // through a single composition and swap it via a MutableState. Each swap
        // recomposes the same root, then ATF runs against that screen.
        var current by mutableStateOf<Screen>(allScreens.first())
        composeRule.enableAccessibilityChecks()
        composeRule.setContent { renderFor(current)() }
        // Check every screen and accumulate findings (rather than fail-fast) so a regression on
        // several screens surfaces all at once, each tagged with the screen and ATF's report.
        val failures = StringBuilder()
        allScreens.forEach { screen ->
            composeRule.runOnUiThread { current = screen }
            composeRule.waitForIdle()
            @Suppress("TooGenericExceptionCaught")
            try {
                composeRule.onAllNodes(isRoot()).tryPerformAccessibilityChecks()
            } catch (e: Throwable) {
                failures.append("=== A11y failure on screen=").append(screen).append(" ===\n")
                    .append(e.message).append("\n\n")
            }
        }
        if (failures.isNotEmpty()) throw AssertionError(failures.toString())
    }
}

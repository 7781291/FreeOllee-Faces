package com.blizzardcaron.freeolleefaces.screens

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.platform.app.InstrumentationRegistry
import com.blizzardcaron.freeolleefaces.ui.Screen
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Records one screenshot per [Screen] on the emulator into the app's external files dir
 * (`<externalFilesDir>/screenshots/<slug>.png`), which CI pulls and uploads as the
 * `screenshots` artifact for the README. Running on a real emulator (not Robolectric) means
 * the actual APK assets — fonts and drawables — load correctly.
 *
 * Instrumented (emulator/device) only; the Pi cannot run an emulator.
 */
class ScreenScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun captureEveryScreen() {
        val outputDir = File(
            InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
            "screenshots",
        ).apply { mkdirs() }

        // One composition, swapped via MutableState (a ComposeTestRule accepts setContent once).
        var current by mutableStateOf<Screen>(allScreens.first())
        composeRule.setContent { renderFor(current)() }
        allScreens.forEach { screen ->
            composeRule.runOnUiThread { current = screen }
            composeRule.waitForIdle()
            val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
            File(outputDir, "${screen.slug()}.png").outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        }
    }
}

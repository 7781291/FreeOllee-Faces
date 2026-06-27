package com.blizzardcaron.freeolleefaces.screens

import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

/**
 * Records one screenshot per [com.blizzardcaron.freeolleefaces.ui.Screen] into
 * `docs/screenshots/<slug>.png` (paths relative to the `app/` module dir).
 *
 * Robolectric native graphics has no Linux aarch64 build, so this CANNOT run on the
 * Raspberry Pi; it is recorded on x86_64 CI. The assumeFalse guard skips it on aarch64
 * so the local `testDebugUnitTest` gate stays green. Robolectric maxes out at SDK 36,
 * hence the SDK-34 pin; Conscrypt is disabled (no aarch64 JNI, and screenshots need no SSL).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [34])
class ScreenScreenshotTest {
    @Test
    fun captureEveryScreen() {
        assumeFalse(
            "Robolectric native runtime unsupported on aarch64",
            System.getProperty("os.arch")?.contains("aarch64") == true,
        )
        allScreens.forEach { screen ->
            captureRoboImage("../docs/screenshots/${screen.slug()}.png") {
                renderFor(screen)()
            }
        }
    }
}

package com.orion.player.ui.playback

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.orion.player.ui.playback.player.ImagePlayer
import com.orion.player.ui.playback.player.VideoPlayer
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runtime proof that Stretch to Fit stretches VIDEO to the container (non-uniform
 * scale) while IMAGE continues to fill the display. Uses a 4:3 color-bar fixture
 * on a 16:9 Android TV surface so letterbox vs stretch vs crop are distinguishable.
 */
@RunWith(AndroidJUnit4::class)
class StretchToFitInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun videoStretchOnFillsContainerWithoutLetterbox() {
        val video = copyAsset("stretch_bars_4x3.mp4")
        val started = AtomicBoolean(false)
        val failed = AtomicBoolean(false)

        composeRule.setContent {
            CompositionLocalProvider(LocalStretchToFit provides true) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(ComposeColor.Magenta)
                ) {
                    VideoPlayer(
                        file = video,
                        playbackSessionKey = "stretch-on",
                        onPlaybackStarted = { started.set(true) },
                        onError = { failed.set(true) },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        composeRule.waitUntil(15_000) { started.get() || failed.get() }
        assertTrue("video must start, error=$failed", started.get())
        Thread.sleep(1_500)

        val shot = capture("video_stretch_on.png")
        val top = edgeMatchRatio(shot, Edge.TOP, 255, 255, 0)
        val bottom = edgeMatchRatio(shot, Edge.BOTTOM, 0, 255, 255)
        val left = edgeMatchRatio(shot, Edge.LEFT, 255, 0, 0)
        val right = edgeMatchRatio(shot, Edge.RIGHT, 0, 0, 255)
        val magenta = edgeMatchRatio(shot, Edge.LEFT, 255, 0, 255)
        Log.i(
            TAG,
            "VIDEO stretch ON topYellow=$top bottomCyan=$bottom leftRed=$left " +
                "rightBlue=$right leftMagenta=$magenta size=${shot.width}x${shot.height}"
        )
        assertTrue("stretch must not letterbox (magenta=$magenta)", magenta < 0.15f)
        assertTrue("top edge must keep yellow bar (stretch, not crop) ratio=$top", top > 0.40f)
        assertTrue("bottom edge must keep cyan bar ratio=$bottom", bottom > 0.40f)
    }

    @Test
    fun videoStretchOffKeepsLetterbox() {
        val video = copyAsset("stretch_bars_4x3.mp4")
        val started = AtomicBoolean(false)
        val failed = AtomicBoolean(false)

        composeRule.setContent {
            CompositionLocalProvider(LocalStretchToFit provides false) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(ComposeColor.Magenta)
                ) {
                    VideoPlayer(
                        file = video,
                        playbackSessionKey = "stretch-off",
                        onPlaybackStarted = { started.set(true) },
                        onError = { failed.set(true) },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        composeRule.waitUntil(15_000) { started.get() || failed.get() }
        assertTrue("video must start, error=$failed", started.get())
        Thread.sleep(1_500)

        val shot = capture("video_stretch_off.png")
        val magentaLeft = edgeMatchRatio(shot, Edge.LEFT, 255, 0, 255)
        val magentaRight = edgeMatchRatio(shot, Edge.RIGHT, 255, 0, 255)
        Log.i(
            TAG,
            "VIDEO stretch OFF magentaLeft=$magentaLeft magentaRight=$magentaRight " +
                "size=${shot.width}x${shot.height}"
        )
        assertTrue(
            "FIT must letterbox 4:3 on 16:9 (magenta L=$magentaLeft R=$magentaRight)",
            magentaLeft > 0.40f && magentaRight > 0.40f
        )
    }

    @Test
    fun imageStretchOnFillsDisplay() {
        val image = copyAsset("stretch_bars_4x3.png")
        composeRule.setContent {
            CompositionLocalProvider(LocalStretchToFit provides true) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(ComposeColor.Magenta)
                ) {
                    ImagePlayer(file = image, modifier = Modifier.fillMaxSize())
                }
            }
        }
        composeRule.waitForIdle()
        Thread.sleep(1_500)

        val shot = capture("image_stretch_on.png")
        val magentaLeft = edgeMatchRatio(shot, Edge.LEFT, 255, 0, 255)
        val magentaRight = edgeMatchRatio(shot, Edge.RIGHT, 255, 0, 255)
        val top = edgeMatchRatio(shot, Edge.TOP, 255, 255, 0)
        val bottom = edgeMatchRatio(shot, Edge.BOTTOM, 0, 255, 255)
        Log.i(
            TAG,
            "IMAGE stretch ON magentaLeft=$magentaLeft magentaRight=$magentaRight " +
                "topYellow=$top bottomCyan=$bottom size=${shot.width}x${shot.height}"
        )
        assertTrue(
            "image stretch ON must fill display (magenta L=$magentaLeft R=$magentaRight)",
            magentaLeft < 0.15f && magentaRight < 0.15f
        )
        assertTrue(
            "image stretch ON must keep top/bottom content (not center-crop) " +
                "topYellow=$top bottomCyan=$bottom",
            top > 0.40f && bottom > 0.40f
        )
    }

    @Test
    fun imageStretchOffKeepsLetterbox() {
        val image = copyAsset("stretch_bars_4x3.png")
        composeRule.setContent {
            CompositionLocalProvider(LocalStretchToFit provides false) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(ComposeColor.Magenta)
                ) {
                    ImagePlayer(file = image, modifier = Modifier.fillMaxSize())
                }
            }
        }
        composeRule.waitForIdle()
        Thread.sleep(1_500)

        val shot = capture("image_stretch_off.png")
        val blackLeft = edgeMatchRatio(shot, Edge.LEFT, 0, 0, 0)
        val blackRight = edgeMatchRatio(shot, Edge.RIGHT, 0, 0, 0)
        Log.i(
            TAG,
            "IMAGE stretch OFF blackLeft=$blackLeft blackRight=$blackRight " +
                "size=${shot.width}x${shot.height}"
        )
        assertTrue(
            "FIT must letterbox 4:3 on 16:9 (black L=$blackLeft R=$blackRight)",
            blackLeft > 0.40f && blackRight > 0.40f
        )
    }

    private fun copyAsset(name: String): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val out = File(instrumentation.targetContext.cacheDir, name)
        instrumentation.context.assets.open(name).use { input ->
            FileOutputStream(out).use { input.copyTo(it) }
        }
        return out
    }

    private fun capture(filename: String): Bitmap {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        val dir = instrumentation.targetContext.getExternalFilesDir(null)
        FileOutputStream(File(dir, filename)).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        return bitmap
    }

    private enum class Edge { TOP, BOTTOM, LEFT, RIGHT }

    private fun edgeMatchRatio(
        bitmap: Bitmap,
        edge: Edge,
        r: Int,
        g: Int,
        b: Int,
        tol: Int = 80
    ): Float {
        val inset = 8
        val samples = 40
        var hits = 0
        for (i in 0 until samples) {
            val t = i / (samples - 1).toFloat()
            val (x, y) = when (edge) {
                Edge.TOP -> Pair((t * (bitmap.width - 1)).toInt(), inset)
                Edge.BOTTOM -> Pair((t * (bitmap.width - 1)).toInt(), bitmap.height - 1 - inset)
                Edge.LEFT -> Pair(inset, (t * (bitmap.height - 1)).toInt())
                Edge.RIGHT -> Pair(bitmap.width - 1 - inset, (t * (bitmap.height - 1)).toInt())
            }
            val pixel = bitmap.getPixel(x.coerceIn(0, bitmap.width - 1), y.coerceIn(0, bitmap.height - 1))
            if (near(pixel, r, g, b, tol)) hits++
        }
        return hits / samples.toFloat()
    }

    private fun near(pixel: Int, r: Int, g: Int, b: Int, tol: Int): Boolean {
        return kotlin.math.abs(Color.red(pixel) - r) <= tol &&
            kotlin.math.abs(Color.green(pixel) - g) <= tol &&
            kotlin.math.abs(Color.blue(pixel) - b) <= tol
    }

    companion object {
        private const val TAG = "OrionStretchToFit"
    }
}

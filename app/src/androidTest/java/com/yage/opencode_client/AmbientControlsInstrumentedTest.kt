package com.yage.opencode_client

import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.yage.opencode_client.ambient.protocol.AmbientActivity
import com.yage.opencode_client.ambient.protocol.AmbientBackendUsage
import com.yage.opencode_client.ambient.protocol.AmbientCaption
import com.yage.opencode_client.ambient.protocol.AmbientMeter
import com.yage.opencode_client.ambient.protocol.AmbientPhase
import com.yage.opencode_client.ambient.protocol.AmbientSnapshot
import com.yage.opencode_client.ambient.ui.AmbientOutputVolume
import com.yage.opencode_client.ambient.ui.AmbientScreen
import com.yage.opencode_client.ambient.ui.AmbientTags
import com.yage.opencode_client.ui.theme.OpenCodeTheme
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AmbientControlsInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun liveControlsShare72dpRowAndMutedSelectionInvokesCallbacks() {
        var mutedTo: Boolean? = null
        var stops = 0
        var ends = 0
        show(
            snapshot(phase = AmbientPhase.LISTENING, muted = true, inputRms = 0.04),
            onSetMuted = { mutedTo = it },
            onInterruptPlayback = { stops++ },
            onEnd = { ends++ },
        )

        val mute = bounds(AmbientTags.MUTE)
        val stop = bounds(AmbientTags.STOP)
        val end = bounds(AmbientTags.END)
        val mic = bounds(AmbientTags.MIC)
        val rowDp = AmbientMeter.CONTROL_ROW_DP.toFloat()
        val glyphDp = AmbientMeter.GLYPH_DP.toFloat()
        assertEquals("mute height", rowDp, heightDp(mute.height), 0.5f)
        assertEquals("stop height", rowDp, heightDp(stop.height), 0.5f)
        assertEquals("end height", rowDp, heightDp(end.height), 0.5f)
        assertEquals("shared top", mute.top, stop.top, 1f)
        assertEquals("shared top", mute.top, end.top, 1f)
        assertEquals("equal slots", mute.width, stop.width, 1f)
        assertEquals("equal slots", stop.width, end.width, 1f)
        assertTrue(mute.right <= stop.left + 1f)
        assertTrue(stop.right <= end.left + 1f)
        assertEquals("glyph width", glyphDp, heightDp(mic.width), 0.5f)
        assertEquals("glyph height", glyphDp, heightDp(mic.height), 0.5f)
        assertTrue(mic.left >= mute.left - 1f && mic.right <= mute.right + 1f)
        assertTrue(mic.top >= mute.top - 1f && mic.bottom <= mute.bottom + 1f)

        composeRule.onNodeWithTag(AmbientTags.MUTE).assertIsSelected().assertIsEnabled().performClick()
        composeRule.onNodeWithTag(AmbientTags.STOP).assertIsNotSelected().assertIsEnabled().performClick()
        composeRule.onNodeWithTag(AmbientTags.END).assertIsNotSelected().assertIsEnabled().performClick()
        assertEquals(false, mutedTo)
        assertEquals(1, stops)
        assertEquals(1, ends)
    }

    @Test
    fun idleConnectAndConnectingEnd() {
        var connects = 0
        var ends = 0
        val snapshotState = mutableStateOf<AmbientSnapshot>(snapshot(AmbientPhase.IDLE))
        composeRule.setContent {
            OpenCodeTheme {
                AmbientScreen(
                    snapshot = snapshotState.value,
                    outputVolume = AmbientOutputVolume(value = 4, max = 15),
                    onConnect = { connects++ },
                    onSetMuted = {},
                    onInterruptPlayback = {},
                    onEnd = { ends++ },
                    onOutputVolumeChange = {},
                )
            }
        }
        composeRule.onNodeWithTag(AmbientTags.CONNECT).assertIsDisplayed().assertIsEnabled()
        composeRule.onNodeWithText("Start").assertIsDisplayed()
        composeRule.onNodeWithText("Disconnected").assertIsDisplayed()
        val connect = bounds(AmbientTags.CONNECT)
        assertEquals(AmbientMeter.CONTROL_ROW_DP.toFloat(), heightDp(connect.height), 0.5f)
        assertEquals(bounds(AmbientTags.CONTROLS).width, connect.width, 1f)
        composeRule.onNodeWithTag(AmbientTags.MUTE).assertDoesNotExist()
        composeRule.onNodeWithTag(AmbientTags.END).assertDoesNotExist()
        composeRule.onNodeWithTag(AmbientTags.MIC).assertDoesNotExist()
        composeRule.onNodeWithTag(AmbientTags.CONNECT).performClick()
        assertEquals(1, connects)
        assertEquals(0, ends)

        composeRule.runOnIdle {
            snapshotState.value = snapshot(AmbientPhase.CONNECTING)
        }
        composeRule.onNodeWithTag(AmbientTags.END).assertIsDisplayed().assertIsEnabled()
        composeRule.onNodeWithText("End").assertIsDisplayed()
        composeRule.onNodeWithText("Connecting…").assertIsDisplayed()
        val cancel = bounds(AmbientTags.END)
        assertEquals(AmbientMeter.CONTROL_ROW_DP.toFloat(), heightDp(cancel.height), 0.5f)
        assertEquals(bounds(AmbientTags.CONTROLS).width, cancel.width, 1f)
        composeRule.onNodeWithTag(AmbientTags.CONNECT).assertDoesNotExist()
        composeRule.onNodeWithTag(AmbientTags.MUTE).assertDoesNotExist()
        composeRule.onNodeWithTag(AmbientTags.MIC).assertDoesNotExist()
        composeRule.onNodeWithTag(AmbientTags.END).performClick()
        assertEquals(1, connects)
        assertEquals(1, ends)
    }

    @Test
    fun expandedDetailsRetainsRawRmsAndSeparatesPortal() {
        show(
            snapshot(
                phase = AmbientPhase.LISTENING,
                inputRms = 0.126,
                outputRms = 0.124,
                voiceSeconds = 3.225,
                local = AmbientBackendUsage(
                    inputTokens = 1200,
                    outputTokens = 340,
                    cachedRead = 50,
                    cacheWrite = 10,
                    estimatedUsd = 0.135,
                    unpricedCount = 0,
                ),
                portalUsd = null,
                portalSettled = false,
                usageUnconfirmed = false,
            ),
        )
        composeRule.onNodeWithTag(AmbientTags.DETAILS_BODY).assertDoesNotExist()
        composeRule.onNodeWithTag(AmbientTags.DETAILS).performClick()
        composeRule.onNodeWithTag(AmbientTags.DETAILS_BODY).assertIsDisplayed()
        composeRule.onNodeWithTag(AmbientTags.INPUT_LEVEL, useUnmergedTree = true).assertTextEquals("0.13")
        composeRule.onNodeWithTag(AmbientTags.OUTPUT_LEVEL, useUnmergedTree = true).assertTextEquals("0.12")
        composeRule.onNodeWithTag(AmbientTags.VOICE_DURATION, useUnmergedTree = true).assertTextEquals("3.23")
        composeRule.onNodeWithTag(AmbientTags.SESSION_DURATION, useUnmergedTree = true).assertTextEquals("30")
        composeRule.onNodeWithTag(AmbientTags.INPUT_TOKENS, useUnmergedTree = true).assertTextEquals("1200")
        composeRule.onNodeWithTag(AmbientTags.OUTPUT_TOKENS, useUnmergedTree = true).assertTextEquals("340")
        composeRule.onNodeWithTag(AmbientTags.CACHE_READ, useUnmergedTree = true).assertTextEquals("50")
        composeRule.onNodeWithTag(AmbientTags.CACHE_WRITE, useUnmergedTree = true).assertTextEquals("10")
        composeRule.onNodeWithTag(AmbientTags.LOCAL_ESTIMATE, useUnmergedTree = true).assertTextEquals("0.14")
        composeRule.onNodeWithTag(AmbientTags.PORTAL_ESTIMATE, useUnmergedTree = true).assertTextEquals("Unconfirmed")
    }

    @Test
    fun captionAndExpandedToolResultUpdateWithoutCollapsing() {
        val captionText = mutableStateOf("caption one")
        val toolResult = mutableStateOf("result one")
        composeRule.setContent {
            OpenCodeTheme {
                AmbientScreen(
                    snapshot = snapshot(
                        phase = AmbientPhase.LISTENING,
                        captions = listOf(
                            AmbientCaption(
                                id = "cap-1",
                                role = "assistant",
                                text = captionText.value,
                                sequence = 1,
                            ),
                        ),
                        activities = listOf(
                            AmbientActivity(
                                id = "tool-1",
                                name = "lookup",
                                arguments = "{\"q\":\"tide\"}",
                                status = "running",
                                result = toolResult.value,
                                sequence = 2,
                            ),
                        ),
                    ),
                    outputVolume = AmbientOutputVolume(value = 4, max = 15),
                    onConnect = {},
                    onSetMuted = {},
                    onInterruptPlayback = {},
                    onEnd = {},
                    onOutputVolumeChange = {},
                )
            }
        }

        composeRule.onNodeWithTag(AmbientTags.caption("cap-1")).assertTextEquals("caption one")
        composeRule.onNodeWithTag(AmbientTags.toolResult("tool-1")).assertDoesNotExist()
        composeRule.onNodeWithTag(AmbientTags.tool("tool-1")).performClick()
        composeRule.onNodeWithTag(AmbientTags.toolResult("tool-1")).assertIsDisplayed()
        composeRule.onNodeWithText("result one").assertIsDisplayed()

        composeRule.runOnIdle {
            captionText.value = "caption two"
            toolResult.value = "result two"
        }

        composeRule.onNodeWithTag(AmbientTags.caption("cap-1")).assertTextEquals("caption two")
        composeRule.onNodeWithTag(AmbientTags.toolResult("tool-1")).assertIsDisplayed()
        composeRule.onNodeWithText("result two").assertIsDisplayed()
        composeRule.onNodeWithText("caption one").assertDoesNotExist()
        composeRule.onNodeWithText("result one").assertDoesNotExist()
    }

    @Test
    fun syntheticLevelsFillFromTheBottomAndCradleMeetsTheHead() {
        val snapshotState = mutableStateOf(snapshot(phase = AmbientPhase.LISTENING, inputRms = 0.01))
        composeRule.setContent {
            OpenCodeTheme {
                AmbientScreen(
                    snapshot = snapshotState.value,
                    outputVolume = AmbientOutputVolume(value = 4, max = 15),
                    onConnect = {},
                    onSetMuted = {},
                    onInterruptPlayback = {},
                    onEnd = {},
                    onOutputVolumeChange = {},
                )
            }
        }
        settle()
        var glyph = micGlyph()
        assertGlyphFrame(glyph)
        assertCradleMeetsHead(glyph)
        assertBottomUpFill(glyph, expectedFill = 0.2f)

        composeRule.runOnIdle {
            snapshotState.value = snapshot(phase = AmbientPhase.LISTENING, inputRms = 0.0625)
        }
        settle()
        glyph = micGlyph()
        assertBottomUpFill(glyph, expectedFill = 0.5f)
        captureActiveControls()

        composeRule.runOnIdle {
            snapshotState.value = snapshot(phase = AmbientPhase.LISTENING, inputRms = 0.25)
        }
        settle()
        glyph = micGlyph()
        assertBottomUpFill(glyph, expectedFill = 1f)

        composeRule.runOnIdle {
            snapshotState.value = snapshot(phase = AmbientPhase.LISTENING, muted = true, inputRms = 0.25)
        }
        settle()
        glyph = micGlyph()
        assertMutedSlashClearsFill(glyph)
        composeRule.onNodeWithTag(AmbientTags.MUTE).assertIsSelected()
    }

    private fun show(
        snapshot: AmbientSnapshot,
        onConnect: () -> Unit = {},
        onSetMuted: (Boolean) -> Unit = {},
        onInterruptPlayback: () -> Unit = {},
        onEnd: () -> Unit = {},
    ) {
        composeRule.setContent {
            OpenCodeTheme {
                AmbientScreen(
                    snapshot = snapshot,
                    outputVolume = AmbientOutputVolume(value = 4, max = 15),
                    onConnect = onConnect,
                    onSetMuted = onSetMuted,
                    onInterruptPlayback = onInterruptPlayback,
                    onEnd = onEnd,
                    onOutputVolumeChange = {},
                )
            }
        }
    }

    private fun bounds(tag: String) =
        composeRule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private fun heightDp(pixels: Float): Float = pixels / composeRule.density.density

    private fun snapshot(
        phase: AmbientPhase,
        muted: Boolean = false,
        inputRms: Double = 0.0,
        outputRms: Double = 0.0,
        voiceSeconds: Double = 12.5,
        captions: List<AmbientCaption> = emptyList(),
        activities: List<AmbientActivity> = emptyList(),
        local: AmbientBackendUsage = AmbientBackendUsage(),
        portalUsd: Double? = null,
        portalSettled: Boolean = false,
        usageUnconfirmed: Boolean = false,
    ) = AmbientSnapshot(
        phase = phase,
        captions = captions,
        activities = activities,
        muted = muted,
        inputRms = inputRms,
        outputRms = outputRms,
        voiceSeconds = voiceSeconds,
        voiceHasSnapshot = true,
        voiceUnconfirmed = false,
        local = local,
        portalUsd = portalUsd,
        portalSettled = portalSettled,
        usageUnconfirmed = usageUnconfirmed,
        statusMessage = null,
        elapsedSeconds = 30L,
    )

    private fun settle() {
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(400)
        composeRule.waitForIdle()
    }

    private fun micGlyph(): GlyphPixels {
        val pixels = composeRule.onNodeWithTag(AmbientTags.MIC, useUnmergedTree = true)
            .captureToImage()
            .toPixelMap()
        return GlyphPixels(pixels)
    }

    private fun assertGlyphFrame(glyph: GlyphPixels) {
        val expected = AmbientMeter.GLYPH_DP * composeRule.density.density
        assertEquals("glyph width", expected, glyph.pixels.width.toFloat(), 1f)
        assertEquals("glyph height", expected, glyph.pixels.height.toFloat(), 1f)
    }

    /**
     * Ink positions come from the iOS 28-unit microphone, not from [com.yage.opencode_client.ambient.ui.AmbientMicLayout].
     * Head top 3.7, head height 14.4, cradle gap 2.3, cradle drop 2.8, stem drop 3.5.
     */
    private fun assertCradleMeetsHead(glyph: GlyphPixels) {
        assertTrue(
            "capsule no longer starts at the old y=0.08",
            glyph.clear(0.5f, 0.08f),
        )
        val headTop = micUnit(3.7f)
        val headBottom = micUnit(3.7f + 14.4f)
        val cradleLeft = micUnit((28f - 10.4f) / 2f - 2.3f)
        val cradleRight = micUnit((28f - 10.4f) / 2f + 10.4f + 2.3f)
        val armTop = 3.7f + 14.4f * 0.42f
        val arcCenter = 3.7f + 14.4f + 2.8f - (10.4f + 2.3f * 2f) / 2f
        val armMid = micUnit((armTop + arcCenter) / 2f)
        assertTrue("head top stroke", glyph.ink(0.5f, headTop))
        assertTrue("head bottom stroke", glyph.ink(0.5f, headBottom))
        assertTrue("left cradle arm", glyph.ink(cradleLeft, armMid))
        assertTrue("right cradle arm", glyph.ink(cradleRight, armMid))
        assertTrue("base under the stem", glyph.ink(0.5f, micUnit(3.7f + 14.4f + 2.8f + 3.5f)))
    }

    private fun assertBottomUpFill(glyph: GlyphPixels, expectedFill: Float) {
        val column = glyph.innerColumn()
        val filled = column.filter { glyph.isFill(it.color) }
        assertTrue("expected capsule fill $expectedFill", filled.isNotEmpty())
        val fraction = filled.size.toFloat() / column.size
        assertEquals("fill fraction", expectedFill, fraction, 0.12f)
        val span = column.last().y - column.first().y
        val topRatio = (filled.first().y - column.first().y).toFloat() / span.coerceAtLeast(1)
        assertEquals("fill grows from the bottom", 1f - expectedFill, topRatio, 0.15f)
        val bottomGap = column.last().y - filled.last().y
        assertTrue("fill reaches the inner head", bottomGap <= (span * 0.12f).roundToInt().coerceAtLeast(1))
        assertTrue("low band", glyph.isFill(glyph.at(0.5f, micUnit(15.5f))) == (expectedFill >= 0.2f))
        assertTrue("mid band", glyph.isFill(glyph.at(0.5f, micUnit(12.445f))) == (expectedFill >= 0.5f))
        assertTrue("upper band", glyph.isFill(glyph.at(0.5f, micUnit(7.81f))) == (expectedFill >= 0.95f))
    }

    private fun assertMutedSlashClearsFill(glyph: GlyphPixels) {
        val column = glyph.innerColumn()
        assertTrue("mute clears the capsule", column.none { glyph.isFill(it.color) })
        assertTrue("mute slash", glyph.ink(micUnit(13.9f), micUnit(14.7f)))
        assertTrue("upper head stays empty", glyph.clear(0.5f, micUnit(7.81f)))
    }

    private fun captureActiveControls() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = context.getExternalFilesDir(null) ?: error("external files dir unavailable")
        dir.mkdirs()
        val bitmap = composeRule.onNodeWithTag(AmbientTags.CONTROLS).captureToImage().asAndroidBitmap()
        try {
            FileOutputStream(File(dir, "ambient-controls-feedback.png")).use { out ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out))
            }
        } finally {
            bitmap.recycle()
        }
    }

    private class GlyphPixels(val pixels: PixelMap) {
        val background: Color = pixels[1.coerceAtMost(pixels.width - 1), 1.coerceAtMost(pixels.height - 1)]

        fun at(xFrac: Float, yFrac: Float): Color = pixels[px(xFrac, pixels.width), px(yFrac, pixels.height)]

        fun ink(xFrac: Float, yFrac: Float): Boolean {
            val x = px(xFrac, pixels.width)
            val y = px(yFrac, pixels.height)
            for (yy in (y - 1)..(y + 1)) {
                for (xx in (x - 1)..(x + 1)) {
                    if (xx !in 0 until pixels.width || yy !in 0 until pixels.height) continue
                    if (isInk(pixels[xx, yy])) return true
                }
            }
            return false
        }

        fun clear(xFrac: Float, yFrac: Float): Boolean = !isInk(at(xFrac, yFrac))

        fun isFill(color: Color): Boolean =
            color.dist2(FILL) < 0.03f && color.dist2(background) > 0.04f

        fun isInk(color: Color): Boolean = color.dist2(background) > 0.05f

        fun innerColumn(): List<Sample> {
            val x = px(0.5f, pixels.width)
            val inset = 1.8f / 2f + 1.15f
            val top = px(micUnit(3.7f + inset), pixels.height)
            val bottom = px(micUnit(18.1f - inset), pixels.height)
            return (top..bottom).map { y -> Sample(y, pixels[x, y]) }
        }

        private fun px(fraction: Float, length: Int) =
            (fraction * (length - 1)).roundToInt().coerceIn(0, length - 1)

        private fun Color.dist2(other: Color): Float {
            val dr = red - other.red
            val dg = green - other.green
            val db = blue - other.blue
            return dr * dr + dg * dg + db * db
        }
    }

    private data class Sample(val y: Int, val color: Color)

    private companion object {
        val FILL = Color(0xFF3B82F6)

        fun micUnit(units: Float) = units / 28f
    }
}

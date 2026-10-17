package com.yage.opencode_client

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertContentDescriptionEquals
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
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
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
        composeRule.onNodeWithTag(AmbientTags.VOICE_DURATION, useUnmergedTree = true).assertTextEquals("00:00:03")
        composeRule.onNodeWithTag(AmbientTags.SESSION_DURATION, useUnmergedTree = true).assertTextEquals("00:00:30")
        composeRule.onNodeWithTag(AmbientTags.INPUT_TOKENS, useUnmergedTree = true).assertTextEquals("1.2K")
        composeRule.onNodeWithTag(AmbientTags.INPUT_TOKENS, useUnmergedTree = true)
            .assertContentDescriptionEquals("1200")
        composeRule.onNodeWithTag(AmbientTags.OUTPUT_TOKENS, useUnmergedTree = true).assertTextEquals("340")
        composeRule.onNodeWithTag(AmbientTags.OUTPUT_TOKENS, useUnmergedTree = true)
            .assertContentDescriptionEquals("340")
        composeRule.onNodeWithTag(AmbientTags.CACHE_READ, useUnmergedTree = true).assertTextEquals("50")
        composeRule.onNodeWithTag(AmbientTags.CACHE_READ, useUnmergedTree = true)
            .assertContentDescriptionEquals("50")
        composeRule.onNodeWithTag(AmbientTags.CACHE_WRITE, useUnmergedTree = true).assertTextEquals("10")
        composeRule.onNodeWithTag(AmbientTags.CACHE_WRITE, useUnmergedTree = true)
            .assertContentDescriptionEquals("10")
        composeRule.onNodeWithTag(AmbientTags.LOCAL_ESTIMATE, useUnmergedTree = true).assertTextEquals('$' + "0.14")
        composeRule.onNodeWithTag(AmbientTags.PORTAL_ESTIMATE, useUnmergedTree = true).assertTextEquals("Unconfirmed")
        composeRule.onNodeWithText("Ordinary Input").assertIsDisplayed()
        composeRule.onNodeWithText("Cost Reference (USD)").assertIsDisplayed()
        composeRule.onNodeWithText("Reasoning Reference Estimate (Luna)").assertIsDisplayed()
        composeRule.onNodeWithText("Platform Session Estimate (Voice + Reasoning)").assertIsDisplayed()
        composeRule.onNodeWithText(
            "Both figures are reference estimates only, not a final bill; the platform estimate already includes reasoning, so do not add them together. The Luna estimate covers hosted reasoning tokens only and excludes voice and delegated worker execution.",
        ).assertIsDisplayed()
        composeRule.onNodeWithText('$' + "1.34").assertDoesNotExist()
    }

    @Test
    fun detailsGridAdaptsToNarrowWidthAndLargeFont() {
        val metrics = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics
        val screenDp = metrics.widthPixels / metrics.density
        val width = mutableStateOf(minOf(screenDp, 400f).dp)
        val fontScale = mutableStateOf(1f)
        composeRule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale.value)) {
                OpenCodeTheme {
                    Box(Modifier.requiredWidth(width.value).fillMaxHeight()) {
                        AmbientScreen(
                            snapshot = snapshot(
                                phase = AmbientPhase.LISTENING,
                                inputRms = 0.126,
                                outputRms = 0.124,
                                voiceSeconds = 3723.9,
                                local = AmbientBackendUsage(
                                    inputTokens = 1200,
                                    outputTokens = 1_234_567,
                                    cachedRead = 999,
                                    cacheWrite = 1000,
                                    estimatedUsd = 0.135,
                                    unpricedCount = 0,
                                ),
                                portalUsd = 1.2,
                                portalSettled = true,
                                elapsedSeconds = 90_061L,
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
            }
        }
        composeRule.onNodeWithTag(AmbientTags.DETAILS).performClick()
        composeRule.onNodeWithTag(AmbientTags.VOICE_DURATION, useUnmergedTree = true).assertTextEquals("01:02:03")
        composeRule.onNodeWithTag(AmbientTags.SESSION_DURATION, useUnmergedTree = true).assertTextEquals("25:01:01")
        composeRule.onNodeWithTag(AmbientTags.OUTPUT_TOKENS, useUnmergedTree = true).assertTextEquals("1.23M")
        composeRule.onNodeWithTag(AmbientTags.OUTPUT_TOKENS, useUnmergedTree = true)
            .assertContentDescriptionEquals("1234567")
        composeRule.onNodeWithTag(AmbientTags.LOCAL_ESTIMATE, useUnmergedTree = true).assertTextEquals('$' + "0.14")
        composeRule.onNodeWithTag(AmbientTags.PORTAL_ESTIMATE, useUnmergedTree = true).assertTextEquals('$' + "1.20")
        composeRule.onNodeWithText('$' + "1.34").assertDoesNotExist()
        assertDetailsContentWidth(atLeastDp = 220f, atMostDp = 599f)
        assertLabelsSideBySide("Voice duration", "Session duration")
        assertLabelsSideBySide("Ordinary Input", "Output")
        assertLabelsSideBySide("Cache read", "Cache write")
        assertLabelsSideBySide("Input level", "Output level")
        assertLabelsStacked("Voice duration", "Ordinary Input")
        assertLabelsStacked("Cache read", "Input level")
        assertLabelAboveValue("Reasoning Reference Estimate (Luna)", AmbientTags.LOCAL_ESTIMATE)
        assertWrapped("Reasoning Reference Estimate (Luna)", "Output")
        assertFullWidthNote()
        assertDetailsLeaveControlsVisible()
        captureDetails("details-refresh-normal.png")

        composeRule.runOnIdle { width.value = 160.dp }
        composeRule.waitForIdle()
        assertDetailsContentWidth(atLeastDp = 48f, atMostDp = 200f)
        assertLabelsStacked("Voice duration", "Session duration")
        assertLabelsStacked("Ordinary Input", "Output")
        assertLabelsStacked("Input level", "Output level")
        assertDetailsLeaveControlsVisible()
        captureDetails("details-refresh-narrow.png")

        composeRule.runOnIdle {
            width.value = minOf(screenDp, 400f).dp
            fontScale.value = 1.5f
        }
        composeRule.waitForIdle()
        assertLabelsStacked("Voice duration", "Session duration")
        assertLabelsStacked("Ordinary Input", "Output")
        assertWrapped("Platform Session Estimate (Voice + Reasoning)", "Output")
        assertDetailsLeaveControlsVisible()
        captureDetails("details-refresh-large-font.png")
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
        elapsedSeconds: Long = 30L,
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
        elapsedSeconds = elapsedSeconds,
    )

    private fun labelBounds(label: String): Rect =
        unclippedBounds(composeRule.onNodeWithText(label).fetchSemanticsNode())

    private fun unclippedBounds(node: SemanticsNode): Rect {
        val origin = node.positionInRoot
        return Rect(
            origin.x,
            origin.y,
            origin.x + node.size.width,
            origin.y + node.size.height,
        )
    }

    private fun assertDetailsContentWidth(atLeastDp: Float, atMostDp: Float) {
        val width = heightDp(unclippedBounds(
            composeRule.onNodeWithTag(AmbientTags.DETAILS_BODY, useUnmergedTree = true).fetchSemanticsNode(),
        ).width)
        assertTrue("details width $width dp", width in atLeastDp..atMostDp)
    }

    private fun assertLabelsSideBySide(leftLabel: String, rightLabel: String) {
        val left = labelBounds(leftLabel)
        val right = labelBounds(rightLabel)
        assertEquals("$leftLabel top", left.top, right.top, 2f)
        assertTrue("$leftLabel is left of $rightLabel", left.right <= right.left + 1f)
    }

    private fun assertLabelsStacked(upperLabel: String, lowerLabel: String) {
        val upper = labelBounds(upperLabel)
        val lower = labelBounds(lowerLabel)
        assertTrue("$upperLabel is above $lowerLabel", upper.bottom <= lower.top + 1f)
        assertEquals("$upperLabel left", upper.left, lower.left, 2f)
    }

    private fun assertLabelAboveValue(label: String, valueTag: String) {
        val labelBounds = this.labelBounds(label)
        val value = unclippedBounds(
            composeRule.onNodeWithTag(valueTag, useUnmergedTree = true).fetchSemanticsNode(),
        )
        assertTrue("$label sits above $valueTag", labelBounds.bottom <= value.top + 1f)
        assertTrue(labelBounds.left <= value.left + 2f)
    }

    private fun assertWrapped(longLabel: String, shortLabel: String) {
        val longBounds = labelBounds(longLabel)
        val shortBounds = labelBounds(shortLabel)
        assertTrue("$longLabel wraps", longBounds.height > shortBounds.height * 1.4f)
    }

    private fun assertFullWidthNote() {
        val note = unclippedBounds(
            composeRule.onNodeWithTag(AmbientTags.COST_NOTE, useUnmergedTree = true).fetchSemanticsNode(),
        )
        val body = unclippedBounds(
            composeRule.onNodeWithTag(AmbientTags.DETAILS_BODY, useUnmergedTree = true).fetchSemanticsNode(),
        )
        assertEquals("cost note width", body.width, note.width, 4f)
    }

    private fun assertDetailsLeaveControlsVisible() {
        composeRule.onNodeWithTag(AmbientTags.VOLUME).assertIsDisplayed()
        composeRule.onNodeWithTag(AmbientTags.CONTROLS).assertIsDisplayed()
        val body = bounds(AmbientTags.DETAILS_BODY)
        val volume = bounds(AmbientTags.VOLUME)
        assertTrue("details stay above the volume control", body.bottom <= volume.top + 1f)
        assertTrue("details keep the height cap", heightDp(body.height) <= 280.5f)
    }

    private fun captureDetails(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.filesDir, "ambient-port")
        dir.mkdirs()
        val bitmap = composeRule.onNodeWithTag(AmbientTags.SCREEN).captureToImage().asAndroidBitmap()
        try {
            FileOutputStream(File(dir, name)).use { out ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out))
            }
        } finally {
            bitmap.recycle()
        }
    }

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

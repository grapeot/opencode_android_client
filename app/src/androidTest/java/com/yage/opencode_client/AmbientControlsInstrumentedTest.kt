package com.yage.opencode_client

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
                inputRms = 0.04,
                outputRms = 0.2,
                local = AmbientBackendUsage(
                    inputTokens = 1200,
                    outputTokens = 340,
                    cachedRead = 50,
                    cacheWrite = 10,
                    estimatedUsd = 0.15,
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
        composeRule.onNodeWithTag(AmbientTags.INPUT_LEVEL, useUnmergedTree = true).assertTextEquals("0.04")
        composeRule.onNodeWithTag(AmbientTags.OUTPUT_LEVEL, useUnmergedTree = true).assertTextEquals("0.2")
        composeRule.onNodeWithTag(AmbientTags.LOCAL_ESTIMATE, useUnmergedTree = true).assertTextEquals("0.15")
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
        voiceSeconds = 12.5,
        voiceHasSnapshot = true,
        voiceUnconfirmed = false,
        local = local,
        portalUsd = portalUsd,
        portalSettled = portalSettled,
        usageUnconfirmed = usageUnconfirmed,
        statusMessage = null,
        elapsedSeconds = 30L,
    )
}

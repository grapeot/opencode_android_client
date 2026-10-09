package com.yage.opencode_client

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.yage.opencode_client.ui.AppState
import com.yage.opencode_client.ui.chat.ComposerStatusBar
import org.junit.Rule
import org.junit.Test

class ComposerStatusBarInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun bar(
        stats: AppState.SessionStats? = null,
        isBusy: Boolean = false,
        agentActivityText: String? = null,
        isTranscribing: Boolean = false,
        stopwatchStartedAtMillis: Long? = null,
        stopwatchEndedAtMillis: Long? = null,
    ) {
        composeRule.setContent {
            MaterialTheme {
                ComposerStatusBar(
                    stats = stats,
                    isBusy = isBusy,
                    agentActivityText = agentActivityText,
                    backgroundTaskLabel = null,
                    onOpenBackgroundTask = {},
                    stopwatchStartedAtMillis = stopwatchStartedAtMillis,
                    stopwatchEndedAtMillis = stopwatchEndedAtMillis,
                    isRecording = false,
                    isTranscribing = isTranscribing,
                    hasPreservedSpeechAudio = false,
                    isRetryingSpeech = false,
                    onAbort = {}
                )
            }
        }
    }

    @Test
    fun busyShowsActivityElapsedAndInterruptMenu() {
        // Deterministic frozen pair (no wall-clock race): the reading holds at
        // a fixed 00:34 while the transient activity row still renders.
        val start = 1_700_000_000_000L
        bar(
            isBusy = true,
            stopwatchStartedAtMillis = start,
            stopwatchEndedAtMillis = start + 34_000L,
        )

        composeRule.onNodeWithText("Agent running").assertIsDisplayed()
        composeRule.onNodeWithText("00:34").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Interrupt agent").assertIsDisplayed()
    }

    @Test
    fun stoppedTurnShowsFrozenStopwatchWithoutActivityOrMenu() {
        // Frozen at a fixed start/end: the reading holds and the transient
        // activity row is absent (agent not busy).
        val start = 1_000_000L
        bar(stopwatchStartedAtMillis = start, stopwatchEndedAtMillis = start + 221_000L)

        composeRule.onNodeWithText("03:41").assertIsDisplayed()
        composeRule.onNodeWithText("Agent running").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Interrupt agent").assertDoesNotExist()
    }

    @Test
    fun busyAndTranscribingJoinWithMiddot() {
        val start = 1_700_000_000_000L
        bar(
            isBusy = true,
            isTranscribing = true,
            stopwatchStartedAtMillis = start,
            stopwatchEndedAtMillis = start + 34_000L,
        )

        composeRule.onNodeWithText("Agent running · Transcribing").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Interrupt agent").assertIsDisplayed()
    }

    @Test
    fun countersRenderSegmentsWithActivity() {
        val stats = AppState.SessionStats(rounds = 12, toolCalls = 37, totalTokens = 1_110_000, cacheHitRate = 0.96f)
        bar(stats = stats, isBusy = true, agentActivityText = "Thinking")

        composeRule.onNodeWithText("12").assertIsDisplayed()
        composeRule.onNodeWithText("37").assertIsDisplayed()
        composeRule.onNodeWithText("1.11M tok").assertIsDisplayed()
        composeRule.onNodeWithText("96% cache hit").assertIsDisplayed()
        composeRule.onNodeWithText("Thinking").assertIsDisplayed()
    }

    @Test
    fun idleWithoutCountersIsNotComposed() {
        bar()

        composeRule.onNodeWithText("Agent running").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Interrupt agent").assertDoesNotExist()
    }
}

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
    ) {
        composeRule.setContent {
            MaterialTheme {
                ComposerStatusBar(
                    stats = stats,
                    isBusy = isBusy,
                    agentActivityText = agentActivityText,
                    agentStartedAtMillis = if (isBusy) System.currentTimeMillis() - 34_000L else null,
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
        bar(isBusy = true)

        composeRule.onNodeWithText("Agent running").assertIsDisplayed()
        composeRule.onNodeWithText("0:34").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Interrupt agent").assertIsDisplayed()
    }

    @Test
    fun busyAndTranscribingJoinWithMiddot() {
        bar(isBusy = true, isTranscribing = true)

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

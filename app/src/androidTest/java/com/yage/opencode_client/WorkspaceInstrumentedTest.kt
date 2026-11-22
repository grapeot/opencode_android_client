package com.yage.opencode_client

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class WorkspaceInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun mediumWindowKeepsSessionsNextToChatAndOpensFileBrowser() {
        withWindow("medium") {
            compose.onNodeWithTag("workspace-sessions").assertIsDisplayed()
            compose.onNodeWithTag("workspace-files-column").assertDoesNotExist()
            compose.onNodeWithTag("workspace-files-toggle").performClick()
            compose.onNodeWithTag("workspace-files-browser").assertIsDisplayed()
            val close = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.common_dismiss)
            compose.onNodeWithContentDescription(close).performClick()
            compose.onNodeWithTag("workspace-files-browser").assertDoesNotExist()
            compose.onNodeWithTag("workspace-sessions").assertIsDisplayed()
        }
    }

    @Test fun wideWindowShowsFilesOnlyOnDemand() {
        withWindow("wide") {
            compose.onNodeWithTag("workspace-sessions").assertIsDisplayed()
            compose.onNodeWithTag("workspace-files-column").assertDoesNotExist()
            compose.onNodeWithTag("workspace-files-toggle").performClick()
            compose.onNodeWithTag("workspace-files-column").assertIsDisplayed()
            compose.onNodeWithTag("workspace-files-toggle").performClick()
            compose.onNodeWithTag("workspace-files-column").assertDoesNotExist()
        }
    }

    // Set the emulator's width (600–839dp or ≥840dp), then pass -e workspaceWidth medium|wide.
    private fun withWindow(width: String, test: () -> Unit) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("workspaceWidth") == width)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra("test_server_url", "http://127.0.0.1:9")
            putExtra("test_username", "")
            putExtra("test_password", "")
        }
        ActivityScenario.launch<MainActivity>(intent).use { test() }
    }
}

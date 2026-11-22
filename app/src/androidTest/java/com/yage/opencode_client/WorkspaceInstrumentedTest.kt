package com.yage.opencode_client

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.yage.opencode_client.util.SettingsManager
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class WorkspaceInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun mediumWindowKeepsSessionsNextToChatAndOpensFileBrowser() {
        withWindow("medium") {
            compose.onNodeWithTag("workspace-sessions").assertIsDisplayed()
            compose.onNodeWithTag("workspace-files-column").assertDoesNotExist()
            openLayoutMenu()
            compose.onNodeWithTag("workspace-keep-files-open").assertDoesNotExist()
            compose.onNodeWithTag("workspace-show-files").performClick()
            compose.onNodeWithTag("workspace-files-browser").assertIsDisplayed()
            val close = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.common_dismiss)
            compose.onNodeWithContentDescription(close).performClick()
            compose.onNodeWithTag("workspace-files-browser").assertDoesNotExist()
            compose.onNodeWithTag("workspace-sessions").assertIsDisplayed()
        }
    }

    @Test fun wideWindowShowsFilesOnlyOnDemand() {
        withWindow("wide") { scenario ->
            compose.onNodeWithTag("workspace-sessions").assertIsDisplayed()
            compose.onNodeWithTag("workspace-files-column").assertDoesNotExist()
            openLayoutMenu()
            compose.onNodeWithTag("workspace-show-files").performClick()
            compose.onNodeWithTag("workspace-files-browser").assertIsDisplayed()
            val close = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.common_dismiss)
            compose.onNodeWithContentDescription(close).performClick()
            compose.onNodeWithTag("workspace-files-column").assertDoesNotExist()
            openLayoutMenu()
            compose.onNodeWithTag("workspace-keep-files-open").assertIsOff().performClick()
            compose.onNodeWithTag("workspace-files-column").assertIsDisplayed()
            scenario.recreate()
            compose.onNodeWithTag("workspace-files-column").assertIsDisplayed()
            openLayoutMenu()
            compose.onNodeWithTag("workspace-keep-files-open").assertIsOn().performClick()
            compose.onNodeWithTag("workspace-files-column").assertDoesNotExist()
        }
    }

    private fun openLayoutMenu() {
        val layout = compose.onNodeWithTag("chat-layout").assertIsDisplayed()
        val model = compose.onNodeWithTag("chat-model-menu").assertIsDisplayed()
        org.junit.Assert.assertEquals(model.fetchSemanticsNode().boundsInRoot.center.y,
            layout.fetchSemanticsNode().boundsInRoot.center.y, 2f)
        layout.performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("workspace-show-files").fetchSemanticsNodes().isNotEmpty() }
    }

    // Set the emulator's width (600–839dp or ≥840dp), then pass -e workspaceWidth medium|wide.
    private fun withWindow(width: String, test: (ActivityScenario<MainActivity>) -> Unit) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("workspaceWidth") == width)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra("test_server_url", "http://127.0.0.1:9")
            putExtra("test_username", "")
            putExtra("test_password", "")
        }
        val settings = SettingsManager(context)
        val previous = settings.keepFilesOpen
        settings.keepFilesOpen = false
        try {
            ActivityScenario.launch<MainActivity>(intent).use(test)
        } finally {
            settings.keepFilesOpen = previous
        }
    }
}

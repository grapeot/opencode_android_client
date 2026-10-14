package com.yage.opencode_client

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.yage.opencode_client.data.model.Session
import com.yage.opencode_client.data.model.SessionStatus
import com.yage.opencode_client.ui.session.SessionList
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class SessionListInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test fun mixedIndicatorsLight() = mixedIndicators(false)
    @Test fun mixedIndicatorsDark() = mixedIndicators(true)

    @Test fun disabledSystemAnimationKeepsGreenSteady() {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val resolver = instrumentation.targetContext.contentResolver
        val key = android.provider.Settings.Global.ANIMATOR_DURATION_SCALE
        val previous = android.provider.Settings.Global.getFloat(resolver, key, 1f)
        instrumentation.uiAutomation.adoptShellPermissionIdentity(android.Manifest.permission.WRITE_SECURE_SETTINGS)
        try {
            android.provider.Settings.Global.putFloat(resolver, key, 0f)
            composeRule.setContent {
                com.yage.opencode_client.ui.theme.OpenCodeTheme(darkTheme = false) {
                    com.yage.opencode_client.ui.session.SessionIndicatorDot("disabled", com.yage.opencode_client.ui.session.SessionIndicator.RUNNING)
                }
            }
            val node = composeRule.onNodeWithTag("session-indicator-disabled", useUnmergedTree = true)
            val first = node.captureToImage().toPixelMap()
            val color = first[first.width / 2, first.height / 2]
            composeRule.mainClock.advanceTimeBy(1300)
            val next = node.captureToImage().toPixelMap()
            org.junit.Assert.assertEquals(first.width, next.width)
            org.junit.Assert.assertEquals(first.height, next.height)
            org.junit.Assert.assertEquals(color, next[next.width / 2, next.height / 2])
        } finally {
            android.provider.Settings.Global.putFloat(resolver, key, previous)
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
    }

    private fun mixedIndicators(dark: Boolean) {
        val sessions = listOf(
            Session("parent", directory = "/fixture", title = "Parent + child executing"),
            Session("child", directory = "/fixture", parentId = "parent", title = "Permission pending"),
            Session("running", directory = "/fixture", title = "Execution not finished"),
            Session("failed", directory = "/fixture", title = "Failure needs review"),
            Session("unread", directory = "/fixture", title = "Unread update"),
            Session("unknown", directory = "/fixture", title = "Awaiting sync")
        )
        val presentations = mapOf(
            "parent" to com.yage.opencode_client.ui.session.SessionPresentation(com.yage.opencode_client.ui.session.SessionIndicator.NEEDS_YOU, SessionStatus("busy"), 1, false),
            "running" to com.yage.opencode_client.ui.session.SessionPresentation(com.yage.opencode_client.ui.session.SessionIndicator.RUNNING, SessionStatus("retry"), 0, false),
            "failed" to com.yage.opencode_client.ui.session.SessionPresentation(com.yage.opencode_client.ui.session.SessionIndicator.FAILED, SessionStatus("idle"), 0, false),
            "unread" to com.yage.opencode_client.ui.session.SessionPresentation(com.yage.opencode_client.ui.session.SessionIndicator.UNREAD, SessionStatus("idle"), 0, false),
            "unknown" to com.yage.opencode_client.ui.session.SessionPresentation(null, SessionStatus("unknown"), 0, true)
        )
        composeRule.setContent {
            com.yage.opencode_client.ui.theme.OpenCodeTheme(darkTheme = dark) {
                SessionList(sessions = sessions, currentSessionId = "parent", presentations = presentations,
                    onSelectSession = {}, onCreateSession = {}, onDeleteSession = {})
            }
        }
        composeRule.onNodeWithText("Running · 1 subagent running").assertIsDisplayed()
        composeRule.onNodeWithText("Status awaiting sync").assertIsDisplayed()
        composeRule.onNodeWithTag("session-indicator-running", useUnmergedTree = true).assertContentDescriptionEquals("Running")
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val path = java.io.File(context.getExternalFilesDir(null), "session-indicators-${if (dark) "dark" else "light"}.png")
        path.outputStream().use { output ->
            composeRule.onNodeWithTag("session_list").captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)
        }
    }

    @Test
    fun sessionListCanScrollToLaterSessionsWithoutClientPaging() {
        val sessions = (1..40).map { index ->
            Session(
                id = "session-$index",
                directory = "/tmp/project-$index",
                title = "Session $index"
            )
        }

        composeRule.setContent {
            MaterialTheme {
                SessionList(
                    sessions = sessions,
                    currentSessionId = "session-1",
                    onSelectSession = {},
                    onCreateSession = {},
                    onDeleteSession = {}
                )
            }
        }

        composeRule.onNodeWithTag("session_list")
            .performScrollToNode(hasText("Session 40"))

        composeRule.onNodeWithText("Session 40").assertIsDisplayed()
    }

    @Test
    fun sessionListRequestsMoreFromGlobalLoadOlderAction() {
        val sessions = (1..40).map { index ->
            Session(
                id = "session-$index",
                directory = "/tmp/project-$index",
                title = "Session $index"
            )
        }
        val loadMoreCalls = AtomicInteger(0)

        composeRule.setContent {
            MaterialTheme {
                SessionList(
                    sessions = sessions,
                    currentSessionId = "session-1",
                    hasMoreSessions = true,
                    onSelectSession = {},
                    onCreateSession = {},
                    onDeleteSession = {},
                    onLoadMoreSessions = { loadMoreCalls.incrementAndGet() }
                )
            }
        }

        composeRule.onNodeWithText("Load older").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { loadMoreCalls.get() > 0 }
    }

    @Test
    fun sessionListDoesNotJumpToTopWhenBackgroundRefreshCompletes() {
        val sessions = (1..40).map { index ->
            Session(
                id = "session-$index",
                directory = "/tmp/project-$index",
                title = "Session $index"
            )
        }
        val refreshing = mutableStateOf(false)

        composeRule.setContent {
            MaterialTheme {
                SessionList(
                    sessions = sessions,
                    currentSessionId = "session-1",
                    isRefreshingSessions = refreshing.value,
                    onSelectSession = {},
                    onCreateSession = {},
                    onDeleteSession = {}
                )
            }
        }

        composeRule.onNodeWithTag("session_list")
            .performScrollToNode(hasText("Session 40"))
        composeRule.onNodeWithText("Session 40").assertIsDisplayed()

        composeRule.runOnIdle { refreshing.value = true }
        composeRule.waitForIdle()
        composeRule.runOnIdle { refreshing.value = false }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Session 40").assertIsDisplayed()
    }

    @Test
    fun sessionListCollapseButtonInvokesCallbackWhenProvided() {
        val session = Session(
            id = "session-1",
            directory = "/tmp/project",
            title = "Session 1"
        )
        val collapseCalls = AtomicInteger(0)

        composeRule.setContent {
            MaterialTheme {
                SessionList(
                    sessions = listOf(session),
                    currentSessionId = "session-1",
                    onSelectSession = {},
                    onCreateSession = {},
                    onDeleteSession = {},
                    onCollapseSessions = { collapseCalls.incrementAndGet() }
                )
            }
        }

        composeRule.onNodeWithContentDescription("Hide sessions").assertIsDisplayed().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { collapseCalls.get() == 1 }
    }

    @Test
    fun sessionListSplitsActiveAndArchivedSections() {
        val active = Session(
            id = "active-session",
            directory = "/tmp/project",
            title = "Active Session"
        )
        val archived = Session(
            id = "archived-session",
            directory = "/tmp/project",
            title = "Archived Session",
            time = Session.TimeInfo(archived = 1_000)
        )

        composeRule.setContent {
            MaterialTheme {
                SessionList(
                    sessions = listOf(active, archived),
                    currentSessionId = "active-session",
                    onSelectSession = {},
                    onCreateSession = {},
                    onDeleteSession = {}
                )
            }
        }

        composeRule.onNodeWithText("Active").assertIsDisplayed()
        composeRule.onNodeWithText("Active Session").assertIsDisplayed()
        composeRule.onNodeWithText("Archived").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Archived Session").assertIsDisplayed()
    }

    @Test
    fun sessionListShowsRelativeTimeSubtitleWhenSessionHasUpdatedTime() {
        val session = Session(
            id = "session-with-time",
            directory = "/tmp/project",
            title = "My Session",
            time = Session.TimeInfo(
                created = System.currentTimeMillis() - 3600_000,
                updated = System.currentTimeMillis() - 300_000 // 5 min ago
            )
        )

        composeRule.setContent {
            MaterialTheme {
                SessionList(
                    sessions = listOf(session),
                    currentSessionId = "session-with-time",
                    onSelectSession = {},
                    onCreateSession = {},
                    onDeleteSession = {}
                )
            }
        }

        composeRule.onNodeWithText("My Session").assertIsDisplayed()
        // SessionList renders the relative time via DateUtils with
        // FORMAT_ABBREV_RELATIVE, which produces an abbreviated form like
        // "5 min. ago" — not the spelled-out "minutes ago". Match the
        // abbreviated token (substring) so the assertion reflects what the
        // component actually renders.
        composeRule.onNode(hasText("min", substring = true)).assertIsDisplayed()
    }

    @Test
    fun sessionListShowsStatusLabelWhenSessionHasStatus() {
        val session = Session(
            id = "session-busy",
            directory = "/tmp/project",
            title = "Busy Session"
        )

        composeRule.setContent {
            MaterialTheme {
                SessionList(
                    sessions = listOf(session),
                    currentSessionId = "session-busy",
                    sessionStatuses = mapOf("session-busy" to SessionStatus(type = "busy")),
                    onSelectSession = {},
                    onCreateSession = {},
                    onDeleteSession = {}
                )
            }
        }

        composeRule.onNodeWithText("Busy Session").assertIsDisplayed()
        composeRule.onNodeWithText("Running").assertIsDisplayed()
    }

    @Test
    fun sessionListOmitsIdleStatusLabel() {
        val session = Session(
            id = "session-idle",
            directory = "/tmp/project",
            title = "Idle Session"
        )

        composeRule.setContent {
            MaterialTheme {
                SessionList(
                    sessions = listOf(session),
                    currentSessionId = "session-idle",
                    sessionStatuses = mapOf("session-idle" to SessionStatus(type = "idle")),
                    onSelectSession = {},
                    onCreateSession = {},
                    onDeleteSession = {}
                )
            }
        }

        composeRule.onNodeWithText("Idle Session").assertIsDisplayed()
        composeRule.onNodeWithText("Idle").assertDoesNotExist()
    }

    @Test
    fun sessionListRollsAttentionDotUpToParentAndKeepsRunning() {
        val parent = Session(
            id = "parent",
            directory = "/tmp/project",
            title = "Parent"
        )
        val child = Session(
            id = "child",
            parentId = "parent",
            directory = "/tmp/project",
            title = "Child"
        )

        composeRule.setContent {
            MaterialTheme {
                SessionList(
                    sessions = listOf(parent, child),
                    currentSessionId = "parent",
                    sessionStatuses = mapOf("parent" to SessionStatus(type = "busy")),
                    attentionSessionIds = listOf("child"),
                    onSelectSession = {},
                    onCreateSession = {},
                    onDeleteSession = {}
                )
            }
        }

        composeRule.onNodeWithText("Parent").assertIsDisplayed()
        composeRule.onNodeWithText("Need attention").assertDoesNotExist()
        composeRule.onNodeWithText("Running").assertIsDisplayed()
        composeRule.onNodeWithTag("session-indicator-parent", useUnmergedTree = true)
            .assertIsDisplayed()
            .assertContentDescriptionEquals("Needs your attention")
    }

    @Test
    fun sessionListRendersAllThreeIndicatorDescriptions() {
        val sessions = listOf(
            Session("needs-you", directory = "/tmp", title = "Needs You"),
            Session("failed", directory = "/tmp", title = "Failed"),
            Session("unread", directory = "/tmp", title = "New Content")
        )
        composeRule.setContent {
            MaterialTheme {
                SessionList(
                    sessions = sessions,
                    currentSessionId = null,
                    attentionSessionIds = listOf("needs-you"),
                    sessionStatuses = sessions.associate { it.id to SessionStatus("idle") },
                    unreadCounts = mapOf("needs-you" to 1, "failed" to 1, "unread" to 1),
                    failureCounts = mapOf("needs-you" to 1, "failed" to 1),
                    onSelectSession = {},
                    onCreateSession = {},
                    onDeleteSession = {}
                )
            }
        }
        for ((id, description) in listOf(
            "needs-you" to "Needs your attention",
            "failed" to "Last turn failed",
            "unread" to "Unread"
        )) {
            composeRule.onNodeWithTag("session-indicator-$id", useUnmergedTree = true)
                .assertIsDisplayed()
                .assertContentDescriptionEquals(description)
        }
    }
}

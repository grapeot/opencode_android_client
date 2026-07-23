package com.yage.opencode_client

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
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
    fun sessionListShowsIdleStatusLabel() {
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
        composeRule.onNodeWithText("Idle").assertIsDisplayed()
    }

    @Test
    fun sessionListRollsNeedAttentionUpToParentAndOverridesRunning() {
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
        composeRule.onNodeWithText("Need attention").assertIsDisplayed()
        composeRule.onNodeWithText("Running").assertDoesNotExist()
    }
}

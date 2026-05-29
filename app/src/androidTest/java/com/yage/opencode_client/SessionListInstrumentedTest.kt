package com.yage.opencode_client

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
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
    fun sessionListRequestsMoreWhenScrolledNearBottom() {
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

        composeRule.onNodeWithTag("session_list")
            .performScrollToNode(hasText("Session 40"))

        composeRule.waitUntil(timeoutMillis = 5_000) { loadMoreCalls.get() > 0 }
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
}

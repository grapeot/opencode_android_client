package com.yage.opencode_client

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.yage.opencode_client.data.model.MessageWithParts
import com.yage.opencode_client.data.repository.OpenCodeRepository
import com.yage.opencode_client.ui.chat.ChatMessageList
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Layer 3 (integration-UI): the real end-to-end contract that a read tool call
 * coming from a real server renders as a read card in the real render path.
 *
 * Flow: configure the repository against the real local OpenCode server ->
 * create a session -> send a prompt that reliably triggers a `read` tool call
 * -> poll the session's messages until a read tool part appears -> feed those
 * REAL messages into the REAL ChatMessageList composable -> assert the semantics
 * tree contains a read card (testTag "toolcard.read.*").
 *
 * Why this shape:
 *  - The server has no API to inject a pre-formed tool call; you can only send a
 *    text prompt and let the model call the tool. So data is produced by a
 *    high-probability prompt ("read this file"), not constructed. read is safe:
 *    it does not write the workspace (4096/4097 share the real cwd).
 *  - It exercises real auth, real session, real prompt, real model-produced tool
 *    call, and the real MessageRow -> ToolCardClassifier.split -> FileCard chain.
 *  - Non-determinism is absorbed with assumeTrue: if the server is down or the
 *    model did not emit a read part within the timeout, the test SKIPS rather
 *    than fails, so it never flakes red. A green run is a real confirmation.
 *
 * Credentials come from .env via instrumentation args (same chain as
 * OpenCodeIntegrationTest). For the emulator, OPENCODE_SERVER_URL must use
 * 10.0.2.2 (the host alias), not localhost.
 *
 * Run: ./gradlew connectedDebugAndroidTest \
 *        -Pandroid.testInstrumentationRunnerArguments.class=com.yage.opencode_client.ReadToolCardIntegrationTest
 */
class ReadToolCardIntegrationTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var repository: OpenCodeRepository
    private var serverUrl: String = ""
    private var username: String = ""
    private var password: String = ""
    private var createdSessionId: String? = null

    @Before
    fun setup() {
        val args = InstrumentationRegistry.getArguments()
        serverUrl = args.getString("openCodeServerUrl") ?: ""
        username = args.getString("openCodeUsername") ?: ""
        password = args.getString("openCodePassword") ?: ""

        assumeTrue(
            "Skipping: no OpenCode server in OPENCODE_SERVER_URL (use 10.0.2.2 for emulator)",
            serverUrl.isNotBlank()
        )

        repository = OpenCodeRepository()
        repository.configure(
            baseUrl = serverUrl,
            username = username.ifEmpty { null },
            password = password.ifEmpty { null }
        )

        assumeTrue(
            "Skipping: OpenCode server not reachable at $serverUrl",
            runBlocking { repository.checkHealth().isSuccess }
        )
    }

    @After
    fun tearDown() {
        val id = createdSessionId ?: return
        runBlocking { repository.deleteSession(id) }
    }

    @Test
    fun readToolCallFromServerRendersAsReadCard() {
        // The agent/model is configurable and has no default: set OPENCODE_AGENT in
        // .env. Unconfigured -> skip with a warning, rather than defaulting to an
        // agent the server may not be able to run. Optionally override the model so
        // a runnable agent uses a fast/cheap model (OPENCODE_MODEL_PROVIDER + _ID),
        // e.g. build + deepseek/deepseek-v4-flash (~2s) instead of the local ds4.
        val args = InstrumentationRegistry.getArguments()
        val agent = args.getString("openCodeAgent")
            ?.takeIf { it.isNotBlank() }
            ?: skip("OPENCODE_AGENT not set in .env — set it to a runnable agent " +
                "(GET /agent lists them; ds4 is the local model)")
        val modelProvider = args.getString("openCodeModelProvider")?.takeIf { it.isNotBlank() }
        val modelId = args.getString("openCodeModelId")?.takeIf { it.isNotBlank() }
        val model = if (modelProvider != null && modelId != null) {
            com.yage.opencode_client.data.model.Message.ModelInfo(modelProvider, modelId)
        } else null

        val messages: List<MessageWithParts> = runBlocking {
            val session = repository.createSession(title = "ui-test-read-card")
                .getOrElse { skip("could not create session: $it") }
            createdSessionId = session.id

            // A prompt that all but guarantees a single read tool call and nothing
            // that writes the workspace.
            val prompt = "Read the file AGENTS.md and reply with only its first line. " +
                "Do not create, edit, or write any file."
            repository.sendMessage(sessionId = session.id, text = prompt, agent = agent, model = model)
                .getOrElse { skip("could not send prompt (agent=$agent, model=$model): $it") }

            // Poll until a read tool part shows up, or time out.
            pollForReadPart(session.id, timeoutMs = 90_000)
        }

        // Distinguish the skip reasons so a skipped run says *why* — a test that
        // silently always skips proves nothing. A real model-backend failure
        // (e.g. invalid API key) shows up here as a server-side error part.
        val hasRead = messages.flatMap { it.parts }.any { it.isTool && isReadTool(it.tool) }
        if (!hasRead) {
            val serverError = messages.firstNotNullOfOrNull { it.info.error }
            if (serverError != null) {
                skip("model backend errored before any tool call: $serverError")
            } else {
                skip("model (agent=$agent) did not emit a read tool call within the timeout")
            }
        }

        composeRule.setContent {
            MaterialTheme {
                ChatMessageList(
                    messages = messages,
                    streamingPartTexts = emptyMap(),
                    streamingReasoningPart = null,
                    isLoading = false,
                    messageLimit = 200,
                    repository = repository,
                    workspaceDirectory = null,
                    completedTurnActivities = emptyList(),
                    onLoadMore = {},
                    onFileClick = {},
                    onForkFromMessage = {},
                    onEditFromMessage = {}
                )
            }
        }
        composeRule.waitForIdle()

        // Assert the semantics tree contains at least one read card. The card's
        // testTag is "toolcard.read.<basename>"; match by prefix so the test does
        // not depend on which file the model happened to read.
        val readCards = composeRule.onAllNodes(hasTestTagPrefix("toolcard.read."))
            .fetchSemanticsNodes()
        assertTrue(
            "Expected at least one read card (toolcard.read.*) in the rendered session",
            readCards.isNotEmpty()
        )
    }

    /** Log the reason to logcat (survives the post-run APK uninstall) and skip. */
    private fun skip(reason: String): Nothing {
        Log.w(TAG, "SKIP: $reason")
        assumeTrue("Skipping: $reason", false)
        error("unreachable") // assumeTrue(false) throws; satisfies the Nothing return
    }

    private suspend fun pollForReadPart(sessionId: String, timeoutMs: Long): List<MessageWithParts> {
        val deadline = System.currentTimeMillis() + timeoutMs
        var latest: List<MessageWithParts> = emptyList()
        while (System.currentTimeMillis() < deadline) {
            val result = repository.getMessages(sessionId)
            if (result.isSuccess) {
                latest = result.getOrThrow()
                if (latest.flatMap { it.parts }.any { it.isTool && isReadTool(it.tool) }) {
                    return latest
                }
            }
            delay(2_000)
        }
        return latest
    }

    private fun isReadTool(tool: String?): Boolean {
        val t = tool?.lowercase() ?: return false
        return t.startsWith("read_file") || t.startsWith("read")
    }

    private fun hasTestTagPrefix(prefix: String): SemanticsMatcher =
        SemanticsMatcher("testTag starts with $prefix") { node ->
            node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true
        }

    private companion object {
        const val TAG = "ReadToolCardUITest"
    }
}

package com.yage.opencode_client

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yage.voiceflowkit.VoiceFlowClient
import com.yage.voiceflowkit.VoiceFlowConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AIBuildersIntegrationTest {

    private var baseURL: String = ""
    private var token: String = ""

    @Before
    fun setup() {
        val args = InstrumentationRegistry.getArguments()
        baseURL = args.getString("aiBuilderBaseUrl") ?: ""
        token = args.getString("aiBuilderToken") ?: ""
        assumeTrue(
            "Skipping: AI Builder credentials not provided in .env",
            baseURL.isNotBlank() && token.isNotBlank()
        )
    }

    private fun client(authToken: String): VoiceFlowClient =
        VoiceFlowClient(
            VoiceFlowConfig(
                endpoint = baseURL,
                tokenProvider = { authToken },
            )
        )

    @Test
    fun testConnectionSucceeds() = runBlocking {
        // VoiceFlowClient.testConnection throws on failure; no throw == success.
        val result = runCatching { client(token).testConnection() }
        assertTrue(
            "testConnection should succeed: ${result.exceptionOrNull()?.message}",
            result.isSuccess
        )
    }

    @Test
    fun testConnectionFailsWithBadToken() = runBlocking {
        val result = runCatching { client("invalid-token-12345").testConnection() }
        assertTrue("testConnection should fail with bad token", result.isFailure)
    }
}

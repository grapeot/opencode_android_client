package com.yage.opencode_client

import com.yage.opencode_client.util.OpenCodeDeepLink
import com.yage.opencode_client.util.OpenCodeDeepLinkParseResult
import com.yage.opencode_client.util.OpenCodeDeepLinkParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCodeDeepLinkParserTest {
    @Test
    fun parsesSessionLinkAndNormalizesSchemeAndHostCase() {
        assertEquals(
            OpenCodeDeepLinkParseResult.Success(OpenCodeDeepLink.Session("ses_example-123")),
            OpenCodeDeepLinkParser.parse("opencode://session/ses_example-123")
        )
        assertEquals(
            OpenCodeDeepLinkParseResult.Success(OpenCodeDeepLink.Session("ses_ABC")),
            OpenCodeDeepLinkParser.parse("OPENCODE://SESSION/ses_ABC")
        )
    }

    @Test
    fun acceptsOnePercentDecodeAndRejectsRepeatedEncoding() {
        assertEquals(
            OpenCodeDeepLinkParseResult.Success(OpenCodeDeepLink.Session("ses_example")),
            OpenCodeDeepLinkParser.parse("opencode://session/ses_%65xample")
        )
        assertInvalid("opencode://session/ses_%252e%252e")
    }

    @Test
    fun rejectsMalformedOrExpandedSessionActions() {
        listOf(
            "opencode://other/ses_example",
            "opencode://session",
            "opencode://session/ses_",
            "opencode://session/not_a_session",
            "opencode://session/ses_example/",
            "opencode://session/ses_one/extra",
            "opencode://user@session/ses_example",
            "opencode://session:4096/ses_example",
            "opencode://session/ses_example?message=msg_1",
            "opencode://session/ses_example#fragment",
            "opencode://session/ses_%2Fextra",
            "opencode://session/ses_你好"
        ).forEach(::assertInvalid)
    }

    @Test
    fun rejectsUnsupportedSchemeAndOverlongId() {
        assertEquals(
            OpenCodeDeepLinkParseResult.UnsupportedScheme,
            OpenCodeDeepLinkParser.parse("https://session/ses_example")
        )
        assertInvalid("opencode://session/ses_${"a".repeat(300)}")
        assertTrue(OpenCodeDeepLinkParser.handles("opencode://session/ses_example"))
    }

    private fun assertInvalid(rawUrl: String) {
        assertEquals(
            "Expected invalid deep link: $rawUrl",
            OpenCodeDeepLinkParseResult.InvalidSessionLink,
            OpenCodeDeepLinkParser.parse(rawUrl)
        )
    }
}

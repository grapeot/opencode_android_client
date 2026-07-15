package com.yage.opencode_client.util

import java.net.URI

sealed interface OpenCodeDeepLink {
    data class Session(val id: String) : OpenCodeDeepLink
}

sealed interface OpenCodeDeepLinkParseResult {
    data class Success(val deepLink: OpenCodeDeepLink) : OpenCodeDeepLinkParseResult
    data object UnsupportedScheme : OpenCodeDeepLinkParseResult
    data object InvalidSessionLink : OpenCodeDeepLinkParseResult
}

object OpenCodeDeepLinkParser {
    const val SCHEME = "opencode"
    private const val SESSION_HOST = "session"
    private const val MAX_SESSION_ID_LENGTH = 256

    fun handles(rawUrl: String): Boolean {
        return runCatching { URI(rawUrl).scheme?.equals(SCHEME, ignoreCase = true) == true }
            .getOrDefault(false)
    }

    fun parse(rawUrl: String): OpenCodeDeepLinkParseResult {
        val uri = runCatching { URI(rawUrl) }.getOrNull()
            ?: return OpenCodeDeepLinkParseResult.InvalidSessionLink
        if (!uri.scheme.equals(SCHEME, ignoreCase = true)) {
            return OpenCodeDeepLinkParseResult.UnsupportedScheme
        }
        if (!uri.host.equals(SESSION_HOST, ignoreCase = true) ||
            uri.userInfo != null ||
            uri.port != -1 ||
            uri.rawQuery != null ||
            uri.rawFragment != null
        ) {
            return OpenCodeDeepLinkParseResult.InvalidSessionLink
        }

        val rawPath = uri.rawPath ?: return OpenCodeDeepLinkParseResult.InvalidSessionLink
        if (!rawPath.startsWith('/') || rawPath.length <= 1 || rawPath.drop(1).contains('/')) {
            return OpenCodeDeepLinkParseResult.InvalidSessionLink
        }
        val decodedPath = uri.path ?: return OpenCodeDeepLinkParseResult.InvalidSessionLink
        if (!decodedPath.startsWith('/') || decodedPath.drop(1).contains('/')) {
            return OpenCodeDeepLinkParseResult.InvalidSessionLink
        }
        val sessionId = decodedPath.drop(1)
        if (!isValidSessionId(sessionId)) {
            return OpenCodeDeepLinkParseResult.InvalidSessionLink
        }
        return OpenCodeDeepLinkParseResult.Success(OpenCodeDeepLink.Session(sessionId))
    }

    private fun isValidSessionId(value: String): Boolean {
        if (!value.startsWith("ses_") || value.length <= 4 || value.length > MAX_SESSION_ID_LENGTH) {
            return false
        }
        return value.all { character ->
            character in 'a'..'z' ||
                character in 'A'..'Z' ||
                character in '0'..'9' ||
                character == '_' ||
                character == '-'
        }
    }
}

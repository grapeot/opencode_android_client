package com.yage.opencode_client.ui.files

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

object WorkspaceMarkdownLinkResolver {
    sealed interface Resolution {
        data class External(val url: String) : Resolution
        data class Preview(val path: String) : Resolution
        data object Ignored : Resolution
        data class Rejected(val message: String) : Resolution
    }

    fun resolve(
        href: String,
        workspaceDirectory: String?,
        sourceMarkdownPath: String? = null
    ): Resolution {
        val raw = href.trim()
        if (raw.isEmpty() || raw.startsWith("#")) return Resolution.Ignored

        val scheme = Regex("^([A-Za-z][A-Za-z0-9+.-]*):").find(raw)?.groupValues?.getOrNull(1)?.lowercase()
        return when (scheme) {
            "http", "https" -> Resolution.External(raw)
            "file" -> resolveFileUri(raw, workspaceDirectory)
            null -> resolveWorkspacePath(raw, workspaceDirectory, sourceMarkdownPath)
            else -> Resolution.Rejected("Unsupported link scheme: $scheme")
        }
    }

    private fun resolveFileUri(raw: String, workspaceDirectory: String?): Resolution {
        val uri = runCatching { URI(raw) }.getOrElse {
            return Resolution.Rejected("Invalid file link")
        }
        val authority = uri.rawAuthority.orEmpty()
        if (authority.isNotEmpty() && !authority.equals("localhost", ignoreCase = true)) {
            return Resolution.Rejected("Only local file:// links are supported")
        }
        if (uri.rawQuery != null) return Resolution.Rejected("File links with query are not supported")
        val rawPath = uri.rawPath.orEmpty()
        if (rawPath.isEmpty()) return if (uri.rawFragment != null) Resolution.Ignored else Resolution.Rejected("File link has no path")
        return resolveAbsolutePath(decodePercentRepeated(rawPath), workspaceDirectory)
    }

    private fun resolveWorkspacePath(raw: String, workspaceDirectory: String?, sourceMarkdownPath: String?): Resolution {
        if (workspaceDirectory.isNullOrBlank()) return Resolution.Rejected("No workspace is available for this link")
        val pathPart = raw.substringBefore('#')
        if (pathPart.isBlank()) return Resolution.Ignored
        if ('?' in pathPart) return Resolution.Rejected("File links with query are not supported")
        val decodedPath = decodePercentRepeated(pathPart)
        if (decodedPath != pathPart && hasTraversal(decodedPath)) {
            return Resolution.Rejected("Encoded parent traversal is not allowed")
        }
        if (decodedPath.startsWith('/')) return resolveAbsolutePath(decodedPath, workspaceDirectory)
        if ('\\' in decodedPath) return Resolution.Rejected("Workspace link escapes the workspace")
        val baseDir = sourceMarkdownPath
            ?.let { resolveRelativePreviewPath(it, workspaceDirectory) }
            ?.substringBeforeLast('/', missingDelimiterValue = "")
            .orEmpty()
        val output = baseDir.split('/').filter { it.isNotBlank() }.toMutableList()
        for (segment in decodedPath.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> {
                    if (output.isEmpty()) return Resolution.Rejected("Workspace link escapes the workspace")
                    output.removeAt(output.lastIndex)
                }
                else -> output += segment
            }
        }
        return Resolution.Preview(output.joinToString("/"))
    }

    private fun resolveAbsolutePath(path: String, workspaceDirectory: String?): Resolution {
        val workspace = workspaceDirectory?.trimEnd('/')?.takeIf { it.startsWith('/') }
            ?: return Resolution.Rejected("No workspace is available for this link")
        if (hasTraversal(path)) return Resolution.Rejected("Workspace link escapes the workspace")
        return when {
            path == workspace -> Resolution.Preview("")
            path.startsWith("$workspace/") -> Resolution.Preview(path.removePrefix("$workspace/").trimStart('/'))
            else -> Resolution.Rejected("Path is outside the workspace")
        }
    }

    private fun hasTraversal(path: String): Boolean {
        if ('\\' in path) return true
        return path.split('/').any { it == ".." }
    }

    private fun decodePercentRepeated(input: String): String {
        var current = input
        repeat(3) {
            val decoded = runCatching {
                URLDecoder.decode(current.replace("+", "%2B"), StandardCharsets.UTF_8.name())
            }.getOrElse { return current }
            if (decoded == current) return current
            current = decoded
        }
        return current
    }
}

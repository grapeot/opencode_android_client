package com.yage.opencode_client.ui.util

import com.yage.opencode_client.data.model.FileContent
import java.nio.file.Paths

object MarkdownImageResolver {

    private val unixAbsoluteRoots = setOf("Users", "private", "var", "tmp", "home", "opt", "etc", "Volumes")

    private val imageExtensions = setOf(
        "png", "jpg", "jpeg", "gif", "webp", "bmp", "tiff", "tif", "heic", "heif", "ico", "svg"
    )

    private val imagePattern = Regex("""!\[([^\]]*)\]\(([^)]+)\)""")

    fun normalizeStandaloneImageBlocks(text: String): String {
        val lines = text.split('\n')
        if (lines.size <= 1) return text

        val normalized = ArrayList<String>(lines.size)
        for (index in lines.indices) {
            val line = lines[index]
            normalized.add(line)

            if (!isStandaloneMarkdownImageLine(line)) continue
            if (index + 1 >= lines.size) continue

            val nextLine = lines[index + 1]
            if (nextLine.trim().isNotEmpty()) {
                normalized.add("")
            }
        }

        return normalized.joinToString("\n")
    }

    suspend fun resolveImages(
        text: String,
        markdownFilePath: String? = null,
        workspaceDirectory: String? = null,
        fetchContent: suspend (String) -> FileContent
    ): String {
        val matches = imagePattern.findAll(text).toList()
        if (matches.isEmpty()) return text

        var result = text
        for (match in matches.asReversed()) {
            val alt = match.groupValues[1]
            val rawUrl = match.groupValues[2].trim().trim('<', '>')
            if (rawUrl.isEmpty() || rawUrl.startsWith("data:") || rawUrl.contains("://")) continue

            val normalizedPath = normalizeImagePath(rawUrl, markdownFilePath, workspaceDirectory) ?: continue
            val ext = normalizedPath.substringAfterLast('.', "").lowercase()
            if (ext !in imageExtensions) continue

            try {
                val content = fetchContent(normalizedPath)
                val base64Data = content.content?.takeIf { it.isNotBlank() } ?: continue
                val mimeType = mimeTypeForExtension(ext)
                val cleaned = base64Data.replace("\n", "").replace("\r", "").replace(" ", "")
                result = result.replaceRange(match.range, "![${alt}](data:${mimeType};base64,${cleaned})")
            } catch (_: Exception) {
                continue
            }
        }

        return result
    }

    internal fun normalizeImagePath(
        rawUrl: String,
        markdownFilePath: String? = null,
        workspaceDirectory: String? = null
    ): String? {
        if (rawUrl.isBlank() || rawUrl.startsWith("data:") || rawUrl.contains("://")) return null

        val withoutFragment = rawUrl.substringBefore('#').substringBefore('?').trim()
        if (withoutFragment.isBlank()) return null

        val resolved = when {
            withoutFragment.startsWith("/") && workspaceDirectory.isNullOrBlank() -> withoutFragment.trimStart('/')
            withoutFragment.startsWith("/") -> relativizeAgainstWorkspace(withoutFragment, workspaceDirectory)
            markdownFilePath.isNullOrBlank() -> withoutFragment
            else -> {
                val baseDirectory = Paths.get(markdownFilePath).parent
                val combined = (baseDirectory ?: Paths.get("")).resolve(withoutFragment).normalize().toString()
                relativizeAgainstWorkspace(combined, workspaceDirectory)
            }
        }

        return resolved
            ?.replace('\\', '/')
            ?.let { restoreUnixAbsolutePathIfNeeded(it, markdownFilePath, workspaceDirectory) }
            ?.let { normalized ->
                if (workspaceDirectory.isNullOrBlank() && normalized.startsWith("/")) normalized else normalized.trimStart('/')
            }
            ?.takeIf { it.isNotBlank() }
    }

    private fun restoreUnixAbsolutePathIfNeeded(
        path: String,
        markdownFilePath: String?,
        workspaceDirectory: String?
    ): String {
        if (!workspaceDirectory.isNullOrBlank()) return path
        if (path.startsWith("/")) return path
        if (path.matches(Regex("^[A-Za-z]:[/\\\\].*"))) return path

        val firstSegment = path.substringBefore('/')
        val contextFirstSegment = markdownFilePath
            ?.replace('\\', '/')
            ?.trimStart('/')
            ?.substringBefore('/')

        return if (firstSegment in unixAbsoluteRoots && contextFirstSegment == firstSegment) {
            "/$path"
        } else {
            path
        }
    }

    private fun relativizeAgainstWorkspace(path: String, workspaceDirectory: String?): String? {
        if (workspaceDirectory.isNullOrBlank()) return path
        return try {
            val workspacePath = Paths.get(workspaceDirectory).normalize()
            val targetPath = if (Paths.get(path).isAbsolute) {
                Paths.get(path).normalize()
            } else {
                workspacePath.resolve(path).normalize()
            }
            workspacePath.relativize(targetPath).toString()
        } catch (_: Exception) {
            path
        }
    }

    private fun mimeTypeForExtension(extension: String): String {
        return when (extension) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            "tiff", "tif" -> "image/tiff"
            "heic" -> "image/heic"
            "heif" -> "image/heif"
            "ico" -> "image/x-icon"
            "svg" -> "image/svg+xml"
            else -> "image/*"
        }
    }

    private fun isStandaloneMarkdownImageLine(line: String): Boolean {
        val trimmed = line.trim()
        if (!trimmed.startsWith("![") || !trimmed.endsWith(")")) return false
        val closeAlt = trimmed.indexOf(']')
        if (closeAlt < 0 || closeAlt + 1 >= trimmed.length) return false
        val afterAlt = trimmed.substring(closeAlt + 1)
        return afterAlt.startsWith("(") && afterAlt.length > 1
    }
}

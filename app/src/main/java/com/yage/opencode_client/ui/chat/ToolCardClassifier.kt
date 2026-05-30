package com.yage.opencode_client.ui.chat

import com.yage.opencode_client.data.model.Part

/**
 * Classification logic for the "tool card render redo": which parts render as
 * file cards (2-column grid) vs. collapse into a single "N tool calls" row.
 * Extracted so the behavior is unit-testable without Compose. Ported from iOS
 * ToolCardClassifier.swift, with one Android-specific tweak: a `patch` only
 * counts as a file operation when it carries navigable file paths
 * (filePathsForNavigationFiltered), so a "pathless patch" doesn't fall out of
 * the grid into nowhere.
 */
object ToolCardClassifier {
    /**
     * A "file operation" is a patch part (with navigable paths), or a tool whose
     * name matches one of the file-op verbs. Loose prefix match so aliases like
     * "edit"/"write"/"read"/"patch" and full forms like "edit_file"/"apply_patch"
     * all count.
     */
    val fileOpToolPrefixes = listOf(
        "apply_patch", "edit_file", "write_file", "read_file",
        "patch", "edit", "write", "read",
    )

    fun isFileOperation(part: Part): Boolean {
        if (part.isPatch && part.filePathsForNavigationFiltered.isNotEmpty()) return true
        if (!part.isTool) return false
        val tool = part.tool?.lowercase() ?: return false
        return fileOpToolPrefixes.any { tool.startsWith(it) }
    }

    /**
     * `read` tool names (and aliases) — a directory read can only come from one of
     * these, never from edit/write/patch.
     */
    val readToolPrefixes = listOf("read_file", "read")

    /**
     * True when this part is a `read` whose tool output reports a directory.
     * The server embeds `<type>directory</type>` in the read output for a folder
     * (vs. `<type>file</type>` for a file).
     */
    fun isDirectoryRead(part: Part): Boolean {
        if (!part.isTool) return false
        val tool = part.tool?.lowercase() ?: return false
        if (readToolPrefixes.none { tool.startsWith(it) }) return false
        val output = part.toolOutput ?: return false
        return output.contains("<type>directory</type>")
    }

    /** One entry in a directory read: a child file or subdirectory. */
    data class DirectoryEntry(
        val name: String,
        val isDirectory: Boolean
    )

    /**
     * Parse the `<entries>…</entries>` block out of a directory read's output.
     * Each line is a child name; names ending in "/" are subdirectories. Blank
     * lines and trailing summary lines like "(12 entries)" are dropped. The
     * display name keeps the trailing "/" stripped so the UI can render it
     * uniformly with its own icon.
     */
    fun parseDirectoryEntries(output: String?): List<DirectoryEntry> {
        if (output == null) return emptyList()
        val openIdx = output.indexOf("<entries>")
        if (openIdx < 0) return emptyList()
        val afterOpen = output.substring(openIdx + "<entries>".length)
        val closeIdx = afterOpen.indexOf("</entries>")
        val body = if (closeIdx >= 0) afterOpen.substring(0, closeIdx) else afterOpen

        val entries = mutableListOf<DirectoryEntry>()
        for (rawLine in body.split("\n")) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            // Drop summary lines such as "(12 entries)" or "(0 entries)".
            if (line.startsWith("(") && line.endsWith("entries)")) continue
            val isDir = line.endsWith("/")
            val name = if (isDir) line.dropLast(1) else line
            if (name.isEmpty()) continue
            entries.add(DirectoryEntry(name = name, isDirectory = isDir))
        }
        return entries
    }

    /**
     * Split a buffered run of tool/patch parts into the file-card group (grid) and
     * the "other tools" group (merged into one "N tool calls" disclosure row).
     */
    fun split(parts: List<Part>): Pair<List<Part>, List<Part>> {
        val fileParts = mutableListOf<Part>()
        val otherParts = mutableListOf<Part>()
        for (part in parts) {
            if (isFileOperation(part)) {
                fileParts.add(part)
            } else {
                otherParts.add(part)
            }
        }
        return Pair(fileParts, otherParts)
    }

    /** Number shown in the merged "N tool calls" row (count of non-file tools). */
    fun toolCallsCount(parts: List<Part>): Int =
        parts.count { !isFileOperation(it) }
}

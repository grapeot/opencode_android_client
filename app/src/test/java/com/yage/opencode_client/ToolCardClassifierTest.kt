package com.yage.opencode_client

import com.yage.opencode_client.data.model.MessageWithParts
import com.yage.opencode_client.data.model.Part
import com.yage.opencode_client.ui.chat.ToolCardClassifier
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

/**
 * Behavior guard for the "tool card render redo": which parts become file cards
 * (2-column grid) vs. which collapse into the merged "N tool calls" row, plus
 * directory-read detection and entry parsing. Ported from iOS
 * ToolCardClassifierTests.swift.
 *
 * Parts are built by decoding JSON so the test exercises the real model exactly
 * as the server would feed it (type/tool/state/metadata all flow through the
 * kotlinx.serialization decoders, including the custom PartState serializer).
 *
 * Android-specific: a `patch` only counts as a file operation when it carries a
 * navigable file path (filePathsForNavigationFiltered), so patch fixtures here
 * include a metadata path with a file extension.
 */
class ToolCardClassifierTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /** Decode a single Part out of a one-part MessageWithParts payload. */
    private fun decodePart(partJson: String): Part {
        val payload = """
            [{"info":{"id":"msg_1","role":"assistant","sessionID":"ses_1"},"parts":[$partJson]}]
        """.trimIndent()
        return json.decodeFromString<List<MessageWithParts>>(payload)[0].parts[0]
    }

    private fun toolPart(id: String = "p1", tool: String? = null, path: String? = null): Part {
        val sb = StringBuilder("""{"type":"tool","id":"$id","sessionID":"ses_1","messageID":"msg_1"""")
        if (tool != null) sb.append(""","tool":"$tool"""")
        if (path != null) sb.append(""","metadata":{"path":"$path"}""")
        sb.append("}")
        return decodePart(sb.toString())
    }

    /** A patch with a navigable file path (required on Android to be a file op). */
    private fun patchPart(id: String = "p1", path: String = "src/a.ts"): Part =
        decodePart("""{"type":"patch","id":"$id","sessionID":"ses_1","messageID":"msg_1","metadata":{"path":"$path"}}""")

    private fun readPart(tool: String = "read", output: String): Part {
        // Embed output via object state so Part.toolOutput resolves to state.output.
        val escaped = json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive(output))
        return decodePart("""{"type":"tool","id":"d1","sessionID":"ses_1","messageID":"msg_1","tool":"$tool","state":{"status":"completed","output":$escaped}}""")
    }

    // MARK: - isFileOperation

    @Test
    fun `patch part with path is file operation`() {
        assertTrue(ToolCardClassifier.isFileOperation(patchPart()))
    }

    @Test
    fun `patch part without navigable path is not file operation`() {
        // Android-specific: a pathless patch (or one with only a directory path)
        // must not be treated as a file op, else it falls out of the grid.
        val noPath = decodePart("""{"type":"patch","id":"p1","sessionID":"ses_1","messageID":"msg_1"}""")
        assertFalse(ToolCardClassifier.isFileOperation(noPath))
        val dirOnly = decodePart("""{"type":"patch","id":"p2","sessionID":"ses_1","messageID":"msg_1","metadata":{"path":"some_dir"}}""")
        assertFalse(ToolCardClassifier.isFileOperation(dirOnly))
    }

    @Test
    fun `canonical file tools`() {
        for (tool in listOf("apply_patch", "edit_file", "write_file", "read_file")) {
            assertTrue("$tool should be a file operation", ToolCardClassifier.isFileOperation(toolPart(tool = tool)))
        }
    }

    @Test
    fun `alias file tools via prefix`() {
        for (tool in listOf("edit", "write", "read", "patch")) {
            assertTrue("alias $tool should be a file operation", ToolCardClassifier.isFileOperation(toolPart(tool = tool)))
        }
    }

    @Test
    fun `tool name match is case insensitive`() {
        assertTrue(ToolCardClassifier.isFileOperation(toolPart(tool = "Edit_File")))
    }

    @Test
    fun `non file tools are not file operations`() {
        for (tool in listOf("bash", "grep", "glob", "list", "webfetch", "task", "todowrite")) {
            assertFalse("$tool should NOT be a file operation", ToolCardClassifier.isFileOperation(toolPart(tool = tool)))
        }
    }

    @Test
    fun `text part is not file operation`() {
        val part = decodePart("""{"type":"text","id":"p1","sessionID":"ses_1","messageID":"msg_1","text":"hi"}""")
        assertFalse(ToolCardClassifier.isFileOperation(part))
    }

    @Test
    fun `tool without name is not file operation`() {
        assertFalse(ToolCardClassifier.isFileOperation(toolPart(tool = null)))
    }

    // MARK: - split + count

    @Test
    fun `split partitions file and other tools`() {
        val parts = listOf(
            toolPart(id = "1", tool = "read_file", path = "src/a.ts"),
            toolPart(id = "2", tool = "bash"),
            patchPart(id = "3"),
            toolPart(id = "4", tool = "grep"),
            toolPart(id = "5", tool = "edit"),
        )
        val (fileParts, otherParts) = ToolCardClassifier.split(parts)
        assertEquals(listOf("1", "3", "5"), fileParts.map { it.id })
        assertEquals(listOf("2", "4"), otherParts.map { it.id })
    }

    @Test
    fun `toolCallsCount is other tools only`() {
        val parts = listOf(
            toolPart(id = "1", tool = "write_file"),
            toolPart(id = "2", tool = "bash"),
            toolPart(id = "3", tool = "glob"),
            patchPart(id = "4"),
        )
        // 2 file ops (write_file + patch w/ path), 2 other tools (bash + glob)
        assertEquals(2, ToolCardClassifier.toolCallsCount(parts))
    }

    @Test
    fun `all file ops means zero tool calls`() {
        val parts = listOf(
            toolPart(id = "1", tool = "read_file"),
            patchPart(id = "2"),
        )
        val (fileParts, otherParts) = ToolCardClassifier.split(parts)
        assertEquals(2, fileParts.size)
        assertTrue(otherParts.isEmpty())
        assertEquals(0, ToolCardClassifier.toolCallsCount(parts))
    }

    @Test
    fun `all other tools means no file cards`() {
        val parts = listOf(
            toolPart(id = "1", tool = "bash"),
            toolPart(id = "2", tool = "webfetch"),
            toolPart(id = "3", tool = "task"),
        )
        val (fileParts, otherParts) = ToolCardClassifier.split(parts)
        assertTrue(fileParts.isEmpty())
        assertEquals(3, otherParts.size)
        assertEquals(3, ToolCardClassifier.toolCallsCount(parts))
    }

    // MARK: - Directory read detection + entries parsing

    private val directoryOutput = """
        <path>/abs/path/proj</path>
        <type>directory</type>
        <entries>
        sub/
        nested_dir/
        file.txt
        README.md
        (4 entries)
        </entries>
    """.trimIndent()

    @Test
    fun `directory read is detected`() {
        assertTrue(ToolCardClassifier.isDirectoryRead(readPart(output = directoryOutput)))
    }

    @Test
    fun `file read is not directory read`() {
        val fileOutput = """
            <path>/abs/path/file.txt</path>
            <type>file</type>
            <content>hello</content>
        """.trimIndent()
        assertFalse(ToolCardClassifier.isDirectoryRead(readPart(output = fileOutput)))
    }

    @Test
    fun `read_file alias detects directory`() {
        assertTrue(ToolCardClassifier.isDirectoryRead(readPart(tool = "read_file", output = directoryOutput)))
    }

    @Test
    fun `non read tool is never directory read`() {
        // Even if some tool's output mentioned directory, only `read` counts.
        assertFalse(ToolCardClassifier.isDirectoryRead(readPart(tool = "bash", output = directoryOutput)))
    }

    @Test
    fun `read without output is not directory read`() {
        assertFalse(ToolCardClassifier.isDirectoryRead(toolPart(tool = "read")))
    }

    @Test
    fun `parses entries with dir and file flags`() {
        val entries = ToolCardClassifier.parseDirectoryEntries(directoryOutput)
        assertEquals(4, entries.size)
        assertEquals(listOf("sub", "nested_dir", "file.txt", "README.md"), entries.map { it.name })
        assertEquals(listOf(true, true, false, false), entries.map { it.isDirectory })
    }

    @Test
    fun `parse skips summary and blank lines`() {
        val output = """
            <type>directory</type>
            <entries>

            a/

            b.swift
            (2 entries)
            </entries>
        """.trimIndent()
        val entries = ToolCardClassifier.parseDirectoryEntries(output)
        assertEquals(listOf("a", "b.swift"), entries.map { it.name })
        assertEquals(listOf(true, false), entries.map { it.isDirectory })
    }

    @Test
    fun `parse returns empty when no entries block`() {
        assertTrue(ToolCardClassifier.parseDirectoryEntries("<type>file</type>").isEmpty())
        assertTrue(ToolCardClassifier.parseDirectoryEntries(null).isEmpty())
    }

    @Test
    fun `parse handles missing close tag`() {
        // Tolerate a truncated/streaming output with no </entries>.
        val output = "<entries>\nonly/\nfile.txt\n"
        val entries = ToolCardClassifier.parseDirectoryEntries(output)
        assertEquals(listOf("only", "file.txt"), entries.map { it.name })
        assertEquals(listOf(true, false), entries.map { it.isDirectory })
    }
}

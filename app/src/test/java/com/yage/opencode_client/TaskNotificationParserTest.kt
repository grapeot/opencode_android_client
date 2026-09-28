package com.yage.opencode_client

import com.yage.opencode_client.data.model.Part
import com.yage.opencode_client.ui.chat.TaskNotificationParser
import com.yage.opencode_client.ui.chat.TaskState
import com.yage.opencode_client.ui.chat.copyableMessageText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskNotificationParserTest {
    @Test
    fun `completed envelope parses and strips the status prefix from the title`() {
        val parsed = TaskNotificationParser.parse(envelope())

        assertNotNull(parsed)
        assertEquals("ses_child_long", parsed!!.sessionID)
        assertEquals(TaskState.COMPLETED, parsed.state)
        assertFalse(parsed.isFailed)
        assertEquals("Background task completed: scan logs", parsed.summary)
        assertEquals("alpha", parsed.resultText)
        assertEquals("scan logs", TaskNotificationParser.displayTitle(parsed))
    }

    @Test
    fun `error envelope uses task_error and marks failed`() {
        val parsed = TaskNotificationParser.parse(
            envelope(
                state = "error",
                summary = "Background task failed: scan logs",
                bodyTag = "task_error",
                body = "boom"
            )
        )

        assertNotNull(parsed)
        assertEquals(TaskState.ERROR, parsed!!.state)
        assertTrue(parsed.isFailed)
        assertEquals("boom", parsed.resultText)
        assertEquals("scan logs", TaskNotificationParser.displayTitle(parsed))
    }

    @Test
    fun `running envelope parses and is not failed`() {
        val parsed = TaskNotificationParser.parse(envelope(state = "running", summary = null, body = "still going"))

        assertNotNull(parsed)
        assertEquals(TaskState.RUNNING, parsed!!.state)
        assertFalse(parsed.isFailed)
        assertNull(parsed.summary)
    }

    @Test
    fun `missing summary falls back to a short session id`() {
        val parsed = TaskNotificationParser.parse(envelope(summary = null))

        assertNotNull(parsed)
        assertNull(parsed!!.summary)
        assertEquals("…ild_long", TaskNotificationParser.displayTitle(parsed))
    }

    @Test
    fun `truncated envelope without a closing task tag is null`() {
        assertNull(TaskNotificationParser.parse(envelope(close = false)))
        assertNull(TaskNotificationParser.parse("""<task id="ses_child_long" state="completed"><task_result>partial"""))
    }

    @Test
    fun `non task text is null`() {
        assertNull(TaskNotificationParser.parse("hello"))
        assertNull(TaskNotificationParser.parse("<summary>not a task</summary>"))
        assertNull(TaskNotificationParser.parse(null))
    }

    @Test
    fun `nested angle brackets in the result are not cut`() {
        val body = "see <file>a.xml</file> and </task_result> and </task> still here"
        val parsed = TaskNotificationParser.parse(envelope(body = body))

        assertNotNull(parsed)
        assertTrue(parsed!!.resultText.contains("<file>a.xml</file>"))
        assertTrue(parsed.resultText.contains("</task_result>"))
        assertTrue(parsed.resultText.contains("</task>"))
        assertTrue(parsed.resultText.contains("still here"))
    }

    @Test
    fun `empty result tag parses to empty text`() {
        val text = """<task id="ses_child_long" state="completed"><summary>Background task completed: quiet</summary><task_result></task_result></task>"""
        val parsed = TaskNotificationParser.parse(text)

        assertNotNull(parsed)
        assertEquals("", parsed!!.resultText)
    }

    @Test
    fun `leading and trailing whitespace still parses`() {
        val parsed = TaskNotificationParser.parse("\n  ${envelope()}  \n")

        assertNotNull(parsed)
        assertEquals("ses_child_long", parsed!!.sessionID)
        assertEquals("alpha", parsed.resultText)
    }

    @Test
    fun `missing id state or result tag is null`() {
        assertNull(TaskNotificationParser.parse("""<task state="completed"><task_result>x</task_result></task>"""))
        assertNull(TaskNotificationParser.parse("""<task id="ses_child_long"><task_result>x</task_result></task>"""))
        assertNull(TaskNotificationParser.parse("""<task id="ses_child_long" state="completed"><summary>Background task completed: x</summary></task>"""))
        assertNull(TaskNotificationParser.parse("""<task id="ses_child_long" state="nope"><task_result>x</task_result></task>"""))
    }

    @Test
    fun `synthetic envelope routes to a card and the same text without synthetic does not`() {
        val text = envelope()
        val synthetic = Part(id = "p1", type = "text", text = text, synthetic = true)
        val plain = Part(id = "p2", type = "text", text = text, synthetic = false)
        val absent = Part(id = "p3", type = "text", text = text)
        val broken = Part(id = "p4", type = "text", text = "not a task", synthetic = true)

        assertNotNull(TaskNotificationParser.notificationFor(synthetic))
        assertNull(TaskNotificationParser.notificationFor(plain))
        assertNull(TaskNotificationParser.notificationFor(absent))
        assertNull(TaskNotificationParser.notificationFor(broken))
        assertFalse(absent.isSyntheticText)
        assertTrue(synthetic.isSyntheticText)
    }

    @Test
    fun `receipt copy uses result text and a non synthetic copy keeps the xml`() {
        val text = envelope(body = "alpha")
        val receipt = Part(id = "p1", type = "text", text = text, synthetic = true)
        val plain = Part(id = "p2", type = "text", text = text)

        val copied = copyableMessageText(listOf(receipt))
        assertTrue(copied.contains("alpha"))
        assertFalse(copied.contains("<task"))
        assertTrue(copyableMessageText(listOf(plain)).contains("<task id="))
    }

    @Test
    fun `synthetic receipt does not offer edit from here`() {
        val receipt = Part(id = "p1", type = "text", text = envelope(), synthetic = true)
        val plain = Part(id = "p2", type = "text", text = "hello")

        assertFalse(TaskNotificationParser.offersEditFromHere(isUser = true, parts = listOf(receipt)))
        assertTrue(TaskNotificationParser.offersEditFromHere(isUser = true, parts = listOf(plain)))
        assertFalse(TaskNotificationParser.offersEditFromHere(isUser = false, parts = listOf(plain)))
    }

    @Test
    fun `large preview threshold`() {
        assertNull(TaskNotificationParser.largeMessagePreview("a".repeat(12_000)))
        assertEquals(12_000, TaskNotificationParser.largeMessagePreview("a".repeat(12_001))!!.length)
    }

    private fun envelope(
        id: String = "ses_child_long",
        state: String = "completed",
        summary: String? = "Background task completed: scan logs",
        bodyTag: String = "task_result",
        body: String = "alpha",
        close: Boolean = true
    ): String = buildString {
        append("""<task id="$id" state="$state">""")
        append('\n')
        if (summary != null) {
            append("<summary>")
            append(summary)
            append("</summary>\n")
        }
        append("<$bodyTag>\n")
        append(body)
        append("\n</$bodyTag>\n")
        if (close) append("</task>")
    }
}

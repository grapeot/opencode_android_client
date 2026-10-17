package com.yage.opencode_client.ambient

import com.yage.opencode_client.ambient.protocol.AmbientCaptionBook
import com.yage.opencode_client.ambient.protocol.AmbientCaptionWrite
import com.yage.opencode_client.ambient.protocol.AmbientHistoryTurn
import com.yage.opencode_client.ambient.protocol.AmbientTimeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AmbientCaptionTest {
    @Test
    fun interleaveRetainsExactUserAndAssistantStrings() {
        val book = AmbientCaptionBook()
        val user = arrayOf("  go go ", "go ", "go", "  ", "stop")
        val assistant = arrayOf("wait wait ", "wait ", "wait", "  ", "now")
        repeat(5) { i ->
            book.append("user", user[i])
            book.append("assistant", assistant[i])
        }
        assertEquals(2, book.captions.size)
        assertEquals("caption-1", book.captions[0].id)
        assertEquals("  go go go go  stop", book.captions[0].text)
        assertEquals("caption-1", book.openId("user"))
        assertEquals("caption-2", book.captions[1].id)
        assertEquals("wait wait wait wait  now", book.captions[1].text)
        assertEquals("caption-2", book.openId("assistant"))
    }

    @Test
    fun speechGapMergesAt1900And2000AndSplitsAt2100() {
        assertEquals("left  left", bookAfterGap(1_900).captions.single().text)
        val merged = bookAfterGap(2_000).captions.single()
        assertEquals("left  left", merged.text)
        assertEquals("caption-1", merged.id)
        assertEquals(0, checkNotNull(merged.startMs))
        assertEquals(3_100, checkNotNull(merged.endMs))
        val split = bookAfterGap(2_100).captions
        assertEquals(2, split.size)
        assertEquals("left ", split[0].text)
        assertEquals("caption-1", split[0].id)
        assertEquals(" left", split[1].text)
        assertEquals("caption-2", split[1].id)
        assertEquals(3_100, checkNotNull(split[1].startMs))
    }

    @Test
    fun missingTimestampsAndDeliveryDelayDoNotSplit() {
        val missing = AmbientCaptionBook()
        missing.append("assistant", "hey hey ", 0, 400)
        missing.append("assistant", "hey ", startMs = null, endMs = 90_000)
        missing.append("assistant", "hey")
        assertEquals("hey hey hey hey", missing.captions.single().text)
        assertEquals("caption-1", missing.openId("assistant"))
        assertEquals(90_000, checkNotNull(missing.captions.single().endMs))

        val delayed = AmbientCaptionBook()
        delayed.append("user", "yo yo ")
        delayed.append("user", "yo", 80_000, 81_000)
        assertEquals("yo yo yo", delayed.captions.single().text)
        assertEquals("caption-1", delayed.openId("user"))
    }

    @Test
    fun newToolClosesOnceAndUpdateDoesNotSplitAssistant() {
        val timeline = AmbientTimeline()
        timeline.captionBook.append("user", "ask  ask ")
        timeline.captionBook.append("assistant", "say say ")
        timeline.upsertActivity("call-9", "bash", "pwd", "running", "")
        assertEquals(listOf("caption-1", "caption-2"), timeline.captions.map { it.id })
        assertEquals("say say ", timeline.captions[1].text)
        assertEquals(3, timeline.activities.single().sequence)
        assertNull(timeline.captionBook.openId("user"))
        assertNull(timeline.captionBook.openId("assistant"))

        assertEquals(AmbientCaptionWrite.OPENED, timeline.captionBook.append("assistant", "say "))
        assertEquals("caption-4", timeline.captionBook.openId("assistant"))
        timeline.upsertActivity("call-9", "bash", "pwd", "done", "ok ok")
        assertEquals(1, timeline.activities.size)
        assertEquals(3, timeline.activities.single().sequence)
        assertEquals("done", timeline.activities.single().status)
        assertEquals("ok ok", timeline.activities.single().result)
        assertEquals("caption-4", timeline.captionBook.openId("assistant"))
        assertEquals(AmbientCaptionWrite.APPENDED, timeline.captionBook.append("assistant", "say"))
        assertEquals(3, timeline.captions.size)
        assertEquals("say say", timeline.captions.last().text)
        assertEquals("caption-4", timeline.captions.last().id)
        assertEquals(4, timeline.captions.last().sequence)
    }

    @Test
    fun trimming201SegmentsRetains200AndActiveAppend() {
        val book = AmbientCaptionBook()
        repeat(201) { i ->
            val start = i * 3_000
            assertEquals(AmbientCaptionWrite.OPENED, book.append("assistant", "n$i ", start, start + 100))
        }
        assertEquals(200, book.captions.size)
        assertEquals("caption-2", book.captions.first().id)
        assertEquals("n1 ", book.captions.first().text)
        assertEquals("caption-201", book.openId("assistant"))
        assertEquals("n200 ", book.captions.last().text)
        val follow = book.append("assistant", "n200 ", 200 * 3_000 + 100, 200 * 3_000 + 200)
        assertEquals(AmbientCaptionWrite.APPENDED, follow)
        assertEquals(200, book.captions.size)
        assertEquals("n200 n200 ", book.captions.last().text)
        assertEquals("caption-201", book.captions.last().id)
    }

    @Test
    fun resetClearsPriorStateAndRestartsSequence() {
        val timeline = AmbientTimeline()
        timeline.captionBook.append("user", "old old ")
        timeline.captionBook.append("assistant", "gone")
        timeline.upsertActivity("call-1", "bash", "ls", "running", "noise")
        timeline.reset(listOf(AmbientHistoryTurn("user", "  kept kept "), AmbientHistoryTurn("assistant", "kept kept")))
        assertEquals(0, timeline.activities.size)
        assertEquals(listOf("history-1", "history-2"), timeline.captions.map { it.id })
        assertEquals(listOf(1, 2), timeline.captions.map { it.sequence })
        assertEquals("  kept kept ", timeline.captions[0].text)
        assertEquals("kept kept", timeline.captions[1].text)
        assertNull(timeline.captionBook.openId("user"))
        assertEquals(AmbientCaptionWrite.OPENED, timeline.captionBook.append("user", "fresh"))
        assertEquals("caption-3", timeline.captions.last().id)
        assertEquals(3, timeline.captions.last().sequence)
    }

    private fun bookAfterGap(gapMs: Int) = AmbientCaptionBook().also {
        it.append("user", "left ", 0, 1_000)
        it.append("user", " left", 1_000 + gapMs, 1_000 + gapMs + 100)
    }
}

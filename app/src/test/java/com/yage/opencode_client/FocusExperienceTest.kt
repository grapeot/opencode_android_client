package com.yage.opencode_client

import com.yage.opencode_client.ui.AppWidthClass
import com.yage.opencode_client.ui.DraftLookup
import com.yage.opencode_client.ui.EffectiveLayout
import com.yage.opencode_client.ui.LayoutPreference
import com.yage.opencode_client.ui.PastePlacement
import com.yage.opencode_client.ui.appendPaste
import com.yage.opencode_client.ui.insertPaste
import com.yage.opencode_client.ui.joinClipboardText
import com.yage.opencode_client.ui.lookupDraft
import com.yage.opencode_client.ui.mergeSendFailure
import com.yage.opencode_client.ui.mergingSpeech
import com.yage.opencode_client.ui.FilePreviewKind
import com.yage.opencode_client.ui.OwnedFilePreview
import com.yage.opencode_client.ui.layoutMigrationFrom
import com.yage.opencode_client.ui.migrateVisiblePreview
import com.yage.opencode_client.ui.chat.snapCursor
import com.yage.opencode_client.ui.renderAnchoredSpeech
import com.yage.opencode_client.ui.ownedDraftStorageKey
import com.yage.opencode_client.ui.pastePlacement
import com.yage.opencode_client.ui.resolveEffectiveLayout
import com.yage.opencode_client.ui.shouldPushTextToEditor
import com.yage.opencode_client.ui.unionAttachmentsById
import com.yage.opencode_client.ui.chat.ChatFilePreviewRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusExperienceTest {
    @Test
    fun `layout matrix keeps medium and compact single content unless expanded automatic`() {
        assertEquals(EffectiveLayout.COMPACT, resolveEffectiveLayout(LayoutPreference.AUTOMATIC, AppWidthClass.COMPACT))
        assertEquals(EffectiveLayout.COMPACT, resolveEffectiveLayout(LayoutPreference.AUTOMATIC, AppWidthClass.MEDIUM))
        assertEquals(EffectiveLayout.REGULAR, resolveEffectiveLayout(LayoutPreference.AUTOMATIC, AppWidthClass.EXPANDED))
        AppWidthClass.entries.forEach { width ->
            assertEquals(EffectiveLayout.COMPACT, resolveEffectiveLayout(LayoutPreference.FOCUS, width))
        }
        assertEquals(LayoutPreference.AUTOMATIC, LayoutPreference.fromRaw("nope"))
    }

    @Test
    fun `preview migration follows the visible file and does not resurrect a hidden one`() {
        val visible = request("notes.md")
        assertEquals(EffectiveLayout.COMPACT, layoutMigrationFrom(EffectiveLayout.COMPACT, EffectiveLayout.REGULAR))
        assertNull(layoutMigrationFrom(null, EffectiveLayout.REGULAR))
        assertNull(layoutMigrationFrom(EffectiveLayout.REGULAR, EffectiveLayout.REGULAR))
        val toRegular = migrateVisiblePreview(
            from = EffectiveLayout.COMPACT,
            to = EffectiveLayout.REGULAR,
            inline = visible,
            column = owned("old.kt", session = "old"),
            hostId = "host",
            sessionId = "session",
            workspace = "/work",
        )
        assertNull(toRegular.inline)
        assertEquals("notes.md", toRegular.column?.path)
        assertEquals("session", toRegular.column?.sessionId)

        val back = migrateVisiblePreview(
            from = EffectiveLayout.REGULAR,
            to = EffectiveLayout.COMPACT,
            inline = request("stale.md"),
            column = owned("notes.md"),
            hostId = "host",
            sessionId = "session",
            workspace = "/work",
        )
        assertEquals("notes.md", back.inline?.path)
        assertEquals("session", back.inline?.sessionId)
        assertNull(back.column)

        val filesStay = migrateVisiblePreview(
            from = EffectiveLayout.REGULAR,
            to = EffectiveLayout.COMPACT,
            inline = request("stale.md"),
            column = owned("tree.kt", kind = FilePreviewKind.FILES, session = null),
            hostId = "host",
            sessionId = "session",
            workspace = "/work",
        )
        assertNull(filesStay.inline)
        assertEquals("tree.kt", filesStay.column?.path)
        assertEquals(FilePreviewKind.FILES, filesStay.column?.kind)
    }

    @Test
    fun `preview closes when host session or workspace does not match`() {
        val wrongHost = migrateVisiblePreview(
            from = EffectiveLayout.COMPACT,
            to = EffectiveLayout.REGULAR,
            inline = request("notes.md", host = "other"),
            column = OwnedFilePreview("notes.md", "other", "other-session", "/work", FilePreviewKind.CHAT),
            hostId = "host",
            sessionId = "session",
            workspace = "/work",
        )
        assertNull(wrongHost.inline)
        assertNull(wrongHost.column)

        val wrongWorkspace = migrateVisiblePreview(
            from = EffectiveLayout.REGULAR,
            to = EffectiveLayout.COMPACT,
            inline = null,
            column = OwnedFilePreview("notes.md", "host", "session", "/other", FilePreviewKind.CHAT),
            hostId = "host",
            sessionId = "session",
            workspace = "/work",
        )
        assertNull(wrongWorkspace.inline)
        assertNull(wrongWorkspace.column)

        val kept = migrateVisiblePreview(
            from = EffectiveLayout.COMPACT,
            to = EffectiveLayout.REGULAR,
            inline = request("notes.md"),
            column = null,
            hostId = "host",
            sessionId = "session",
            workspace = "/work",
        )
        assertNull(kept.inline)
        assertEquals("notes.md", kept.column?.path)
        assertEquals("host", kept.column?.hostId)
        assertEquals("session", kept.column?.sessionId)
        assertEquals("/work", kept.column?.workspace)
        assertEquals(FilePreviewKind.CHAT, kept.column?.kind)
    }

    @Test
    fun `paste inserts at selection only after focus and defers while composing`() {
        assertEquals(PastePlacement.APPEND, pastePlacement(false, 1, 1, false, null))
        assertEquals(PastePlacement.INSERT, pastePlacement(true, 2, 4, false, null))
        assertEquals(PastePlacement.DEFER, pastePlacement(true, 2, 2, true, null))
        assertEquals(PastePlacement.APPEND, pastePlacement(true, 1, 1, false, speechOwnedEnd = 5))
        assertEquals("alpha\nbeta\ngamma", joinClipboardText(listOf("alpha", "", "beta", "gamma")))
        assertEquals("hello\nthere", appendPaste("hello", "there"))
        assertEquals("heXlo" to 3, insertPaste("hello", "X", 2, 3))
        assertEquals("ab你ef" to 3, insertPaste("abCDef", "你", 2, 4))
        assertEquals("aXb" to 2, insertPaste("a👍b", "X", 1, 3))
        assertEquals(3, snapCursor("a👍b", 2))
        assertEquals(2, snapCursor("aXb", 2))
    }

    @Test
    fun `late speech keeps paste tail and send failure does not use substring contains`() {
        assertEquals("hello worldnote", renderAnchoredSpeech("hello", "world", "hello", "hellonote").text)
        assertEquals("hello world\nmore\npasted", renderAnchoredSpeech("hello", "world\nmore", "hello", "hello\npasted").text)
        val first = renderAnchoredSpeech("hello", "world", "hello", "hellonote")
        assertEquals("hello worldnote", first.text)
        assertEquals("note", first.tail)
        assertEquals("hello world!note", renderAnchoredSpeech("hello", "world!", first.anchor, first.text).text)
        assertEquals("hello world\npasted", mergingSpeech("hello", "world", "hello\npasted"))
        assertEquals("hello world", mergingSpeech("hello", "world", "hello"))
        assertEquals("user typed", mergingSpeech("hello", "world", "user typed"))
        assertEquals("sent\nadded", mergeSendFailure("sent", "added"))
        assertEquals("sent\nmore", mergeSendFailure("sent", "sent\nmore"))
        assertEquals("sent\nsentence\nsent inside", mergeSendFailure("sent", "sentence\nsent inside"))
        assertEquals(listOf("a", "b"), unionAttachmentsById(listOf("a"), listOf("b", "a")))
    }

    @Test
    fun `legacy draft migrates only when the single host is confirmed`() {
        val legacy = mapOf("session" to "old")
        val confirmed = lookupDraft(legacy, "host-a", "session", setOf("host-a"))
        assertEquals("old", confirmed.text)
        assertTrue(confirmed.migrated)
        assertEquals("old", confirmed.stored?.get(ownedDraftStorageKey("host-a", "session")))
        assertFalse(confirmed.stored!!.containsKey("session"))

        val ambiguous = lookupDraft(legacy, "host-a", "session", setOf("host-a", "host-b"))
        assertEquals("", ambiguous.text)
        assertTrue(ambiguous.ambiguousLegacy)
        assertEquals(DraftLookup(text = "old"), lookupDraft(legacy, null, "session", setOf("host-a", "host-b")).let {
            DraftLookup(text = it.text)
        })
    }

    @Test
    fun `editor push skips recomposition and composing but applies owner changes`() {
        assertFalse(shouldPushTextToEditor("same", "same", composing = false, ownerChanged = false))
        assertFalse(shouldPushTextToEditor("old", "new", composing = true, ownerChanged = false))
        assertTrue(shouldPushTextToEditor("old", "new", composing = false, ownerChanged = false))
        assertTrue(shouldPushTextToEditor("same", "same", composing = true, ownerChanged = true))
    }

    private fun owned(
        path: String,
        session: String? = "session",
        kind: FilePreviewKind = FilePreviewKind.CHAT,
    ) = OwnedFilePreview(path, "host", session, "/work", kind)

    private fun request(path: String, host: String = "host") = ChatFilePreviewRequest(
        path = path,
        hostProfileId = host,
        sessionId = "session",
        workspaceDirectory = "/work",
    )
}

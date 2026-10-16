package com.yage.opencode_client

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.text.Editable
import android.text.Spanned
import android.text.TextWatcher
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.test.platform.app.InstrumentationRegistry
import com.yage.opencode_client.ui.PastePlacement
import com.yage.opencode_client.ui.appendPaste
import com.yage.opencode_client.ui.chat.ChatInputBar
import com.yage.opencode_client.ui.insertPaste
import com.yage.opencode_client.ui.joinClipboardText
import com.yage.opencode_client.ui.pastePlacement
import com.yage.opencode_client.ui.theme.OpenCodeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runners.MethodSorters

/**
 * Paste harness mirrors ChatScreen's clipboard read plus MainViewModel's
 * defer/placement/insert/append rules. It does not construct MainViewModel:
 * speech-owned region, draft storage, and session mismatch stay outside this file.
 * A click counter is never treated as proof of insertion.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class VoiceFirstEditorInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test
    fun tapKeepsImeSuppressedUntilKeyboardButton() {
        var keyboardRequested by mutableStateOf(false)
        var pasteClicks = 0
        var keyboardClicks = 0
        composeRule.setContent {
            MaterialTheme {
                ChatInputBar(
                    text = "draft",
                    isRecording = false,
                    isTranscribing = false,
                    hasPreservedSpeechAudio = false,
                    isRetryingSpeech = false,
                    speechAudioLevel = 0f,
                    isSpeechConfigured = true,
                    imageAttachments = emptyList(),
                    onTextChange = {},
                    onSend = {},
                    onAddImages = {},
                    onRemoveImage = {},
                    onAbortSpeech = {},
                    onRetrySpeech = {},
                    onDiscardSpeech = {},
                    onToggleRecording = {},
                    voiceFirst = true,
                    keyboardRequested = keyboardRequested,
                    ownerKey = "host:session",
                    onPaste = { pasteClicks += 1 },
                    onKeyboard = {
                        keyboardClicks += 1
                        keyboardRequested = !keyboardRequested
                    },
                )
            }
        }

        composeRule.onNodeWithTag("chat-paste").performClick()
        assertEquals(1, pasteClicks)
        composeRule.onNodeWithTag("chat-input").performTouchInput { click() }
        composeRule.waitForIdle()
        val editor = findEditText(composeRule.activity.window.decorView)
        assertNotNull(editor)
        assertTrue(editor!!.isFocused)
        assertFalse(editor.showSoftInputOnFocus)
        assertFalse(imeVisible(editor))

        composeRule.onNodeWithTag("chat-keyboard").performClick()
        composeRule.waitForIdle()
        assertEquals(1, keyboardClicks)
        assertTrue(editor.showSoftInputOnFocus)
    }

    @Test
    fun selectionSurvivesRecompositionUntilOwnerChanges() {
        var level by mutableStateOf(0f)
        var owner by mutableStateOf("host:session")
        composeRule.setContent {
            MaterialTheme {
                ChatInputBar(
                    text = "draft",
                    isRecording = false,
                    isTranscribing = false,
                    hasPreservedSpeechAudio = false,
                    isRetryingSpeech = false,
                    speechAudioLevel = level,
                    isSpeechConfigured = true,
                    imageAttachments = emptyList(),
                    onTextChange = {},
                    onSend = {},
                    onAddImages = {},
                    onRemoveImage = {},
                    onAbortSpeech = {},
                    onRetrySpeech = {},
                    onDiscardSpeech = {},
                    onToggleRecording = {},
                    voiceFirst = true,
                    ownerKey = owner,
                )
            }
        }
        composeRule.onNodeWithTag("chat-input").performTouchInput { click() }
        composeRule.waitForIdle()
        val editor = findEditText(composeRule.activity.window.decorView)
        assertNotNull(editor)
        val field = editor!!
        composeRule.runOnUiThread { field.setSelection(1, 3) }
        composeRule.waitForIdle()
        level = 0.4f
        composeRule.waitForIdle()
        assertEquals(1, field.selectionStart)
        assertEquals(3, field.selectionEnd)
        assertFalse(field.showSoftInputOnFocus)
        assertFalse(imeVisible(field))

        owner = "host:other"
        composeRule.waitForIdle()
        assertFalse(editor.isFocused)
        assertFalse(editor.showSoftInputOnFocus)
    }

    @Test
    fun voiceFirstOffKeepsComposeFieldAndHidesPaste() {
        composeRule.setContent {
            MaterialTheme {
                ChatInputBar(
                    text = "draft",
                    isRecording = false,
                    isTranscribing = false,
                    hasPreservedSpeechAudio = false,
                    isRetryingSpeech = false,
                    speechAudioLevel = 0f,
                    isSpeechConfigured = true,
                    imageAttachments = emptyList(),
                    onTextChange = {},
                    onSend = {},
                    onAddImages = {},
                    onRemoveImage = {},
                    onAbortSpeech = {},
                    onRetrySpeech = {},
                    onDiscardSpeech = {},
                    onToggleRecording = {},
                )
            }
        }
        assertTrue(composeRule.onAllNodesWithTag("chat-paste").fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithTag("chat-keyboard").fetchSemanticsNodes().isEmpty())
        assertNull(findEditText(composeRule.activity.window.decorView))
    }

    @Test
    fun emptyHintBaselineSharesCaretFirstLine() {
        val probe = PasteProbe(text = "")
        composeRule.setContent {
            OpenCodeTheme {
                ChatInputBar(
                    text = probe.text,
                    isRecording = false,
                    isTranscribing = false,
                    hasPreservedSpeechAudio = false,
                    isRetryingSpeech = false,
                    speechAudioLevel = 0f,
                    isSpeechConfigured = true,
                    imageAttachments = emptyList(),
                    onTextChange = probe::onTextChange,
                    onSend = {},
                    onAddImages = {},
                    onRemoveImage = {},
                    onAbortSpeech = {},
                    onRetrySpeech = {},
                    onDiscardSpeech = {},
                    onToggleRecording = {},
                    voiceFirst = true,
                    ownerKey = probe.owner,
                    onCaret = probe::onCaret,
                )
            }
        }
        composeRule.waitForIdle()
        val editor = requireEditor()
        val hintText = composeRule.activity.getString(R.string.chat_type_message)
        composeRule.runOnUiThread { paintMeasurementCursor(editor) }
        composeRule.onNodeWithTag("chat-input").performTouchInput { click() }
        composeRule.waitForIdle()
        composeRule.runOnUiThread { paintMeasurementCursor(editor) }
        composeRule.waitForIdle()
        Thread.sleep(200)
        val shot = awaitCaretShot(editor)
        val report = measureCaretAgainstHint(shot, editor, hintText)
        writeAssertedBitmap(shot, editor, report)
        assertFalse("tap must not show IME while measuring caret: ${imeDebug(editor)}", imeVisible(editor))
        assertTrue(editor.isFocused)
        assertTrue(report.detail, report.sameLine && report.firstLine)
    }

    @Test
    fun tapShowsCollapsedCaretWithoutIme() {
        val probe = PasteProbe(text = "")
        setBar(probe)
        val editor = requireEditor()
        assertFalse(editor.isFocused)
        composeRule.onNodeWithTag("chat-input").performTouchInput { click() }
        composeRule.waitForIdle()
        saveShot("caret-no-ime")
        assertTrue(editor.isFocused)
        assertTrue(editor.isCursorVisible)
        assertEquals(editor.selectionStart, editor.selectionEnd)
        assertFalse(editor.showSoftInputOnFocus)
        assertFalse("tap must not show IME: ${imeDebug(editor)}", imeVisible(editor))
        assertTrue(
            "tap must report focused caret, events=${probe.caretEvents}",
            probe.caretEvents.any { it.startsWith("f=true") },
        )
    }

    @Test
    fun explicitKeyboardButtonShowsAndDismissesImeWindow() {
        val probe = PasteProbe(text = "draft")
        setBar(probe)
        val editor = requireEditor()
        val before = editor.text
        composeRule.onNodeWithTag("chat-keyboard").performClick()
        composeRule.waitForIdle()
        val shown = awaitIme(editor, want = true)
        saveShot("keyboard-shown")
        if (!shown) {
            val diagnostic = diagnoseShow(editor)
            saveShot("keyboard-shown-after-diagnostic")
            assertTrue("keyboard button did not show IME. ${imeDebug(editor)} diagnostic=$diagnostic", false)
        }
        assertTrue(editor.showSoftInputOnFocus)
        assertTrue(editor.isCursorVisible)
        assertSame(before, editor.text)
        probe.level = 0.4f
        composeRule.waitForIdle()
        assertSame(before, editor.text)
        assertTrue(imeVisible(editor))
        assertTrue(editor.showSoftInputOnFocus)

        composeRule.onNodeWithTag("chat-keyboard").performClick()
        composeRule.waitForIdle()
        val hidden = awaitIme(editor, want = false)
        saveShot("keyboard-dismissed")
        assertTrue("keyboard button did not dismiss IME: ${imeDebug(editor)}", hidden)
        assertFalse(editor.showSoftInputOnFocus)
        assertFalse(probe.keyboardRequested)
    }

    @Test
    fun keyboardRequestedBeforeAttachShowsIme() {
        val probe = PasteProbe(text = "draft")
        probe.keyboardRequested = true
        setBar(probe)
        val editor = requireEditor()
        val firstShown = awaitIme(editor, want = true, timeoutMs = 2_000)
        val firstFlag = editor.showSoftInputOnFocus
        probe.level = 0.6f
        composeRule.waitForIdle()
        val secondShown = awaitIme(editor, want = true, timeoutMs = 3_000)
        saveShot("keyboard-first-bind")
        assertTrue(
            "firstBind showOnFocus=$firstFlag ime=$firstShown; after unrelated recompose showOnFocus=${editor.showSoftInputOnFocus} ime=$secondShown ${imeDebug(editor)}",
            firstFlag && firstShown,
        )
    }

    @Test
    fun focusedPasteInsertsClipboardIntoSelection() {
        val probe = PasteProbe(text = "abCDef")
        setBar(probe)
        val editor = requireEditor()
        composeRule.onNodeWithTag("chat-input").performTouchInput { click() }
        composeRule.waitForIdle()
        composeRule.runOnUiThread { editor.setSelection(2, 4) }
        composeRule.waitForIdle()
        assertTrue("selection change must report focus, events=${probe.caretEvents}", probe.focused)
        assertEquals(2, probe.selStart)
        assertEquals(4, probe.selEnd)
        seedClip("你")
        composeRule.onNodeWithTag("chat-paste").performClick()
        composeRule.waitForIdle()
        saveShot("paste-inserted")
        assertEquals(listOf("你"), probe.readParts)
        assertEquals("ab你ef", editor.text.toString())
        assertEquals("ab你ef", probe.text)
        assertEquals(3, editor.selectionStart)
        assertEquals(3, editor.selectionEnd)
        assertFalse(insideSurrogate(editor.text.toString(), editor.selectionStart))
        assertFalse(insideSurrogate(editor.text.toString(), editor.selectionEnd))
    }

    @Test
    fun unfocusedPasteAppendsClipboard() {
        val probe = PasteProbe(text = "hello")
        setBar(probe)
        val editor = requireEditor()
        assertFalse(editor.isFocused)
        assertFalse(probe.focused)
        seedClip("ALPHA", "BETA")
        composeRule.onNodeWithTag("chat-paste").performClick()
        composeRule.waitForIdle()
        saveShot("paste-appended")
        assertEquals(listOf("ALPHA", "BETA"), probe.readParts)
        assertEquals("hello\nALPHA\nBETA", editor.text.toString())
        assertEquals("hello\nALPHA\nBETA", probe.text)
    }

    @Test
    fun emptyClipboardDoesNotInsert() {
        val probe = PasteProbe(text = "hello")
        setBar(probe)
        val editor = requireEditor()
        seedClip("")
        composeRule.onNodeWithTag("chat-paste").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf(""), probe.readParts)
        assertEquals("hello", editor.text.toString())
        assertEquals(1, probe.pasteClicks)
    }

    @Test
    fun composingNotOverwrittenBySpeechOrPaste() {
        val probe = PasteProbe(text = "草稿")
        setBar(probe)
        val editor = requireEditor()
        composeRule.onNodeWithTag("chat-input").performTouchInput { click() }
        composeRule.waitForIdle()
        composeRule.runOnUiThread {
            editor.setSelection(0, editor.text?.length ?: 0)
            val connection = editor.onCreateInputConnection(EditorInfo())
            assertNotNull(connection)
            assertTrue(connection!!.setComposingText("你好", 1))
        }
        composeRule.waitForIdle()
        assertTrue("composing span missing after setComposingText: ${editor.text}", hasComposing(editor))
        assertEquals("你好", editor.text.toString())
        probe.echoEditor = false
        probe.text = "外来语音"
        composeRule.waitForIdle()
        seedClip("PASTE-OVERWRITE")
        composeRule.onNodeWithTag("chat-paste").performClick()
        composeRule.waitForIdle()
        saveShot("composing-held")
        assertEquals(listOf("PASTE-OVERWRITE"), probe.readParts)
        assertEquals("PASTE-OVERWRITE", probe.deferred)
        assertEquals("你好", editor.text.toString())
        assertTrue(hasComposing(editor))
        assertFalse(editor.text.toString().contains("外来语音"))
        assertFalse(editor.text.toString().contains("PASTE-OVERWRITE"))
    }

    @Test
    fun finishComposingReportsCaretAndAppliesDeferred() {
        val probe = PasteProbe(text = "草稿")
        setBar(probe)
        val editor = requireEditor()
        composeRule.onNodeWithTag("chat-input").performTouchInput { click() }
        composeRule.waitForIdle()
        composeRule.runOnUiThread {
            editor.setSelection(0, editor.text?.length ?: 0)
            val connection = editor.onCreateInputConnection(EditorInfo())
            assertNotNull(connection)
            assertTrue(connection!!.setComposingText("你好", 1))
        }
        composeRule.waitForIdle()
        probe.echoEditor = false
        seedClip("PASTE-OVERWRITE")
        composeRule.onNodeWithTag("chat-paste").performClick()
        composeRule.waitForIdle()
        assertEquals("PASTE-OVERWRITE", probe.deferred)
        probe.caretEvents.clear()
        composeRule.runOnUiThread {
            editor.onCreateInputConnection(EditorInfo())?.finishComposingText()
        }
        composeRule.waitForIdle()
        saveShot("composing-finished")
        assertFalse(hasComposing(editor))
        assertTrue(
            "finishComposingText cleared the span but did not report composing=false; deferred=${probe.deferred} events=${probe.caretEvents} text=${editor.text}",
            probe.caretEvents.any { it.contains("c=false") },
        )
        assertNull(probe.deferred)
        assertTrue(editor.text.toString().contains("PASTE-OVERWRITE"))
    }

    @Test
    fun unchangedRecompositionDoesNotReplaceEditable() {
        val probe = PasteProbe(text = "stable")
        setBar(probe)
        val editor = requireEditor()
        var changes = 0
        composeRule.runOnUiThread {
            editor.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    changes += 1
                }
            })
        }
        val before = editor.text
        probe.level = 0.4f
        composeRule.waitForIdle()
        assertSame(before, editor.text)
        assertEquals(0, changes)

        probe.text = "replaced-once"
        composeRule.waitForIdle()
        assertEquals("replaced-once", editor.text.toString())
        assertNotSame(before, editor.text)
        assertEquals(1, changes)
        val after = editor.text
        probe.level = 0.8f
        composeRule.waitForIdle()
        assertSame(after, editor.text)
        assertEquals(1, changes)
    }

    @Test
    fun emojiPasteSelectionStaysOnCodePointBoundary() {
        val probe = PasteProbe(text = "a👍b")
        setBar(probe)
        val editor = requireEditor()
        assertEquals(4, editor.text?.length)
        composeRule.onNodeWithTag("chat-input").performTouchInput { click() }
        composeRule.waitForIdle()
        composeRule.runOnUiThread { editor.setSelection(1, 3) }
        composeRule.waitForIdle()
        assertFalse(insideSurrogate(editor.text.toString(), 1))
        assertFalse(insideSurrogate(editor.text.toString(), 3))
        assertEquals(1, probe.selStart)
        assertEquals(3, probe.selEnd)
        seedClip("X")
        composeRule.onNodeWithTag("chat-paste").performClick()
        composeRule.waitForIdle()
        assertEquals("aXb", editor.text.toString())
        assertEquals(2, editor.selectionStart)
        assertEquals(2, editor.selectionEnd)
        assertFalse(insideSurrogate(editor.text.toString(), editor.selectionStart))
        assertFalse(insideSurrogate(editor.text.toString(), editor.selectionEnd))
        assertTrue(editor.selectionStart <= editor.selectionEnd)
    }

    @Test
    fun ownerChangeClearsFocusWithoutRestartLoop() {
        val probe = PasteProbe(text = "a👍b")
        setBar(probe)
        val editor = requireEditor()
        composeRule.onNodeWithTag("chat-input").performTouchInput { click() }
        composeRule.waitForIdle()
        val before = editor.text
        probe.caretEvents.clear()
        probe.owner = "host:other"
        composeRule.waitForIdle()
        assertFalse(editor.isFocused)
        assertFalse(editor.showSoftInputOnFocus)
        assertEquals(editor.text?.length, editor.selectionStart)
        assertEquals(editor.selectionStart, editor.selectionEnd)
        assertFalse(insideSurrogate(editor.text.toString(), editor.selectionStart))
        assertNotSame(before, editor.text)
        val restarted = editor.text
        probe.level = 0.3f
        composeRule.waitForIdle()
        assertSame(restarted, editor.text)
        assertTrue(
            "owner clearFocus must report focused=false, events=${probe.caretEvents} ${imeDebug(editor)}",
            probe.caretEvents.any { it.startsWith("f=false") },
        )
    }

    @Test
    fun disposeKeepsSpeechCallbacksAndRestoresDraft() {
        val probe = PasteProbe(text = "draft-audio")
        probe.recording = true
        setBar(probe)
        val first = requireEditor()
        composeRule.onNodeWithContentDescription("Listening").assertIsEnabled()
        probe.voiceFirst = false
        composeRule.waitForIdle()
        assertNull(findEditText(composeRule.activity.window.decorView))
        assertEquals(0, probe.toggles)
        assertEquals(0, probe.aborts)
        assertEquals(0, probe.discards)
        assertEquals(0, probe.retries)
        assertEquals("draft-audio", probe.text)
        assertNull(fieldOrNull(first, "onCaret"))
        assertNull(fieldOrNull(first, "watcher"))
        assertFalse(first.isAttachedToWindow)
        probe.voiceFirst = true
        composeRule.waitForIdle()
        val second = requireEditor()
        assertNotSame(first, second)
        assertEquals("draft-audio", second.text.toString())
        assertEquals(0, probe.toggles)
        assertEquals(0, probe.aborts)
        composeRule.onNodeWithContentDescription("Listening").assertIsEnabled()
        saveShot("dispose-restored")
    }

    @Test
    fun normalModeAcceptsTextWithoutNativeEditor() {
        var text by mutableStateOf("")
        var received = ""
        var sends = 0
        composeRule.setContent {
            MaterialTheme {
                ChatInputBar(
                    text = text,
                    isRecording = false,
                    isTranscribing = false,
                    hasPreservedSpeechAudio = false,
                    isRetryingSpeech = false,
                    speechAudioLevel = 0f,
                    isSpeechConfigured = true,
                    imageAttachments = emptyList(),
                    onTextChange = {
                        received = it
                        text = it
                    },
                    onSend = { sends += 1 },
                    onAddImages = {},
                    onRemoveImage = {},
                    onAbortSpeech = {},
                    onRetrySpeech = {},
                    onDiscardSpeech = {},
                    onToggleRecording = {},
                )
            }
        }
        assertNull(findEditText(composeRule.activity.window.decorView))
        composeRule.onNodeWithTag("chat-input").performTextInput("normal-seed")
        composeRule.waitForIdle()
        assertEquals("normal-seed", received)
        assertEquals("normal-seed", text)
        assertNull(findEditText(composeRule.activity.window.decorView))
        assertTrue(composeRule.onAllNodesWithTag("chat-paste").fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithTag("chat-keyboard").fetchSemanticsNodes().isEmpty())
        composeRule.onNodeWithTag("chat-send").performClick()
        assertEquals(1, sends)
    }

    @Test
    fun zz_systemBackAfterKeyboardReturnsVoiceFirst() {
        val probe = PasteProbe(text = "draft")
        setBar(probe)
        val editor = requireEditor()
        composeRule.onNodeWithTag("chat-keyboard").performClick()
        composeRule.waitForIdle()
        val shown = awaitIme(editor, want = true)
        assertTrue("precondition: IME must be visible before Back. ${imeDebug(editor)}", shown)
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        val hidden = awaitIme(editor, want = false, timeoutMs = 4_000)
        val finishing = runCatching { composeRule.activity.isFinishing || composeRule.activity.isDestroyed }.getOrDefault(true)
        saveShot("keyboard-back")
        assertFalse("Back finished the activity instead of returning to voice-first", finishing)
        assertTrue("Back did not hide IME: ${imeDebug(editor)}", hidden)
        assertFalse("Back left showSoftInputOnFocus true: ${imeDebug(editor)} events=${probe.caretEvents}", editor.showSoftInputOnFocus)
        assertFalse(probe.keyboardRequested)
    }

    private fun paintMeasurementCursor(editor: EditText) {
        val cursor = GradientDrawable().apply {
            setColor(Color.MAGENTA)
            setSize(8, 8)
        }
        editor.setTextCursorDrawable(cursor)
        editor.isCursorVisible = true
        runCatching {
            val editorField = TextView::class.java.getDeclaredField("mEditor")
            editorField.isAccessible = true
            val editorObject = editorField.get(editor) ?: return@runCatching
            val drawableField = editorObject.javaClass.getDeclaredField("mDrawableForCursor")
            drawableField.isAccessible = true
            drawableField.set(editorObject, null)
            val blinkField = editorObject.javaClass.getDeclaredField("mBlink")
            blinkField.isAccessible = true
            val blink = blinkField.get(editorObject)
            if (blink != null) {
                val cancel = blink.javaClass.getDeclaredMethod("cancel")
                cancel.isAccessible = true
                cancel.invoke(blink)
            }
            val visible = editorObject.javaClass.getDeclaredField("mCursorVisible")
            visible.isAccessible = true
            visible.setBoolean(editorObject, true)
        }
        editor.requestLayout()
        editor.invalidate()
    }

    private fun awaitCaretShot(editor: EditText): Bitmap {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        var last: Bitmap? = null
        repeat(10) {
            composeRule.waitForIdle()
            val shot = automation.takeScreenshot()
            if (shot != null) {
                last = shot
                if (caretInk(shot, editor) != null) return shot
            }
            Thread.sleep(80)
        }
        return checkNotNull(last) { "uiAutomation.takeScreenshot returned null" }
    }

    private fun writeAssertedBitmap(shot: Bitmap, editor: EditText, report: CaretAlignReport) {
        val bounds = editorScreenBounds(shot, editor)
        val crop = Bitmap.createBitmap(shot, bounds.left, bounds.top, bounds.width(), bounds.height())
        val scaled = Bitmap.createScaledBitmap(crop, crop.width * 4, crop.height * 4, false)
        shellWrite("/data/local/tmp/voice-first-caret-asserted.png", pngBytes(shot))
        shellWrite("/data/local/tmp/voice-first-caret-asserted-editor.png", pngBytes(scaled))
        shellWrite("/data/local/tmp/voice-first-caret-metrics.txt", report.detail.toByteArray())
        Log.i("CaretAlign", report.detail)
    }

    private fun pngBytes(bitmap: Bitmap): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    private fun shellWrite(path: String, bytes: ByteArray) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val pipes = automation.executeShellCommandRwe("dd of=$path")
        val stdout = java.io.ByteArrayOutputStream()
        val stderr = java.io.ByteArrayOutputStream()
        val outThread = Thread {
            java.io.FileInputStream(pipes[0].fileDescriptor).use { it.copyTo(stdout) }
        }
        val errThread = Thread {
            java.io.FileInputStream(pipes[2].fileDescriptor).use { it.copyTo(stderr) }
        }
        outThread.start()
        errThread.start()
        FileOutputStream(pipes[1].fileDescriptor).use { it.write(bytes) }
        pipes[1].close()
        outThread.join(5_000)
        errThread.join(5_000)
        pipes[0].close()
        pipes[2].close()
        val check = automation.executeShellCommand("ls -l $path")
        val listing = java.io.FileInputStream(check.fileDescriptor).use { it.readBytes() }.decodeToString()
        check.close()
        check(listing.contains(path.substringAfterLast('/'))) {
            "asserted bitmap was not saved: listing='$listing' stderr='${stderr.toByteArray().decodeToString()}' stdout='${stdout.toByteArray().decodeToString()}'"
        }
    }

    private fun measureCaretAgainstHint(shot: Bitmap, editor: EditText, hintText: String): CaretAlignReport {
        val bounds = editorScreenBounds(shot, editor)
        val caret = caretInk(shot, editor)
        val hint = hintInk(shot, editor)
        val layout = editor.layout
        val line = layout?.getLineForOffset(editor.selectionStart.coerceAtLeast(0))
        val lineTop = if (layout != null && line != null) layout.getLineTop(line) else null
        val lineBaseline = if (layout != null && line != null) layout.getLineBaseline(line) else null
        val lineBottom = if (layout != null && line != null) layout.getLineBottom(line) else null
        val metrics = "hintText='$hintText' editor=${bounds.width()}x${bounds.height()} " +
            "pad=${editor.paddingLeft},${editor.paddingTop},${editor.paddingRight},${editor.paddingBottom} " +
            "extTop=${editor.extendedPaddingTop} extBottom=${editor.extendedPaddingBottom} " +
            "gravity=${editor.gravity} textSizePx=${editor.textSize} lineHeight=${editor.lineHeight} " +
            "includeFontPadding=${editor.includeFontPadding} minLines=${editor.minLines} " +
            "hint='${editor.hint}' layoutLines=${layout?.lineCount} line=$line " +
            "lineTop=$lineTop baseline=$lineBaseline lineBottom=$lineBottom sel=${editor.selectionStart}"
        if (caret == null || hint == null) {
            return CaretAlignReport(
                sameLine = false,
                firstLine = false,
                detail = "missing ink caret=$caret hint=$hint $metrics",
            )
        }
        val overlap = max(0, min(caret.bottom, hint.bottom) - max(caret.top, hint.top) + 1)
        val shorter = min(caret.height, hint.height)
        val centerDelta = kotlin.math.abs(caret.center - hint.center)
        val sameLine = overlap >= shorter * 0.8f && centerDelta <= max(4f, shorter * 0.12f)
        val firstLine = caret.center < bounds.height() * 0.42f && line == 0
        return CaretAlignReport(
            sameLine = sameLine,
            firstLine = firstLine,
            detail = "caret=$caret hintInk=$hint overlap=$overlap shorter=$shorter centerDelta=$centerDelta sameLine=$sameLine firstLine=$firstLine $metrics",
        )
    }

    private fun caretInk(shot: Bitmap, editor: EditText): InkSpan? =
        inkSpan(shot, editor) { color ->
            val red = Color.red(color)
            val green = Color.green(color)
            val blue = Color.blue(color)
            red > 170 && blue > 140 && red > green + 50 && blue > green + 30
        }

    private fun hintInk(shot: Bitmap, editor: EditText): InkSpan? {
        val bounds = editorScreenBounds(shot, editor)
        val background = shot.getPixel(
            (bounds.right - 8).coerceIn(0, shot.width - 1),
            (bounds.bottom - 8).coerceIn(0, shot.height - 1),
        )
        return inkSpan(shot, editor) { color ->
            val red = Color.red(color)
            val green = Color.green(color)
            val blue = Color.blue(color)
            val maxChannel = max(red, max(green, blue))
            val minChannel = min(red, min(green, blue))
            val saturation = if (maxChannel == 0) 0f else (maxChannel - minChannel) / maxChannel.toFloat()
            saturation < 0.28f && colorDistance(color, background) > 22
        }
    }

    private fun inkSpan(shot: Bitmap, editor: EditText, predicate: (Int) -> Boolean): InkSpan? {
        val bounds = editorScreenBounds(shot, editor)
        var top = Int.MAX_VALUE
        var bottom = -1
        var count = 0
        for (y in bounds.top until bounds.bottom) {
            var row = 0
            for (x in bounds.left until bounds.right) {
                if (predicate(shot.getPixel(x, y))) row += 1
            }
            if (row >= 1) {
                top = min(top, y)
                bottom = max(bottom, y)
                count += row
            }
        }
        if (bottom < top || count < 12) return null
        return InkSpan(top - bounds.top, bottom - bounds.top, count)
    }

    private fun editorScreenBounds(shot: Bitmap, editor: EditText): android.graphics.Rect {
        val loc = IntArray(2)
        editor.getLocationOnScreen(loc)
        val left = loc[0].coerceIn(0, shot.width - 2)
        val top = loc[1].coerceIn(0, shot.height - 2)
        val right = (loc[0] + editor.width).coerceIn(left + 1, shot.width)
        val bottom = (loc[1] + editor.height).coerceIn(top + 1, shot.height)
        return android.graphics.Rect(left, top, right, bottom)
    }

    private fun colorDistance(left: Int, right: Int): Int {
        val dr = Color.red(left) - Color.red(right)
        val dg = Color.green(left) - Color.green(right)
        val db = Color.blue(left) - Color.blue(right)
        return max(kotlin.math.abs(dr), max(kotlin.math.abs(dg), kotlin.math.abs(db)))
    }

    private data class InkSpan(val top: Int, val bottom: Int, val count: Int) {
        val center: Float get() = (top + bottom) / 2f
        val height: Int get() = bottom - top + 1
        override fun toString(): String = "y=$top..$bottom c=$count"
    }

    private data class CaretAlignReport(val sameLine: Boolean, val firstLine: Boolean, val detail: String)

    private fun setBar(probe: PasteProbe) {
        composeRule.setContent {
            MaterialTheme {
                ChatInputBar(
                    text = probe.text,
                    isRecording = probe.recording,
                    isTranscribing = false,
                    hasPreservedSpeechAudio = false,
                    isRetryingSpeech = false,
                    speechAudioLevel = probe.level,
                    isSpeechConfigured = true,
                    imageAttachments = emptyList(),
                    onTextChange = probe::onTextChange,
                    onSend = {},
                    onAddImages = {},
                    onRemoveImage = {},
                    onAbortSpeech = { probe.aborts += 1 },
                    onRetrySpeech = { probe.retries += 1 },
                    onDiscardSpeech = { probe.discards += 1 },
                    onToggleRecording = { probe.toggles += 1 },
                    voiceFirst = probe.voiceFirst,
                    keyboardRequested = probe.keyboardRequested,
                    ownerKey = probe.owner,
                    onPaste = { probe.pasteFrom(composeRule.activity) },
                    onKeyboard = {
                        probe.keyboardRequested = !probe.keyboardRequested
                    },
                    onKeyboardDismissed = { probe.keyboardRequested = false },
                    cursorRevision = probe.cursorRevision,
                    cursor = probe.cursor,
                    onCaret = probe::onCaret,
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun requireEditor(): EditText {
        val editor = findEditText(composeRule.activity.window.decorView)
        assertNotNull(editor)
        return editor!!
    }

    private fun seedClip(vararg parts: String) {
        composeRule.runOnUiThread {
            val clipboard = composeRule.activity.getSystemService(ClipboardManager::class.java)
            val first = parts.firstOrNull().orEmpty()
            val clip = ClipData.newPlainText("seed", first)
            parts.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
            clipboard.setPrimaryClip(clip)
        }
    }

    private fun awaitIme(editor: EditText, want: Boolean, timeoutMs: Long = 8_000): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            composeRule.waitForIdle()
            if (imeVisible(editor) == want) return true
            Thread.sleep(150)
        }
        return imeVisible(editor) == want
    }

    private fun diagnoseShow(editor: EditText): String {
        val imm = editor.context.getSystemService(InputMethodManager::class.java)
        var implicit = false
        var forced = false
        composeRule.runOnUiThread {
            editor.requestFocus()
            implicit = imm?.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT) == true
            forced = imm?.showSoftInput(editor, InputMethodManager.SHOW_FORCED) == true
        }
        Thread.sleep(700)
        composeRule.waitForIdle()
        return "implicitReturn=$implicit forcedReturn=$forced after=${imeDebug(editor)}"
    }

    private fun saveShot(name: String) {
        val remote = "/data/local/tmp/focus-voice-$name.png"
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.executeShellCommand("screencap -p $remote").use { descriptor ->
            java.io.FileInputStream(descriptor.fileDescriptor).readBytes()
        }
    }

    private fun imeVisible(editor: EditText): Boolean {
        val insets = editor.rootWindowInsets ?: return false
        return insets.isVisible(WindowInsets.Type.ime())
    }

    private fun imeDebug(editor: EditText): String {
        val insets = editor.rootWindowInsets
        val bottom = insets?.getInsets(WindowInsets.Type.ime())?.bottom
        val imm = editor.context.getSystemService(InputMethodManager::class.java)
        return "focused=${editor.isFocused} cursor=${editor.isCursorVisible} showOnFocus=${editor.showSoftInputOnFocus} " +
            "sel=${editor.selectionStart}..${editor.selectionEnd} imeVisible=${insets?.isVisible(WindowInsets.Type.ime())} " +
            "imeBottom=$bottom accepting=${imm?.isAcceptingText} active=${imm?.isActive(editor)} " +
            "shown=${editor.isShown} windowFocus=${editor.hasWindowFocus()} token=${editor.windowToken != null}"
    }

    private fun findEditText(view: View): EditText? {
        if (view is EditText) return view
        if (view !is ViewGroup) return null
        for (index in 0 until view.childCount) {
            findEditText(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun hasComposing(editor: EditText): Boolean {
        val editable = editor.text ?: return false
        val spans = editable.getSpans(0, editable.length, Any::class.java)
        return spans.any { (editable.getSpanFlags(it) and Spanned.SPAN_COMPOSING) != 0 }
    }

    private fun insideSurrogate(text: String, index: Int): Boolean {
        if (index <= 0 || index >= text.length) return false
        return text[index - 1].isHighSurrogate() && text[index].isLowSurrogate()
    }

    private fun fieldOrNull(target: Any, name: String): Any? {
        val field = target.javaClass.getDeclaredField(name)
        field.isAccessible = true
        return field.get(target)
    }

    private class PasteProbe(text: String) {
        var text by mutableStateOf(text)
        var keyboardRequested by mutableStateOf(false)
        var cursor by mutableStateOf(-1)
        var cursorRevision by mutableStateOf(0L)
        var owner by mutableStateOf("host:session")
        var level by mutableStateOf(0f)
        var voiceFirst by mutableStateOf(true)
        var recording by mutableStateOf(false)
        var focused = false
        var selStart = 0
        var selEnd = 0
        var composing = false
        var deferred: String? = null
        val caretEvents = mutableListOf<String>()
        var readParts: List<String>? = null
        var pasteClicks = 0
        var toggles = 0
        var aborts = 0
        var discards = 0
        var retries = 0
        var echoEditor = true

        fun onCaret(focused: Boolean, start: Int, end: Int, composing: Boolean) {
            this.focused = focused
            selStart = start
            selEnd = end
            this.composing = composing
            caretEvents.add("f=$focused $start..$end c=$composing")
            if (!composing) flush()
        }

        fun onTextChange(value: String) {
            if (echoEditor) text = value
        }

        fun pasteFrom(context: android.content.Context) {
            pasteClicks += 1
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            val clip = clipboard?.primaryClip
            val parts = if (clip == null) {
                emptyList()
            } else {
                (0 until clip.itemCount).map { index ->
                    clip.getItemAt(index).coerceToText(context)?.toString().orEmpty()
                }
            }
            readParts = parts
            val addition = joinClipboardText(listOf(joinClipboardText(parts)))
            if (addition.isEmpty()) return
            if (composing) {
                deferred = if (deferred.isNullOrEmpty()) addition else joinClipboardText(listOf(deferred!!, addition))
                return
            }
            apply(addition)
        }

        fun apply(addition: String) {
            val placement = pastePlacement(
                focused = focused,
                selectionStart = selStart,
                selectionEnd = selEnd,
                composing = false,
                speechOwnedEnd = null,
            )
            val inserted = when (placement) {
                PastePlacement.INSERT -> insertPaste(text, addition, selStart, selEnd)
                else -> appendPaste(text, addition).let { appended -> appended to appended.length }
            }
            cursor = inserted.second
            cursorRevision += 1
            text = inserted.first
        }

        fun flush() {
            val pending = deferred
            deferred = null
            if (!pending.isNullOrEmpty()) apply(pending)
        }
    }
}

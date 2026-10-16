package com.yage.opencode_client.ui.chat

import android.text.Editable
import android.text.SpanWatcher
import android.text.Spannable
import android.text.Spanned
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.widget.AppCompatEditText
import androidx.core.widget.TextViewCompat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.getSystemService
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnAttach
import com.yage.opencode_client.ui.shouldPushTextToEditor

@Composable
internal fun VoiceFirstEditor(
    text: String,
    ownerKey: String,
    keyboardRequested: Boolean,
    hint: String,
    textColor: Int,
    hintColor: Int,
    textSizeSp: Float,
    lineHeightSp: Float,
    letterSpacingSp: Float,
    onTextChange: (String) -> Unit,
    onCaret: (focused: Boolean, start: Int, end: Int, composing: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    cursorRevision: Long = 0,
    cursor: Int = -1,
    onKeyboardDismissed: () -> Unit = {},
) {
    val currentTextChange by rememberUpdatedState(onTextChange)
    val currentCaret by rememberUpdatedState(onCaret)
    val currentDismiss by rememberUpdatedState(onKeyboardDismissed)
    AndroidView(
        modifier = modifier.testTag("chat-input"),
        factory = { context ->
            ComposerEditText(context).apply {
                setShowSoftInputOnFocus(false)
                background = null
                setTextColor(textColor)
                setHintTextColor(hintColor)
                pinFirstLine(hint, textSizeSp, lineHeightSp, letterSpacingSp)
                minLines = 2
                maxLines = 6
                isFocusable = true
                isFocusableInTouchMode = true
                val watcher = object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                    override fun afterTextChanged(s: Editable?) {
                        watchComposing(s)
                        if (applying || !acceptUserEdits || boundOwner != callbackOwner) return
                        boundText = s?.toString().orEmpty()
                        this@apply.onTextChange(boundText.orEmpty())
                        reportCaret()
                    }
                }
                this.watcher = watcher
                addTextChangedListener(watcher)
                setOnFocusChangeListener { _, _ ->
                    if (!applying && !released) reportCaret()
                }
                this.onTextChange = { value ->
                    if (!released && acceptUserEdits && !applying && boundOwner == callbackOwner) currentTextChange(value)
                }
                this.onCaret = { focused, start, end, composing ->
                    if (!released) currentCaret(focused, start, end, composing)
                }
                this.onKeyboardDismissed = { if (!released) currentDismiss() }
                installImeWatcher()
            }
        },
        onRelease = { editText ->
            editText.releaseEditor()
        },
        update = { editText ->
            editText.onTextChange = { value ->
                if (!editText.released && editText.acceptUserEdits && !editText.applying && editText.boundOwner == editText.callbackOwner) {
                    currentTextChange(value)
                }
            }
            editText.onCaret = { focused, start, end, composing ->
                if (!editText.released) currentCaret(focused, start, end, composing)
            }
            editText.onKeyboardDismissed = { if (!editText.released) currentDismiss() }
            editText.setTextColor(textColor)
            editText.setHintTextColor(hintColor)
            editText.pinFirstLine(hint, textSizeSp, lineHeightSp, letterSpacingSp)
            val initialBind = editText.boundOwner == null
            val ownerChanged = editText.boundOwner != null && editText.boundOwner != ownerKey
            if (ownerChanged) {
                editText.acceptUserEdits = false
                editText.ownerGeneration += 1
                editText.boundOwner = ownerKey
                editText.callbackOwner = null
                editText.imeWasShown = false
                editText.appliedKeyboard = false
                editText.dismissedTicket = editText.keyboardTicket
                editText.setShowSoftInputOnFocus(false)
                editText.clearFocus()
                editText.hideIme()
                editText.context.getSystemService<InputMethodManager>()?.restartInput(editText)
                editText.post {
                    if (!editText.released) editText.onKeyboardDismissed?.invoke()
                }
            } else if (initialBind) {
                editText.boundOwner = ownerKey
            }
            val composing = editText.text?.hasComposingSpan() == true
            if (shouldPushTextToEditor(editText.boundText, text, composing, ownerChanged)) {
                editText.applying = true
                editText.setText(text)
                editText.watchComposing(editText.text)
                val length = editText.text?.length ?: 0
                val caret = if (ownerChanged) length else editText.selectionEnd.coerceAtMost(length)
                editText.setSelection(caret.coerceIn(0, length))
                editText.boundText = text
                editText.applying = false
            } else {
                editText.watchComposing(editText.text)
            }
            if (!ownerChanged && cursorRevision != editText.appliedCursorRevision && cursor >= 0) {
                editText.applying = true
                editText.setSelection(snapCursor(editText.text?.toString().orEmpty(), cursor))
                editText.appliedCursorRevision = cursorRevision
                editText.applying = false
                editText.reportCaret()
            }
            if (!keyboardRequested) editText.dismissedTicket = -1
            val suppressed = editText.keyboardTicket != 0 && editText.dismissedTicket == editText.keyboardTicket
            val wantKeyboard = keyboardRequested && !ownerChanged && !suppressed
            if (wantKeyboard && !editText.appliedKeyboard) editText.keyboardTicket += 1
            if (initialBind || editText.appliedKeyboard != wantKeyboard) {
                editText.appliedKeyboard = wantKeyboard
                editText.setShowSoftInputOnFocus(wantKeyboard)
                if (wantKeyboard) {
                    if (!editText.isFocused) editText.requestFocus()
                    editText.showImeWhenReady()
                } else if (!initialBind && !ownerChanged) {
                    editText.hideIme()
                }
            }
            editText.callbackOwner = ownerKey
            editText.acceptUserEdits = true
            editText.installImeWatcher()
        },
    )
}

internal fun snapCursor(text: String, cursor: Int): Int {
    val clamped = cursor.coerceIn(0, text.length)
    if (clamped in 1 until text.length && text[clamped].isLowSurrogate() && text[clamped - 1].isHighSurrogate()) {
        return (clamped + 1).coerceAtMost(text.length)
    }
    return clamped
}

private fun ComposerEditText.pinFirstLine(
    hint: String,
    textSizeSp: Float,
    lineHeightSp: Float,
    letterSpacingSp: Float,
) {
    if (background != null) background = null
    if (paddingLeft != 0 || paddingTop != 0 || paddingRight != 0 || paddingBottom != 0) {
        setPadding(0, 0, 0, 0)
    }
    val wantedGravity = Gravity.TOP or Gravity.START
    if (gravity != wantedGravity) gravity = wantedGravity
    if (includeFontPadding) includeFontPadding = false
    if (textSizeSp > 0f) {
        val sizePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, textSizeSp, resources.displayMetrics)
        if (kotlin.math.abs(textSize - sizePx) > 0.5f) {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
        }
        val em = letterSpacingSp / textSizeSp
        if (kotlin.math.abs(letterSpacing - em) > 0.002f) letterSpacing = em
    }
    if (lineHeightSp > 0f) {
        val linePx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            lineHeightSp,
            resources.displayMetrics,
        ).toInt()
        if (linePx > 0 && lineHeight != linePx) TextViewCompat.setLineHeight(this, linePx)
    }
    if (this.hint?.toString() != hint) this.hint = hint
}

private class ComposerEditText(context: android.content.Context) : AppCompatEditText(context) {
    var applying: Boolean = false
    var acceptUserEdits: Boolean = true
    var released: Boolean = false
    var boundOwner: String? = null
    var callbackOwner: String? = null
    var boundText: String? = null
    var appliedKeyboard: Boolean = false
    var appliedCursorRevision: Long = 0
    var ownerGeneration: Int = 0
    var keyboardTicket: Int = 0
    var dismissedTicket: Int = -1
    var imeWasShown: Boolean = false
    var watcher: TextWatcher? = null
    var onCaret: ((Boolean, Int, Int, Boolean) -> Unit)? = null
    var onTextChange: (String) -> Unit = {}
    var onKeyboardDismissed: (() -> Unit)? = null
    private var watcherInstalled = false
    private var insetsRoot: View? = null
    private var rootLayoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null
    private val layoutWatcher = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        noteIme(rootImeVisible())
    }
    private val spanWatcher = object : SpanWatcher {
        override fun onSpanAdded(text: Spannable, what: Any, start: Int, end: Int) {
            if (what !== this && !applying && isComposingSpan(text, what)) reportCaret()
        }

        override fun onSpanRemoved(text: Spannable, what: Any, start: Int, end: Int) {
            if (what !== this && !applying && !released) reportCaret()
        }

        override fun onSpanChanged(text: Spannable, what: Any, ostart: Int, oend: Int, nstart: Int, nend: Int) {
            if (what !== this && !applying && isComposingSpan(text, what)) reportCaret()
        }
    }

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        if (!applying && !released) reportCaret()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus && appliedKeyboard && !imeWasShown) showIme()
        noteIme(rootImeVisible())
    }

    override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
        val handled = super.onKeyPreIme(keyCode, event)
        if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            post { noteIme(rootImeVisible()) }
        }
        return handled
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val base = super.onCreateInputConnection(outAttrs) ?: return null
        val generation = ownerGeneration
        return object : InputConnectionWrapper(base, true) {
            override fun finishComposingText(): Boolean {
                if (generation != ownerGeneration || released) return true
                val result = super.finishComposingText()
                if (!applying) reportCaret()
                return result
            }

            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                if (generation != ownerGeneration || !acceptUserEdits || released) return true
                val result = super.commitText(text, newCursorPosition)
                if (!applying) reportCaret()
                return result
            }

            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                if (generation != ownerGeneration || !acceptUserEdits || released) return true
                val result = super.setComposingText(text, newCursorPosition)
                if (!applying) reportCaret()
                return result
            }
        }
    }

    fun watchComposing(editable: Editable?) {
        if (editable == null || editable.getSpanStart(spanWatcher) >= 0) return
        editable.setSpan(spanWatcher, 0, editable.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
    }

    fun installImeWatcher() {
        if (watcherInstalled) return
        watcherInstalled = true
        addOnLayoutChangeListener(layoutWatcher)
        doOnAttach {
            val root = rootView
            insetsRoot = root
            val listener = ViewTreeObserver.OnGlobalLayoutListener { noteIme(rootImeVisible()) }
            rootLayoutListener = listener
            root.viewTreeObserver.addOnGlobalLayoutListener(listener)
            ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
                noteIme(insets.isVisible(WindowInsetsCompat.Type.ime()))
                ViewCompat.onApplyWindowInsets(v, insets)
            }
            ViewCompat.requestApplyInsets(root)
        }
    }

    fun reportCaret() {
        if (released) return
        onCaret?.invoke(
            isFocused,
            selectionStart.coerceAtLeast(0),
            selectionEnd.coerceAtLeast(0),
            text?.hasComposingSpan() == true,
        )
    }

    fun showImeWhenReady() {
        if (!isAttachedToWindow) {
            doOnAttach { if (appliedKeyboard) showIme() }
        } else {
            post { if (appliedKeyboard && isAttachedToWindow) showIme() }
        }
    }

    fun showIme() {
        requestFocus()
        context.getSystemService<InputMethodManager>()?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    fun hideIme() {
        context.getSystemService<InputMethodManager>()?.hideSoftInputFromWindow(windowToken, 0)
    }

    fun releaseEditor() {
        released = true
        acceptUserEdits = false
        watcher?.let { removeTextChangedListener(it) }
        watcher = null
        onCaret = null
        onTextChange = {}
        onKeyboardDismissed = null
        setOnFocusChangeListener(null)
        removeOnLayoutChangeListener(layoutWatcher)
        rootLayoutListener?.let { listener ->
            val root = insetsRoot
            if (root != null && root.viewTreeObserver.isAlive) {
                root.viewTreeObserver.removeOnGlobalLayoutListener(listener)
            }
        }
        insetsRoot?.let { ViewCompat.setOnApplyWindowInsetsListener(it, null) }
        text?.removeSpan(spanWatcher)
        hideIme()
    }

    private fun noteIme(visible: Boolean) {
        if (released) return
        if (visible) {
            imeWasShown = true
            return
        }
        if (!imeWasShown || !appliedKeyboard) return
        imeWasShown = false
        appliedKeyboard = false
        dismissedTicket = keyboardTicket
        setShowSoftInputOnFocus(false)
        onKeyboardDismissed?.invoke()
    }

    private fun rootImeVisible(): Boolean {
        val insets = ViewCompat.getRootWindowInsets(this) ?: return false
        return insets.isVisible(WindowInsetsCompat.Type.ime())
    }
}

private fun isComposingSpan(text: Spannable, what: Any): Boolean =
    (text.getSpanFlags(what) and Spanned.SPAN_COMPOSING) != 0

private fun Editable.hasComposingSpan(): Boolean {
    val spans = getSpans(0, length, Any::class.java)
    return spans.any { (getSpanFlags(it) and Spanned.SPAN_COMPOSING) != 0 }
}

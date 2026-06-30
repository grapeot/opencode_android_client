package com.yage.opencode_client

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.yage.opencode_client.util.SettingsManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import javax.inject.Inject

@AndroidEntryPoint
class NfcWriterActivity : AppCompatActivity() {

    @Inject lateinit var settingsManager: SettingsManager

    private var nfcAdapter: NfcAdapter? = null
    private var pendingUri: String? = null
    private var writeInProgress = false

    companion object {
        private const val TAG = "NfcWriterActivity"
    }

    private val readerCallback = NfcAdapter.ReaderCallback { tag ->
        Log.d(TAG, "ReaderCallback: tag detected, tech list=${tag.techList.toList()}")
        if (writeInProgress) {
            Log.d(TAG, "Write already in progress, ignoring")
            return@ReaderCallback
        }
        val uri = pendingUri
        if (uri == null) {
            Log.w(TAG, "No pending URI, ignoring tag")
            return@ReaderCallback
        }
        writeInProgress = true
        CoroutineScope(Dispatchers.Main).launch {
            val success = writeNdef(tag, uri)
            Log.d(TAG, "writeNdef result: $success")
            Toast.makeText(
                this@NfcWriterActivity,
                if (success) getString(R.string.nfc_write_success) else getString(R.string.nfc_write_failed),
                Toast.LENGTH_SHORT
            ).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate")
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        val prompt = settingsManager.nfcPrompt
        val autoSend = settingsManager.nfcAutoSend
        Log.d(TAG, "prompt length=${prompt.length}, autoSend=$autoSend")

        if (prompt.isBlank()) {
            Log.w(TAG, "Prompt is empty, finishing")
            Toast.makeText(this, getString(R.string.nfc_prompt_empty), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val uri = buildUri(prompt, autoSend)
        val uriBytes = uri.toByteArray(Charsets.UTF_8)
        Log.d(TAG, "URI=$uri, ${uriBytes.size} bytes (max ${SettingsManager.NFC_TAG_MAX_BYTES})")

        if (uriBytes.size > SettingsManager.NFC_TAG_MAX_BYTES) {
            Log.e(TAG, "URI too large: ${uriBytes.size} > ${SettingsManager.NFC_TAG_MAX_BYTES}")
            Toast.makeText(this, getString(R.string.nfc_prompt_too_large, uriBytes.size, SettingsManager.NFC_TAG_MAX_BYTES), Toast.LENGTH_LONG).show()
            finish()
            return
        }

        if (nfcAdapter == null) {
            Log.e(TAG, "NFC adapter is null")
            Toast.makeText(this, getString(R.string.nfc_not_available), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        pendingUri = uri
        enableReaderMode()
        Toast.makeText(this, getString(R.string.nfc_hold_tag), Toast.LENGTH_LONG).show()
    }

    private fun buildUri(prompt: String, autoSend: Boolean): String {
        val encodedPrompt = URLEncoder.encode(prompt, "UTF-8")
        return "opencode://prompt?a=${if (autoSend) "1" else "0"}&p=$encodedPrompt"
    }

    private fun enableReaderMode() {
        // FLAG_READER_SKIP_NDEF_CHECK: don't let the system try to read NDEF first
        // (prevents the "Empty tag" popup on blank tags)
        val flags = NfcAdapter.FLAG_READER_NFC_A or
                    NfcAdapter.FLAG_READER_NFC_B or
                    NfcAdapter.FLAG_READER_NFC_F or
                    NfcAdapter.FLAG_READER_NFC_V or
                    NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK
        nfcAdapter?.enableReaderMode(this, readerCallback, flags, null)
        Log.d(TAG, "ReaderMode enabled (SKIP_NDEF_CHECK)")
    }

    private suspend fun writeNdef(tag: Tag, uri: String): Boolean = withContext(Dispatchers.IO) {
        Log.d(TAG, "writeNdef: starting, tag id=${tag.id?.toList()}")
        val ndef = Ndef.get(tag)
        if (ndef == null) {
            Log.e(TAG, "Ndef.get(tag) returned null — tag is not NDEF-formatted")
            // Could try NdefFormatable for blank tags, but NTAG215 should support Ndef
            return@withContext false
        }
        Log.d(TAG, "Ndef tag: maxSize=${ndef.maxSize}, isWritable=${ndef.isWritable}, type=${ndef.type}")
        try {
            ndef.connect()
            Log.d(TAG, "Ndef connected: isConnected=${ndef.isConnected}")
            if (!ndef.isConnected) {
                Log.e(TAG, "Failed to connect to tag")
                return@withContext false
            }

            // If tag already has NDEF, check if it's writable
            if (!ndef.isWritable) {
                Log.e(TAG, "Tag is read-only")
                return@withContext false
            }

            val record = NdefRecord.createUri(Uri.parse(uri))
            val message = NdefMessage(arrayOf(record))
            val messageSize = message.toByteArray().size
            Log.d(TAG, "NDEF message size=$messageSize, tag maxSize=${ndef.maxSize}")
            if (messageSize > ndef.maxSize) {
                Log.e(TAG, "Message too large for tag: $messageSize > ${ndef.maxSize}")
                return@withContext false
            }

            ndef.writeNdefMessage(message)
            Log.d(TAG, "writeNdefMessage succeeded")
            true
        } catch (e: Exception) {
            Log.e(TAG, "writeNdef exception", e)
            false
        } finally {
            try { ndef.close(); Log.d(TAG, "Ndef closed") } catch (_: Exception) {}
        }
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableReaderMode(this)
        Log.d(TAG, "ReaderMode disabled")
    }
}
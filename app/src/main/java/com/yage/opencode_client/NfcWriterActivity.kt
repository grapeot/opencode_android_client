package com.yage.opencode_client

import android.app.PendingIntent
import android.content.Intent
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
        Toast.makeText(this, getString(R.string.nfc_hold_tag), Toast.LENGTH_LONG).show()
    }

    override fun onResume() {
        super.onResume()
        enableForegroundDispatch()
        Log.d(TAG, "ForegroundDispatch enabled")
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
        Log.d(TAG, "ForegroundDispatch disabled")
    }

    private fun enableForegroundDispatch() {
        // Key fix: construct a NEW Intent targeting this activity with SINGLE_TOP,
        // so the system routes the tag intent back to onNewIntent instead of
        // creating a new instance or dropping it.
        val tagIntent = Intent(this, NfcWriterActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            tagIntent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        // null filters + null techLists = catch ALL tag types in foreground,
        // which suppresses the system's default "Empty Tag" handler.
        nfcAdapter?.enableForegroundDispatch(this, pendingIntent, null, null)
    }

    private fun buildUri(prompt: String, autoSend: Boolean): String {
        val encodedPrompt = URLEncoder.encode(prompt, "UTF-8")
        return "opencode://prompt?a=${if (autoSend) "1" else "0"}&p=$encodedPrompt"
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Log.d(TAG, "onNewIntent received, action=${intent.action}")

        if (writeInProgress) {
            Log.d(TAG, "Write already in progress, ignoring")
            return
        }

        val tag: Tag? = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        }

        val uri = pendingUri
        if (uri == null) {
            Log.w(TAG, "No pending URI, ignoring")
            return
        }
        if (tag == null) {
            Log.e(TAG, "No Tag in intent extras")
            Toast.makeText(this, getString(R.string.nfc_write_failed), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        Log.d(TAG, "Tag detected, tech list=${tag.techList.toList()}")
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

    private suspend fun writeNdef(tag: Tag, uri: String): Boolean = withContext(Dispatchers.IO) {
        Log.d(TAG, "writeNdef: starting, tag id=${tag.id?.toList()}")
        val ndef = Ndef.get(tag)
        if (ndef == null) {
            Log.e(TAG, "Ndef.get(tag) returned null — tag is not NDEF-formatted")
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
}
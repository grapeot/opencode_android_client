package com.yage.opencode_client

import android.app.Activity
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.net.Uri
import android.os.Bundle
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        val prompt = settingsManager.nfcPrompt
        val autoSend = settingsManager.nfcAutoSend

        if (prompt.isBlank()) {
            Toast.makeText(this, getString(R.string.nfc_prompt_empty), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val uri = buildUri(prompt, autoSend)
        val uriBytes = uri.toByteArray(Charsets.UTF_8)
        if (uriBytes.size > SettingsManager.NFC_TAG_MAX_BYTES) {
            Toast.makeText(this, getString(R.string.nfc_prompt_too_large, uriBytes.size, SettingsManager.NFC_TAG_MAX_BYTES), Toast.LENGTH_LONG).show()
            finish()
            return
        }

        if (nfcAdapter == null) {
            Toast.makeText(this, getString(R.string.nfc_not_available), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        pendingUri = uri
        enableWriteMode()
        Toast.makeText(this, getString(R.string.nfc_hold_tag), Toast.LENGTH_LONG).show()
    }

    private fun buildUri(prompt: String, autoSend: Boolean): String {
        val encodedPrompt = URLEncoder.encode(prompt, "UTF-8")
        return "opencode://prompt?a=${if (autoSend) "1" else "0"}&p=$encodedPrompt"
    }

    private fun enableWriteMode() {
        nfcAdapter?.enableForegroundDispatch(
            this,
            android.app.PendingIntent.getActivity(
                this,
                0,
                intent,
                android.app.PendingIntent.FLAG_MUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
            ),
            null,
            null
        )
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        val tag: Tag? = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        }
        val uri = pendingUri ?: return
        if (tag == null) {
            Toast.makeText(this, getString(R.string.nfc_write_failed), Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        CoroutineScope(Dispatchers.Main).launch {
            val success = writeNdef(tag, uri)
            Toast.makeText(
                this@NfcWriterActivity,
                if (success) getString(R.string.nfc_write_success) else getString(R.string.nfc_write_failed),
                Toast.LENGTH_SHORT
            ).show()
            finish()
        }
    }

    private suspend fun writeNdef(tag: Tag, uri: String): Boolean = withContext(Dispatchers.IO) {
        val ndef = Ndef.get(tag) ?: return@withContext false
        try {
            ndef.connect()
            if (!ndef.isConnected) return@withContext false
            val record = NdefRecord.createUri(Uri.parse(uri))
            val message = NdefMessage(arrayOf(record))
            if (message.toByteArray().size > ndef.maxSize) return@withContext false
            ndef.writeNdefMessage(message)
            true
        } catch (e: Exception) {
            false
        } finally {
            try { ndef.close() } catch (_: Exception) {}
        }
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
    }
}
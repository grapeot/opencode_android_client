package com.yage.opencode_client.ui.util

import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.mikepenz.markdown.model.ImageData
import com.mikepenz.markdown.model.ImageTransformer

// MikePenz defaults to NoOpImageTransformerImpl (returns null), so data URI images
// show only a placeholder. This handles data:image/...;base64,... without Coil.
object DataUriImageTransformer : ImageTransformer {

    private const val TAG = "DataUriImage"

    private val dataUriPattern = Regex("""^data:([^;]+);base64,(.+)$""", RegexOption.DOT_MATCHES_ALL)

    @Composable
    override fun transform(link: String): ImageData? {
        val match = dataUriPattern.matchEntire(link) ?: return null
        val mimeType = match.groupValues[1]
        val base64 = match.groupValues[2]

        if (!mimeType.startsWith("image/")) return null

        return try {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bitmap == null) {
                Log.w(TAG, "Failed to decode image (mimeType=$mimeType, ${bytes.size} bytes)")
                return null
            }
            ImageData(
                painter = androidx.compose.ui.graphics.painter.BitmapPainter(bitmap.asImageBitmap()),
                contentDescription = null,
                modifier = Modifier.fillMaxWidth(),
                alignment = Alignment.Center,
                contentScale = ContentScale.FillWidth
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load data URI image", e)
            null
        }
    }
}

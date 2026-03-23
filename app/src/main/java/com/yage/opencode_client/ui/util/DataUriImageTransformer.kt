package com.yage.opencode_client.ui.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import com.mikepenz.markdown.model.ImageData
import com.mikepenz.markdown.model.ImageTransformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "DataUriImage"

private val dataUriPattern = Regex("""^data:([^;]+);base64,(.+)$""", RegexOption.DOT_MATCHES_ALL)

internal fun bitmapToImageData(bitmap: Bitmap): ImageData = ImageData(
    painter = BitmapPainter(bitmap.asImageBitmap()),
    contentDescription = null,
    modifier = Modifier.fillMaxWidth(),
    alignment = Alignment.Center,
    contentScale = ContentScale.FillWidth
)

// MikePenz defaults to NoOpImageTransformerImpl (returns null), so images show
// only a placeholder. This handles data:image/...;base64,... and HTTPS URLs.
object DataUriImageTransformer : ImageTransformer {

    @Composable
    override fun transform(link: String): ImageData? {
        val dataMatch = dataUriPattern.matchEntire(link)
        if (dataMatch != null) {
            val mimeType = dataMatch.groupValues[1]
            if (!mimeType.startsWith("image/")) return null
            return try {
                val bytes = Base64.decode(dataMatch.groupValues[2], Base64.DEFAULT)
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                bitmap?.let { bitmapToImageData(it) }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load data URI image", e)
                null
            }
        }

        if (link.startsWith("https://") || link.startsWith("http://")) {
            return HttpImageHolder.load(link)
        }

        return null
    }
}

object HttpImageHolder {
    private val cachedBitmaps = mutableStateMapOf<String, Bitmap>()
    private val inflightUrls = mutableSetOf<String>()

    @Composable
    fun load(url: String): ImageData? {
        return cachedBitmaps[url]?.let(::bitmapToImageData)
    }

    suspend fun prefetch(url: String) {
        if (cachedBitmaps.containsKey(url) || !inflightUrls.add(url)) return
        try {
            val bitmap = withContext(Dispatchers.IO) {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = 10000
                connection.readTimeout = 15000
                connection.connect()
                connection.inputStream.use { BitmapFactory.decodeStream(it) }
            }
            if (bitmap != null) {
                cachedBitmaps[url] = bitmap
            } else {
                Log.w(TAG, "Decoded null bitmap for markdown image: ${url.take(120)}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to prefetch image from $url", e)
        } finally {
            inflightUrls.remove(url)
        }
    }
}

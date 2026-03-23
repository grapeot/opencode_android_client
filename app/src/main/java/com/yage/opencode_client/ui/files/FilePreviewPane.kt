package com.yage.opencode_client.ui.files

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.mikepenz.markdown.m3.Markdown
import com.yage.opencode_client.data.model.FileContent
import com.yage.opencode_client.data.repository.OpenCodeRepository
import com.yage.opencode_client.ui.theme.markdownTypographyCompact
import com.yage.opencode_client.ui.util.DataUriImageTransformer
import com.yage.opencode_client.ui.util.HttpImageHolder
import com.yage.opencode_client.ui.util.MarkdownImageResolver
import java.io.File
import kotlin.math.max
import kotlin.math.min

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FilePreviewPane(
    path: String,
    fileContent: FileContent,
    repository: OpenCodeRepository,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val content = fileContent.content.orEmpty()
    val previewKind = remember(path, fileContent.isBinary) {
        FilePreviewUtils.previewContentKind(path, fileContent.isBinary)
    }
    val imagePayload = remember(path, content) {
        if (previewKind == FilePreviewUtils.PreviewContentKind.IMAGE) decodeImagePayload(content) else null
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(path.substringAfterLast('/'), style = MaterialTheme.typography.titleSmall) },
            navigationIcon = {
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            },
            actions = {
                if (imagePayload != null) {
                    IconButton(onClick = { shareImage(context, path, imagePayload.bytes) }) {
                        Icon(Icons.Default.Share, contentDescription = "Share")
                    }
                }
            }
        )

        HorizontalDivider()

        when {
            imagePayload != null -> ImageViewer(bitmap = imagePayload.bitmap)
            previewKind == FilePreviewUtils.PreviewContentKind.MARKDOWN -> PreviewMarkdown(
                content = content,
                filePath = path,
                repository = repository
            )
            previewKind == FilePreviewUtils.PreviewContentKind.BINARY -> PreviewBinaryFallback()
            else -> PreviewPlainText(content = content)
        }
    }
}

@Composable
private fun PreviewMarkdown(
    content: String,
    filePath: String,
    repository: OpenCodeRepository
) {
    var resolvedContent by remember(content, filePath) { mutableStateOf<String?>(null) }

    LaunchedEffect(content, filePath, repository) {
        resolvedContent = null
        resolvedContent = MarkdownImageResolver.resolveImages(
            text = content,
            markdownFilePath = filePath,
            fetchContent = { path -> repository.getFileContent(path).getOrThrow() }
        )
        val finalText = resolvedContent ?: content
        val httpsUrls = """!\[[^\]]*\]\((https?://[^)]+)\)""".toRegex().findAll(finalText).map { it.groupValues[1] }.toList().distinct()
        for (url in httpsUrls) {
            HttpImageHolder.prefetch(url)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp)
    ) {
        item {
            Markdown(
                content = resolvedContent ?: content,
                typography = markdownTypographyCompact(),
                modifier = Modifier.fillMaxWidth(),
                imageTransformer = DataUriImageTransformer
            )
        }
    }
}

@Composable
private fun PreviewBinaryFallback() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "Binary file preview is not supported.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline
        )
    }
}

@Composable
private fun PreviewPlainText(content: String) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp)
    ) {
        item {
            Text(
                text = content,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
internal fun ImageViewer(bitmap: Bitmap) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val containerWidth = constraints.maxWidth.toFloat()
        val containerHeight = constraints.maxHeight.toFloat()
        val fitRatio = remember(containerWidth, containerHeight, bitmap.width, bitmap.height) {
            if (bitmap.width == 0 || bitmap.height == 0 || containerWidth == 0f || containerHeight == 0f) {
                1f
            } else {
                min(containerWidth / bitmap.width.toFloat(), containerHeight / bitmap.height.toFloat())
            }
        }
        val fittedWidth = bitmap.width * fitRatio
        val fittedHeight = bitmap.height * fitRatio
        val maxDoubleTapScale = remember(fitRatio) { (1f / fitRatio).coerceIn(2f, 5f) }

        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }

        fun clampOffset(candidate: Offset, targetScale: Float): Offset {
            val maxX = max(0f, (fittedWidth * targetScale - containerWidth) / 2f)
            val maxY = max(0f, (fittedHeight * targetScale - containerHeight) / 2f)
            return Offset(
                x = candidate.x.coerceIn(-maxX, maxX),
                y = candidate.y.coerceIn(-maxY, maxY)
            )
        }

        val transformState = rememberTransformableState { zoomChange, panChange, _ ->
            val newScale = (scale * zoomChange).coerceIn(1f, 5f)
            scale = newScale
            offset = clampOffset(
                candidate = offset + if (newScale > 1f) panChange else Offset.Zero,
                targetScale = newScale
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .transformable(state = transformState)
                .pointerInput(maxDoubleTapScale) {
                    detectTapGestures(
                        onDoubleTap = {
                            if (scale > 1.01f) {
                                scale = 1f
                                offset = Offset.Zero
                            } else {
                                scale = maxDoubleTapScale
                                offset = Offset.Zero
                            }
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    }
            )
        }
    }
}

private data class DecodedImagePayload(
    val bytes: ByteArray,
    val bitmap: Bitmap
)

private fun decodeImagePayload(rawContent: String): DecodedImagePayload? {
    val candidates = listOf(
        rawContent,
        rawContent.replace("\n", "").replace("\r", "").replace(" ", "")
    ).distinct()

    for (candidate in candidates) {
        val bytes = try {
            Base64.decode(candidate, Base64.DEFAULT)
        } catch (_: IllegalArgumentException) {
            continue
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: continue
        return DecodedImagePayload(bytes = bytes, bitmap = bitmap)
    }

    return null
}

private fun shareImage(context: Context, path: String, bytes: ByteArray) {
    val sharedDir = File(context.cacheDir, "shared").apply { mkdirs() }
    val fileName = path.substringAfterLast('/').ifBlank { "image" }
    val shareFile = File(sharedDir, fileName)
    shareFile.writeBytes(bytes)

    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        shareFile
    )

    val intent = Intent(Intent.ACTION_SEND).apply {
        type = FilePreviewUtils.imageMimeType(path)
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    context.startActivity(
        Intent.createChooser(intent, "Share image").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

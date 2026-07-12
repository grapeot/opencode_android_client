package com.yage.opencode_client.ui.files

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import com.mikepenz.markdown.m3.Markdown
import com.yage.opencode_client.ui.theme.markdownTypographyCompact
import com.yage.opencode_client.ui.util.DataUriImageTransformer

@Composable
internal fun WorkspaceLinkMarkdown(
    content: String,
    modifier: Modifier = Modifier,
    onLinkClick: (String) -> Unit
) {
    CompositionLocalProvider(
        LocalUriHandler provides object : UriHandler {
            override fun openUri(uri: String) {
                onLinkClick(uri)
            }
        }
    ) {
        Markdown(
            content = content,
            typography = markdownTypographyCompact(),
            modifier = modifier,
            imageTransformer = DataUriImageTransformer
        )
    }
}

package com.yage.opencode_client

import com.yage.opencode_client.data.model.FileContent
import com.yage.opencode_client.ui.util.MarkdownImageResolver
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownImageResolverTest {
    @Test
    fun `normalizeImagePath resolves markdown relative path into workspace path`() {
        val resolved = MarkdownImageResolver.normalizeImagePath(
            rawUrl = "./output/screenshot.png",
            markdownFilePath = "/workspace/docs/guide/readme.md",
            workspaceDirectory = "/workspace"
        )

        assertEquals("docs/guide/output/screenshot.png", resolved)
    }

    @Test
    fun `resolveImages replaces relative image markdown with data uri`() = runTest {
        val markdown = "Look here ![alt](./output/screenshot.png)"

        val resolved = MarkdownImageResolver.resolveImages(
            text = markdown,
            markdownFilePath = "/workspace/docs/readme.md",
            workspaceDirectory = "/workspace",
            fetchContent = { path ->
                assertEquals("docs/output/screenshot.png", path)
                FileContent(type = "binary", content = "QUJD\n")
            }
        )

        assertTrue(resolved.contains("![alt](data:image/png;base64,QUJD)"))
    }
}

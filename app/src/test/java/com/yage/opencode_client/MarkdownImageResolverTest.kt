package com.yage.opencode_client

import com.yage.opencode_client.data.model.FileContent
import com.yage.opencode_client.ui.util.MarkdownImageResolver
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownImageResolverTest {
    @Test
    fun `normalizeStandaloneImageBlocks separates image from caption`() {
        val markdown = """
            ![雍和宫入口](https://example.com/yonghe.jpg)
            *图注文字*
        """.trimIndent()

        val normalized = MarkdownImageResolver.normalizeStandaloneImageBlocks(markdown)

        assertEquals(
            """
                ![雍和宫入口](https://example.com/yonghe.jpg)

                *图注文字*
            """.trimIndent(),
            normalized
        )
    }

    @Test
    fun `normalizeStandaloneImageBlocks leaves existing blank line`() {
        val markdown = """
            ![chart](assets/chart.png)

            Caption
        """.trimIndent()

        assertEquals(markdown, MarkdownImageResolver.normalizeStandaloneImageBlocks(markdown))
    }

    @Test
    fun `normalizeStandaloneImageBlocks keeps true inline image text unchanged`() {
        val markdown = "Before ![inline](assets/icon.png) after"

        assertEquals(markdown, MarkdownImageResolver.normalizeStandaloneImageBlocks(markdown))
    }

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

    @Test
    fun `normalizeImagePath restores leading slash for unix absolute markdown paths`() {
        val resolved = MarkdownImageResolver.normalizeImagePath(
            rawUrl = "./context_infrastructure_before_after_visual_20260324_v2.png_0.jpg",
            markdownFilePath = "Users/grapeot/co/knowledge_working/contexts/thought_review/context_operating_model_high_level_spec_20260323.md",
            workspaceDirectory = null
        )

        assertEquals(
            "/Users/grapeot/co/knowledge_working/contexts/thought_review/context_infrastructure_before_after_visual_20260324_v2.png_0.jpg",
            resolved
        )
    }

    @Test
    fun `normalizeImagePath resolves ordinary relative path without forcing root slash when workspace is absent`() {
        val resolved = MarkdownImageResolver.normalizeImagePath(
            rawUrl = "images/screenshot.png",
            markdownFilePath = "Users/grapeot/co/knowledge_working/contexts/thought_review/context_operating_model_high_level_spec_20260323.md",
            workspaceDirectory = null
        )

        assertEquals(
            "/Users/grapeot/co/knowledge_working/contexts/thought_review/images/screenshot.png",
            resolved
        )
    }

    @Test
    fun `normalizeImagePath restores leading slash for parent traversal from unix absolute markdown path`() {
        val resolved = MarkdownImageResolver.normalizeImagePath(
            rawUrl = "../shared/diagram.png",
            markdownFilePath = "Users/grapeot/co/knowledge_working/contexts/thought_review/context_operating_model_high_level_spec_20260323.md",
            workspaceDirectory = null
        )

        assertEquals(
            "/Users/grapeot/co/knowledge_working/contexts/shared/diagram.png",
            resolved
        )
    }

    @Test
    fun `resolveImages preserves unix absolute path for file preview style markdown path`() = runTest {
        val markdown = "Look here ![alt](./context_infrastructure_before_after_visual_20260324_v2.png_0.jpg)"

        val resolved = MarkdownImageResolver.resolveImages(
            text = markdown,
            markdownFilePath = "Users/grapeot/co/knowledge_working/contexts/thought_review/context_operating_model_high_level_spec_20260323.md",
            workspaceDirectory = null,
            fetchContent = { path ->
                assertEquals(
                    "/Users/grapeot/co/knowledge_working/contexts/thought_review/context_infrastructure_before_after_visual_20260324_v2.png_0.jpg",
                    path
                )
                FileContent(type = "binary", content = "QUJD\n")
            }
        )

        assertTrue(resolved.contains("![alt](data:image/jpeg;base64,QUJD)"))
    }
}

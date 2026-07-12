package com.yage.opencode_client

import com.yage.opencode_client.ui.files.WorkspaceMarkdownLinkResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceMarkdownLinkResolverTest {
    private val workspace = "/Users/me/project"

    @Test
    fun httpAndHttpsOpenExternally() {
        assertEquals(
            WorkspaceMarkdownLinkResolver.Resolution.External("https://example.com/a?b=1#c"),
            WorkspaceMarkdownLinkResolver.resolve("https://example.com/a?b=1#c", workspace)
        )
        assertEquals(
            WorkspaceMarkdownLinkResolver.Resolution.External("http://example.com"),
            WorkspaceMarkdownLinkResolver.resolve("http://example.com", workspace)
        )
    }

    @Test
    fun relativeLinksResolveFromWorkspaceRootByDefault() {
        assertEquals(
            WorkspaceMarkdownLinkResolver.Resolution.Preview("README.md"),
            WorkspaceMarkdownLinkResolver.resolve("README.md", workspace)
        )
        assertEquals(
            WorkspaceMarkdownLinkResolver.Resolution.Preview("docs/a.md"),
            WorkspaceMarkdownLinkResolver.resolve("./docs/a.md", workspace)
        )
    }

    @Test
    fun relativeLinksRequireWorkspace() {
        assertRejected(WorkspaceMarkdownLinkResolver.resolve("README.md", null))
    }

    @Test
    fun relativeLinksCanResolveFromMarkdownFileDirectory() {
        assertEquals(
            WorkspaceMarkdownLinkResolver.Resolution.Preview("docs/b.md"),
            WorkspaceMarkdownLinkResolver.resolve("b.md", workspace, sourceMarkdownPath = "docs/a.md")
        )
        assertEquals(
            WorkspaceMarkdownLinkResolver.Resolution.Preview("README.md"),
            WorkspaceMarkdownLinkResolver.resolve("../README.md", workspace, sourceMarkdownPath = "docs/a.md")
        )
    }

    @Test
    fun workspaceAbsolutePathResolvesToRelativePreviewPath() {
        assertEquals(
            WorkspaceMarkdownLinkResolver.Resolution.Preview("docs/a.md"),
            WorkspaceMarkdownLinkResolver.resolve("/Users/me/project/docs/a.md", workspace)
        )
    }

    @Test
    fun fileUriInsideWorkspaceResolvesToRelativePreviewPath() {
        assertEquals(
            WorkspaceMarkdownLinkResolver.Resolution.Preview("docs/a.md"),
            WorkspaceMarkdownLinkResolver.resolve("file:///Users/me/project/docs/a.md", workspace)
        )
        assertEquals(
            WorkspaceMarkdownLinkResolver.Resolution.Preview("docs/a.md"),
            WorkspaceMarkdownLinkResolver.resolve("file://localhost/Users/me/project/docs/a.md", workspace)
        )
    }

    @Test
    fun fragmentOnlyDoesNotRequestFile() {
        assertEquals(
            WorkspaceMarkdownLinkResolver.Resolution.Ignored,
            WorkspaceMarkdownLinkResolver.resolve("#anchor", workspace)
        )
    }

    @Test
    fun fileAnchorOpensFileForMvp() {
        assertEquals(
            WorkspaceMarkdownLinkResolver.Resolution.Preview("README.md"),
            WorkspaceMarkdownLinkResolver.resolve("README.md#anchor", workspace)
        )
    }

    @Test
    fun rejectsTraversalAndEncodedTraversal() {
        assertRejected(WorkspaceMarkdownLinkResolver.resolve("../secret.md", workspace))
        assertRejected(WorkspaceMarkdownLinkResolver.resolve("docs/%2e%2e/secret.md", workspace))
        assertRejected(WorkspaceMarkdownLinkResolver.resolve("docs/%252e%252e%252fsecret.md", workspace))
        assertRejected(WorkspaceMarkdownLinkResolver.resolve("file:///Users/me/project/%2e%2e/secret.md", workspace))
    }

    @Test
    fun rejectsPrefixCollisionOutsideWorkspace() {
        assertRejected(WorkspaceMarkdownLinkResolver.resolve("/Users/me/project-other/README.md", workspace))
        assertRejected(WorkspaceMarkdownLinkResolver.resolve("file:///Users/me/project-other/README.md", workspace))
    }

    @Test
    fun rejectsNonLocalFileAuthorityAndUnsafeSchemes() {
        assertRejected(WorkspaceMarkdownLinkResolver.resolve("file://example.com/Users/me/project/README.md", workspace))
        assertRejected(WorkspaceMarkdownLinkResolver.resolve("javascript:alert(1)", workspace))
        assertRejected(WorkspaceMarkdownLinkResolver.resolve("data:text/plain,hi", workspace))
        assertRejected(WorkspaceMarkdownLinkResolver.resolve("mailto:a@example.com", workspace))
    }

    private fun assertRejected(resolution: WorkspaceMarkdownLinkResolver.Resolution) {
        assertTrue(resolution is WorkspaceMarkdownLinkResolver.Resolution.Rejected)
    }
}

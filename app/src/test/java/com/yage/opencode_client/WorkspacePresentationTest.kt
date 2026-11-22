package com.yage.opencode_client

import com.yage.opencode_client.ui.AppWidthClass
import com.yage.opencode_client.ui.EffectiveLayout
import com.yage.opencode_client.ui.LayoutPreference
import com.yage.opencode_client.ui.WorkspacePresentation
import com.yage.opencode_client.ui.resolveWorkspacePresentation
import org.junit.Assert.*
import org.junit.Test

class WorkspacePresentationTest {
    @Test fun mediumHeightDecidesSidebarWithoutChangingPreviewRouting() {
        val normal = resolveWorkspacePresentation(LayoutPreference.AUTOMATIC, AppWidthClass.MEDIUM, false)
        val short = resolveWorkspacePresentation(LayoutPreference.AUTOMATIC, AppWidthClass.MEDIUM, true)
        assertEquals(WorkspacePresentation.SIDEBAR, normal)
        assertEquals(WorkspacePresentation.COMPACT, short)
        assertEquals(EffectiveLayout.COMPACT, normal.previewLayout)
        assertFalse(normal.showsFilesColumn(pinned = true, previewOpen = true))
    }

    @Test fun wideKeepsChatSpaceUntilFilesArePinnedOrPreviewedAndFocusStillWins() {
        val wide = resolveWorkspacePresentation(LayoutPreference.AUTOMATIC, AppWidthClass.EXPANDED, false)
        assertEquals(EffectiveLayout.REGULAR, wide.previewLayout)
        assertFalse(wide.showsFilesColumn(pinned = false, previewOpen = false))
        assertTrue(wide.showsFilesColumn(pinned = true, previewOpen = false))
        assertTrue(wide.showsFilesColumn(pinned = false, previewOpen = true))
        assertEquals(WorkspacePresentation.COMPACT,
            resolveWorkspacePresentation(LayoutPreference.FOCUS, AppWidthClass.EXPANDED, false))
    }
}

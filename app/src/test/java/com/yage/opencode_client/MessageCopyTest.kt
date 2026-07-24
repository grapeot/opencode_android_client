package com.yage.opencode_client

import com.yage.opencode_client.data.model.Part
import com.yage.opencode_client.ui.chat.copyableMessageText
import org.junit.Assert.assertEquals
import org.junit.Test

class MessageCopyTest {
    @Test
    fun `copyable message text joins text parts and excludes other parts`() {
        val parts = listOf(
            Part(id = "text-1", type = "text", text = "First **paragraph**"),
            Part(id = "tool-1", type = "tool", text = "tool output"),
            Part(id = "text-2", type = "text", text = "- second\n- third")
        )

        assertEquals(
            "First **paragraph**\n\n- second\n- third",
            copyableMessageText(parts)
        )
    }
}

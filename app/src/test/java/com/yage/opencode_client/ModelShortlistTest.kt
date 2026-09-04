package com.yage.opencode_client

import com.yage.opencode_client.data.model.ConfigProvider
import com.yage.opencode_client.data.model.ModelShortlistItem
import com.yage.opencode_client.data.model.ProviderModel
import com.yage.opencode_client.data.model.ProviderModelCapabilities
import com.yage.opencode_client.data.model.ProviderModelOutput
import com.yage.opencode_client.ui.CatalogModel
import com.yage.opencode_client.ui.addModelToShortlist
import com.yage.opencode_client.ui.buildCatalog
import com.yage.opencode_client.ui.decodeShortlist
import com.yage.opencode_client.ui.encodeShortlist
import com.yage.opencode_client.ui.moveShortlistItem
import com.yage.opencode_client.ui.migrateToIdBasedModelSelection
import com.yage.opencode_client.ui.refreshShortlistDisplayNames
import com.yage.opencode_client.ui.reanchorSelectedModelIndex
import com.yage.opencode_client.ui.removeShortlistItem
import com.yage.opencode_client.ui.seedShortlistFromPresets
import com.yage.opencode_client.ui.suggestedShortName
import com.yage.opencode_client.ui.updateShortlistShortName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelShortlistTest {

    private fun item(
        providerId: String = "openai",
        modelId: String = "gpt-5.6-sol",
        displayName: String = "GPT-5.6 Sol",
        shortName: String = "GPT"
    ) = ModelShortlistItem(providerId, modelId, displayName, shortName)

    @Test
    fun `suggestedShortName applies known mappings`() {
        assertEquals("DS-L", suggestedShortName("DeepSeek Local"))
        assertEquals("GPT", suggestedShortName("GPT-5.6 Sol"))
        assertEquals("Gemini", suggestedShortName("Gemini 3.7 Flash"))
        assertEquals("Grok", suggestedShortName("Grok 4.6"))
        assertEquals("Qwen", suggestedShortName("Qwen 3.8 27B"))
    }

    @Test
    fun `suggestedShortName falls back to first word for unknown names`() {
        assertEquals("My", suggestedShortName("My Custom Model"))
        assertEquals("GLM-5.3", suggestedShortName("GLM-5.3"))
    }

    @Test
    fun `seedShortlistFromPresets has nine entries with stable ids`() {
        val seed = seedShortlistFromPresets()
        assertEquals(9, seed.size)
        assertEquals("zai-coding-plan/glm-5.3", seed[0].id)
        assertEquals("openai/gpt-5.6-sol", seed[1].id)
        assertEquals("google/gemini-3.7-flash", seed[2].id)
        assertTrue(seed.all { it.shortName.isNotEmpty() })
    }

    @Test
    fun `buildCatalog scopes to connected providers and chat-capable models`() {
        val providers = listOf(
            ConfigProvider(
                id = "openai",
                name = "OpenAI",
                models = mapOf(
                    "gpt-5.6-sol" to ProviderModel(id = "gpt-5.6-sol", name = "GPT-5.6 Sol"),
                    "embed-1" to ProviderModel(
                        id = "embed-1",
                        name = "Embed",
                        capabilities = ProviderModelCapabilities(output = ProviderModelOutput(text = false))
                    )
                )
            ),
            ConfigProvider(
                id = "disconnected",
                name = "Disconnected",
                models = mapOf("m1" to ProviderModel(id = "m1", name = "M1"))
            )
        )
        val result = buildCatalog(providers, connectedProviderIds = setOf("openai"))

        assertEquals(listOf("openai/gpt-5.6-sol"), result.models.map { it.id })
        assertEquals(mapOf("openai" to "OpenAI"), result.providerDisplayNames)
    }

    @Test
    fun `buildCatalog includes all providers when connected set is null`() {
        val providers = listOf(
            ConfigProvider(id = "a", models = mapOf("m" to ProviderModel(id = "m", name = "M"))),
            ConfigProvider(id = "b", models = mapOf("m" to ProviderModel(id = "m", name = "M")))
        )
        val result = buildCatalog(providers, connectedProviderIds = null)
        assertEquals(2, result.models.size)
    }

    @Test
    fun `buildCatalog sorts by provider then display name`() {
        val providers = listOf(
            ConfigProvider(
                id = "openai",
                models = mapOf(
                    "zeta" to ProviderModel(id = "zeta", name = "Zeta"),
                    "alpha" to ProviderModel(id = "alpha", name = "Alpha")
                )
            )
        )
        val result = buildCatalog(providers, connectedProviderIds = setOf("openai"))
        assertEquals(listOf("openai/alpha", "openai/zeta"), result.models.map { it.id })
    }

    @Test
    fun `buildCatalog falls back to model id when the name is blank or null`() {
        val providers = listOf(
            ConfigProvider(
                id = "openai",
                models = mapOf(
                    "blank-name" to ProviderModel(id = "blank-name", name = "   "),
                    "null-name" to ProviderModel(id = "null-name", name = null)
                )
            )
        )
        val result = buildCatalog(providers, connectedProviderIds = setOf("openai"))
        val byId = result.models.associateBy { it.id }
        assertEquals("blank-name", byId["openai/blank-name"]?.displayName)
        assertEquals("null-name", byId["openai/null-name"]?.displayName)
        assertTrue(result.models.all { it.shortName.isNotEmpty() })
    }

    @Test
    fun `addModelToShortlist appends new model and skips duplicates`() {
        val shortlist = listOf(item())
        val (added, changed) = addModelToShortlist(shortlist, "google", "gemini-3.7-flash", "Gemini 3.7 Flash")
        assertTrue(changed)
        assertEquals(2, added.size)
        assertEquals("google/gemini-3.7-flash", added[1].id)

        val (unchanged, changedAgain) = addModelToShortlist(added, "google", "gemini-3.7-flash", "Gemini 3.7 Flash")
        assertFalse(changedAgain)
        assertEquals(added, unchanged)
    }

    @Test
    fun `removeShortlistItem removes by id`() {
        val shortlist = listOf(item(), item("google", "gemini-3.7-flash", "Gemini 3.7 Flash", "Gemini"))
        val next = removeShortlistItem(shortlist, "google/gemini-3.7-flash")
        assertEquals(1, next.size)
        assertEquals("openai/gpt-5.6-sol", next[0].id)
    }

    @Test
    fun `moveShortlistItem reorders and ignores out-of-range indices`() {
        val shortlist = listOf(
            item("openai", "a", "A", "A"),
            item("openai", "b", "B", "B"),
            item("openai", "c", "C", "C")
        )
        val moved = moveShortlistItem(shortlist, 0, 2)
        assertEquals(listOf("openai/b", "openai/c", "openai/a"), moved.map { it.id })
        assertEquals(shortlist, moveShortlistItem(shortlist, 0, -1))
        assertEquals(shortlist, moveShortlistItem(shortlist, 0, 5))
    }

    @Test
    fun `updateShortlistShortName edits and falls back to suggested when blank`() {
        val shortlist = listOf(item())
        val edited = updateShortlistShortName(shortlist, "openai/gpt-5.6-sol", "  Sol  ")
        assertEquals("Sol", edited[0].shortName)

        val blank = updateShortlistShortName(shortlist, "openai/gpt-5.6-sol", "   ")
        assertEquals("GPT", blank[0].shortName)
    }

    @Test
    fun `refreshShortlistDisplayNames updates display name but keeps short name`() {
        val shortlist = listOf(item(displayName = "Old Name", shortName = "MyCustom"))
        val catalog = listOf(CatalogModel("openai", "gpt-5.6-sol", "New Name", "GPT"))
        val refreshed = refreshShortlistDisplayNames(shortlist, catalog)
        assertEquals("New Name", refreshed[0].displayName)
        assertEquals("MyCustom", refreshed[0].shortName)
    }

    @Test
    fun `reanchorSelectedModelIndex finds id or defaults to zero`() {
        val shortlist = listOf(
            item("openai", "a", "A", "A"),
            item("openai", "b", "B", "B")
        )
        assertEquals(1, reanchorSelectedModelIndex(shortlist, "openai/b"))
        assertEquals(0, reanchorSelectedModelIndex(shortlist, "openai/missing"))
        assertEquals(0, reanchorSelectedModelIndex(shortlist, null))
        assertEquals(0, reanchorSelectedModelIndex(emptyList(), "openai/a"))
    }

    @Test
    fun `migrate seeds shortlist and maps legacy index to id`() {
        val migration = migrateToIdBasedModelSelection(
            existingShortlist = null,
            legacySelectedIndex = 2,
            legacySessionModels = mapOf("s1" to "1", "s2" to "not-a-number", "s3" to "999")
        )
        assertEquals(9, migration.shortlist.size)
        assertEquals("google/gemini-3.7-flash", migration.selectedModelId)
        // Only in-range numeric legacy indices migrate; malformed / out-of-range
        // values are dropped so they can't become permanent invalid selections.
        assertEquals(mapOf("s1" to "openai/gpt-5.6-sol"), migration.sessionModelIds)
    }

    @Test
    fun `migrate keeps existing shortlist and falls back to first preset for bad index`() {
        val existing = listOf(item("zai-coding-plan", "glm-5.3", "GLM-5.3", "GLM-5.3"))
        val migration = migrateToIdBasedModelSelection(existing, 999, emptyMap())
        assertEquals(existing, migration.shortlist)
        assertEquals("zai-coding-plan/glm-5.3", migration.selectedModelId)
    }

    @Test
    fun `migrate maps an already-normalized index straight to the seed`() {
        // By the time this runs, migrateRemovedGpt56SolProModelIndices() has already
        // normalized the stored index to the current ModelPresets.list order, so the
        // index maps straight onto the seed (a second remap would shift it further).
        val luna = migrateToIdBasedModelSelection(null, 6, emptyMap())
        assertEquals("openai/gpt-5.6-luna", luna.selectedModelId)
        val grok = migrateToIdBasedModelSelection(null, 7, emptyMap())
        assertEquals("xai/grok-4.6", grok.selectedModelId)
    }

    @Test
    fun `encode and decode shortlist round trips`() {
        val shortlist = listOf(item(), item("google", "gemini-3.7-flash", "Gemini 3.7 Flash", "Gemini"))
        val json = encodeShortlist(shortlist)
        assertEquals(shortlist, decodeShortlist(json))
        assertNull(decodeShortlist(null))
        assertNull(decodeShortlist("not-json"))
    }
}
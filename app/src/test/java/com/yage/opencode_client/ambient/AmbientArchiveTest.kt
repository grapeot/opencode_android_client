package com.yage.opencode_client.ambient

import com.yage.opencode_client.ambient.protocol.AmbientArchiveWire
import com.yage.opencode_client.ambient.protocol.AmbientGatewayCall
import com.yage.opencode_client.ambient.protocol.AmbientHostedFunction
import com.yage.opencode_client.ambient.protocol.AmbientHttpResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AmbientArchiveTest {
    @Test fun threeLevelMixedArchiveIsPostorderWithOneStampAndNoAbort() {
        val script = treeScript(rootArchived = 9, childArchived = 0, grandArchived = 5)
        val effect = script.execute(AmbientHostedFunction.ARCHIVE_JOB, archiveArgs("root"))
        assertEquals(listOf("/session/grand", "/session/child", "/session/root"), patchPaths(script))
        val stamp = AmbientArchiveWire.body(1_700_000_000_000L)
        script.calls.filter { it.method == "PATCH" }.forEach { assertEquals(stamp, it.jsonBody) }
        assertEquals(true, json(effect.output).getValue("ok").jsonPrimitive.content.toBoolean())
        assertEquals("archived", json(effect.output).getValue("status").jsonPrimitive.content)
        assertNoAbort(script.calls)
        assertNull(effect.clearSessionId)
    }

    @Test fun unarchiveIsPreorderAndWritesZero() {
        val script = treeScript(rootArchived = 0, childArchived = 9, grandArchived = 5)
        val effect = script.execute(AmbientHostedFunction.UNARCHIVE_JOB, archiveArgs("root"))
        assertEquals(listOf("/session/root", "/session/child", "/session/grand"), patchPaths(script))
        val zero = AmbientArchiveWire.body(0)
        script.calls.filter { it.method == "PATCH" }.forEach { call ->
            assertEquals(zero, call.jsonBody)
            assertFalse(call.jsonBody!!.contains("null"))
        }
        assertEquals("unarchived", json(effect.output).getValue("status").jsonPrimitive.content)
        assertNoAbort(script.calls)
    }

    @Test fun discoveryFailureWritesNothingAndKeepsBinding() {
        val script = AmbientToolScript(bindingSession = binding("root"))
        script.handler = { call ->
            when (call.path) {
                "/session/root" -> toolHttp(200, sessionBody("root"))
                else -> toolHttp(500, "")
            }
        }
        val effect = script.execute(AmbientHostedFunction.ARCHIVE_JOB, archiveArgs("root"))
        assertTrue(script.calls.none { it.method == "PATCH" })
        assertNoAbort(script.calls)
        assertNull(effect.clearSessionId)
        assertEquals("root", script.binding?.sessionId)
        assertEquals(false, json(effect.output).getValue("ok").jsonPrimitive.content.toBoolean())
    }

    @Test fun cycleDepthAndNodeLimitsWriteNothing() {
        val cycle = AmbientToolScript()
        cycle.handler = { call ->
            when (call.path) {
                "/session/root" -> toolHttp(200, sessionBody("root"))
                "/session/root/children" -> toolHttp(200, "[${sessionBody("child", parentId = "root")}]")
                "/session/child/children" -> toolHttp(200, "[${sessionBody("root", parentId = "child")}]")
                else -> unexpected(call)
            }
        }
        cycle.execute(AmbientHostedFunction.ARCHIVE_JOB, archiveArgs("root"))
        assertTrue(cycle.calls.none { it.method == "PATCH" })
        assertNoAbort(cycle.calls)

        val deep = AmbientToolScript()
        val ids = (0..33).map { "n$it" }
        deep.handler = { call ->
            when {
                call.method != "GET" -> unexpected(call)
                call.path == "/session/n0" -> toolHttp(200, sessionBody("n0"))
                call.path.endsWith("/children") -> {
                    val id = call.path.removePrefix("/session/").removeSuffix("/children")
                    val next = ids.getOrNull(ids.indexOf(id) + 1)
                    if (next == null) toolHttp(200, "[]") else toolHttp(200, "[${sessionBody(next, parentId = id)}]")
                }
                else -> unexpected(call)
            }
        }
        deep.execute(AmbientHostedFunction.ARCHIVE_JOB, archiveArgs("n0"))
        assertTrue(deep.calls.none { it.method == "PATCH" })

        val wide = AmbientToolScript()
        val kids = (1..64).joinToString(",") { sessionBody("c$it", parentId = "root") }
        wide.handler = { call ->
            when (call.path) {
                "/session/root" -> toolHttp(200, sessionBody("root"))
                "/session/root/children" -> toolHttp(200, "[$kids]")
                else -> if (call.path.endsWith("/children")) toolHttp(200, "[]") else unexpected(call)
            }
        }
        wide.execute(AmbientHostedFunction.ARCHIVE_JOB, archiveArgs("root"))
        assertTrue(wide.calls.none { it.method == "PATCH" })
        assertNoAbort(wide.calls)
    }

    @Test fun partialWriteKeepsVerifiedAndDoesNotClearRoot() {
        val script = treeScript(failPatch = "child", bindingId = "root", rootArchived = 9)
        val effect = script.execute(AmbientHostedFunction.ARCHIVE_JOB, archiveArgs("root"))
        assertEquals(listOf("/session/grand", "/session/child"), patchPaths(script))
        val body = json(effect.output)
        assertEquals("partial", body.getValue("status").jsonPrimitive.content)
        assertEquals(listOf("grand"), stringList(body, "verifiedIDs"))
        assertEquals(listOf("child"), stringList(body, "failedIDs"))
        assertEquals(listOf("root"), stringList(body, "pendingIDs"))
        assertNull(effect.clearSessionId)
        assertEquals("root", script.binding?.sessionId)
        assertNoAbort(script.calls)
    }

    @Test fun missingChildDoesNotClearRootBinding() {
        val script = treeScript(failGet = "child", bindingId = "root")
        val effect = script.execute(AmbientHostedFunction.ARCHIVE_JOB, archiveArgs("root"))
        assertEquals(listOf("grand"), stringList(json(effect.output), "verifiedIDs"))
        assertEquals(listOf("child"), stringList(json(effect.output), "failedIDs"))
        assertEquals(listOf("root"), stringList(json(effect.output), "pendingIDs"))
        assertNull(effect.clearSessionId)
        assertEquals("root", script.binding?.sessionId)
        assertNoAbort(script.calls)
    }

    @Test fun lateOwnerStopsBeforeTheNextWrite() {
        val script = treeScript(bindingId = "other")
        script.handler = treeHandler(script, flipOnFirstPatch = true)
        val effect = script.execute(AmbientHostedFunction.ARCHIVE_JOB, archiveArgs("root"))
        assertEquals(1, script.calls.count { it.method == "PATCH" })
        assertEquals("PATCH", script.calls.last().method)
        assertEquals("stale_context", json(effect.output).getValue("error").jsonPrimitive.content)
        assertNull(effect.clearSessionId)
        assertEquals("other", script.binding?.sessionId)
        assertNoAbort(script.calls)
    }

    @Test fun root404ClearsOnlyTheMatchingBinding() {
        val matched = AmbientToolScript(bindingSession = binding("root"))
        matched.handler = { toolHttp(404, "") }
        val cleared = matched.execute(AmbientHostedFunction.ARCHIVE_JOB, archiveArgs("root"))
        assertEquals("root", cleared.clearSessionId)
        assertEquals(1, matched.calls.size)
        assertNoAbort(matched.calls)

        val other = AmbientToolScript(bindingSession = binding("other"))
        other.handler = { toolHttp(404, "") }
        val kept = other.execute(AmbientHostedFunction.ARCHIVE_JOB, archiveArgs("root"))
        assertNull(kept.clearSessionId)
        assertEquals("other", other.binding?.sessionId)
    }
}

private const val ROOT = "root"
private const val CHILD = "child"
private const val GRAND = "grand"

private fun treeScript(
    rootArchived: Long = 0,
    childArchived: Long = 0,
    grandArchived: Long = 0,
    failPatch: String? = null,
    failGet: String? = null,
    bindingId: String? = null,
): AmbientToolScript {
    val script = AmbientToolScript(bindingSession = bindingId?.let { binding(it) })
    script.handler = treeHandler(
        script,
        rootArchived,
        childArchived,
        grandArchived,
        failPatch,
        failGet,
        flipOnFirstPatch = false,
    )
    return script
}

private fun treeHandler(
    script: AmbientToolScript,
    rootArchived: Long = 0,
    childArchived: Long = 0,
    grandArchived: Long = 0,
    failPatch: String? = null,
    failGet: String? = null,
    flipOnFirstPatch: Boolean = false,
): (AmbientGatewayCall) -> AmbientHttpResult {
    val archived = mutableMapOf(ROOT to rootArchived, CHILD to childArchived, GRAND to grandArchived)
    val children = mapOf(ROOT to listOf(CHILD), CHILD to listOf(GRAND), GRAND to emptyList<String>())
    val parents = mapOf(ROOT to null, CHILD to ROOT, GRAND to CHILD)
    return { call ->
        when {
            call.method == "GET" && call.path.endsWith("/children") -> {
                val id = call.path.removePrefix("/session/").removeSuffix("/children")
                val bodies = children[id].orEmpty().map { child ->
                    sessionBody(child, parentId = id, archived = archived.getValue(child))
                }
                toolHttp(200, "[${bodies.joinToString(",")}]")
            }
            call.method == "GET" && call.path.startsWith("/session/") -> {
                val id = call.path.removePrefix("/session/")
                if (id == failGet) toolHttp(404, "")
                else toolHttp(200, sessionBody(id, parentId = parents[id], archived = archived.getValue(id)))
            }
            call.method == "PATCH" && call.path.startsWith("/session/") -> {
                val id = call.path.removePrefix("/session/")
                if (flipOnFirstPatch && script.calls.count { it.method == "PATCH" } == 1) script.lease = null
                if (id == failPatch) {
                    toolHttp(500, "")
                } else {
                    val value = Json.parseToJsonElement(call.jsonBody!!).jsonObject
                        .getValue("time").jsonObject.getValue("archived").jsonPrimitive.content.toLong()
                    archived[id] = value
                    toolHttp(200, sessionBody(id, parentId = parents[id], archived = value))
                }
            }
            else -> unexpected(call)
        }
    }
}

private fun patchPaths(script: AmbientToolScript): List<String> =
    script.calls.filter { it.method == "PATCH" }.map { it.path }

private fun archiveArgs(id: String): String = buildJsonObject { put("sessionID", id) }.toString()

private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

private fun stringList(body: kotlinx.serialization.json.JsonObject, key: String): List<String> =
    body.getValue(key).jsonArray.map { it.jsonPrimitive.content }

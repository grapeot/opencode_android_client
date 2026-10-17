package com.yage.opencode_client.ambient

import com.yage.opencode_client.ambient.protocol.AmbientAuthorizingGateway
import com.yage.opencode_client.ambient.protocol.AmbientBinding
import com.yage.opencode_client.ambient.protocol.AmbientCapturedConfig
import com.yage.opencode_client.ambient.protocol.AmbientDispatchGate
import com.yage.opencode_client.ambient.protocol.AmbientGatewayCall
import com.yage.opencode_client.ambient.protocol.AmbientHostedFunction
import com.yage.opencode_client.ambient.protocol.AmbientHttpResult
import com.yage.opencode_client.ambient.protocol.AmbientLease
import com.yage.opencode_client.ambient.protocol.AmbientPromptWire
import com.yage.opencode_client.ambient.protocol.AmbientToolDefaults
import com.yage.opencode_client.ambient.protocol.AmbientToolEffect
import com.yage.opencode_client.ambient.tools.AmbientToolRunner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
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

class AmbientToolTest {
    @Test fun recentRootsPageAndExcludeDescendants() {
        val script = pagingScript()
        val effect = script.execute(AmbientHostedFunction.QUERY_JOBS, queryArgs())
        assertTrue(script.calls.all { it.method == "GET" && it.path == "/experimental/session" })
        assertNoAbort(script.calls)
        assertEquals(listOf(null, "50"), script.calls.map { it.query["cursor"] })
        script.calls.forEach { call ->
            assertEquals("true", call.query["roots"])
            assertEquals("true", call.query["archived"])
            assertEquals("50", call.query["limit"])
        }
        val json = json(effect.output)
        assertEquals(JsonPrimitive(true), json["ok"])
        val ids = json.getValue("records").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }
        assertEquals(listOf("a1", "a2", "a3", "a4", "a5", "a6", "a7", "a8", "a9", "a10"), ids)
        assertEquals(ids, json.getValue("sessions").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content })
        assertFalse(ids.contains("child"))
        assertFalse(ids.contains("u1"))
        val page = json.getValue("pagination").jsonObject
        assertEquals(0, page.getValue("skip").jsonPrimitive.content.toInt())
        assertEquals(10, page.getValue("count").jsonPrimitive.content.toInt())
        assertEquals(JsonPrimitive(true), page["hasMore"])
        assertEquals(10, page.getValue("nextSkip").jsonPrimitive.content.toInt())
        assertNull(effect.clearSessionId)
        assertFalse(effect.poll)
    }

    @Test fun omittedArchivedOnlyDefaultsTrueAndFalseKeepsUnarchivedRoots() {
        val defaults = pagingScript().execute(AmbientHostedFunction.QUERY_JOBS, queryArgs(archivedOnly = null))
        val ids = json(defaults.output).getValue("records").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }
        assertEquals("a1", ids.first())
        assertFalse(ids.contains("u1"))

        val both = pagingScript().execute(AmbientHostedFunction.QUERY_JOBS, queryArgs(archivedOnly = false))
        val mixed = json(both.output).getValue("records").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }
        assertTrue(mixed.contains("u1"))
        assertFalse(mixed.contains("child"))
    }

    @Test fun laterPageUsesNextSkip() {
        val effect = pagingScript().execute(AmbientHostedFunction.QUERY_JOBS, queryArgs(skip = 10))
        val json = json(effect.output)
        val ids = json.getValue("records").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }
        assertEquals(listOf("a11", "a12"), ids)
        assertEquals(JsonPrimitive(false), json.getValue("pagination").jsonObject["hasMore"])
        assertNull(json.getValue("pagination").jsonObject["nextSkip"])
    }

    @Test fun currentIgnoresPagingAndSpecifiedDoesNotFallBack() {
        val current = AmbientToolScript(bindingSession = binding("ses_now"))
        current.handler = {
            assertEquals("GET", it.method)
            assertEquals("/session/ses_now", it.path)
            toolHttp(200, sessionBody("ses_now", title = "当前"))
        }
        val effect = current.execute(AmbientHostedFunction.QUERY_JOBS, queryArgs(scope = "current", skip = 4, count = 50, archivedOnly = false))
        val currentJson = json(effect.output)
        assertEquals(listOf("ses_now"), currentJson.getValue("records").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content })
        assertEquals(1, currentJson.getValue("pagination").jsonObject.getValue("count").jsonPrimitive.content.toInt())

        val missing = AmbientToolScript(bindingSession = binding("ses_real"))
        missing.handler = { call ->
            assertEquals("/session/ses_missing", call.path)
            toolHttp(404, "")
        }
        val missed = missing.execute(AmbientHostedFunction.QUERY_JOBS, queryArgs(scope = "specified", sessionId = "ses_missing"))
        assertEquals(1, missing.calls.size)
        assertNull(missed.clearSessionId)
        assertEquals("ses_real", missing.binding?.sessionId)
        assertEquals(404, json(missed.output).getValue("http_status").jsonPrimitive.content.toInt())

        val matched = AmbientToolScript(bindingSession = binding("ses_missing"))
        matched.handler = { toolHttp(404, "") }
        val cleared = matched.execute(AmbientHostedFunction.QUERY_JOBS, queryArgs(scope = "specified", sessionId = "ses_missing"))
        assertEquals("ses_missing", cleared.clearSessionId)
        assertEquals("ses_missing", matched.binding?.sessionId)

        val child = AmbientToolScript()
        child.handler = { toolHttp(200, sessionBody("ses_child", parentId = "ses_root")) }
        val rejected = child.execute(AmbientHostedFunction.QUERY_JOBS, queryArgs(scope = "specified", sessionId = "ses_child"))
        assertNull(rejected.clearSessionId)
        assertFalse(json(rejected.output).getValue("ok").jsonPrimitive.content.toBoolean())
        assertTrue(child.calls.none { it.path == "/experimental/session" })
    }

    @Test fun startOmitsTitleUsesDeepSeekAndAcceptsWithoutWaiting() {
        val script = startScript(mapOf("ollama-cloud" to listOf("deepseek-v4.1-flash")))
        val effect = script.execute(AmbientHostedFunction.START_JOB, startArgs("研究一下"))
        assertEquals(
            listOf("GET" to "/provider", "POST" to "/session", "POST" to "/session/ses_new/prompt_async"),
            script.calls.map { it.method to it.path },
        )
        assertEquals("{}", script.calls[1].jsonBody)
        assertEquals("/work", script.calls[1].query["directory"])
        assertEquals(
            AmbientPromptWire.startData("msg_fixed", "研究一下", "plan", "ollama-cloud", "deepseek-v4.1-flash"),
            script.calls[2].jsonBody,
        )
        assertTrue(effect.poll)
        assertEquals("ses_new", effect.sessionId)
        assertEquals("msg_fixed", effect.messageId)
        assertEquals("/work", effect.directory)
        assertEquals(AmbientToolDefaults.DEEPSEEK, effect.model)
        assertEquals("accepted", json(effect.output).getValue("status").jsonPrimitive.content)
        assertNull(effect.clearSessionId)
    }

    @Test fun complexSolAndSpokenSoAUseTheConnectedSolRef() {
        val connected = mapOf(
            "ollama-cloud" to listOf("deepseek-v4.1-flash"),
            "openai" to listOf("gpt-6.1-sol"),
        )
        val sol = startScript(connected)
        sol.execute(AmbientHostedFunction.START_JOB, startArgs("难推理", AmbientToolDefaults.SOL))
        assertEquals(
            AmbientPromptWire.startData("msg_fixed", "难推理", "plan", "openai", "gpt-6.1-sol"),
            sol.calls[2].jsonBody,
        )
        val spoken = startScript(connected)
        spoken.execute(AmbientHostedFunction.START_JOB, startArgs("难推理", "SoA"))
        assertEquals(sol.calls[2].jsonBody, spoken.calls[2].jsonBody)
    }

    @Test fun unconnectedOrUnknownModelDoesNotCreate() {
        val unconnected = startScript(mapOf("ollama-cloud" to listOf("deepseek-v4.1-flash")))
        val denied = unconnected.execute(AmbientHostedFunction.START_JOB, startArgs("难推理", AmbientToolDefaults.SOL))
        assertEquals(listOf("/provider"), unconnected.calls.map { it.path })
        assertFalse(json(denied.output).getValue("ok").jsonPrimitive.content.toBoolean())
        assertFalse(denied.poll)

        val unknown = startScript(mapOf("ollama-cloud" to listOf("deepseek-v4.1-flash")))
        unknown.execute(AmbientHostedFunction.START_JOB, startArgs("研究", "claude"))
        assertTrue(unknown.calls.isEmpty())
    }

    @Test fun blankLeaseDirectoryAcceptsServerDirectoryBeforePrompt() {
        val script = startScript(mapOf("ollama-cloud" to listOf("deepseek-v4.1-flash")), directory = null)
        val effect = script.execute(AmbientHostedFunction.START_JOB, startArgs("研究一下"))
        assertNull(script.calls[1].query["directory"])
        assertEquals("/server-default", effect.directory)
        assertTrue(effect.poll)
        assertEquals(3, script.calls.size)
    }

    @Test fun unverifiedDirectoryDoesNotPrompt() {
        val blank = startScript(mapOf("ollama-cloud" to listOf("deepseek-v4.1-flash")), createdDirectory = "")
        val missing = blank.execute(AmbientHostedFunction.START_JOB, startArgs("研究一下"))
        assertEquals(2, blank.calls.size)
        assertEquals("directory_unknown", json(missing.output).getValue("error").jsonPrimitive.content)
        assertFalse(missing.poll)

        val mismatch = startScript(mapOf("ollama-cloud" to listOf("deepseek-v4.1-flash")), createdDirectory = "/other")
        mismatch.execute(AmbientHostedFunction.START_JOB, startArgs("研究一下"))
        assertEquals(2, mismatch.calls.size)
    }

    @Test fun continueSendsExactTextWithoutModelAndDoesNotWait() {
        val text = "  你好  "
        val script = AmbientToolScript(agentName = "build", bindingSession = binding("ses_1"))
        script.handler = { call ->
            when {
                call.method == "GET" && call.path == "/session/ses_1" -> toolHttp(200, sessionBody("ses_1"))
                call.method == "GET" && call.path == "/session/ses_1/message" -> toolHttp(
                    200,
                    "[${historyMessage("m_old", "user", 1, "早", agent = "other", variant = "low")}," +
                        "${historyMessage("m_new", "assistant", 2, "晚", agent = "plan", variant = "max", model = true)}]",
                )
                call.method == "POST" && call.path == "/session/ses_1/prompt_async" -> toolHttp(204, "")
                else -> unexpected(call)
            }
        }
        val effect = script.execute(AmbientHostedFunction.CONTINUE_JOB, continueArgs(text, "ses_1"))
        val prompt = script.calls.last()
        assertEquals(AmbientPromptWire.continueData("msg_fixed", text, "plan", "max"), prompt.jsonBody)
        assertFalse(prompt.jsonBody!!.contains("\"model\""))
        assertFalse(prompt.jsonBody!!.contains("build"))
        assertEquals(3, script.calls.size)
        assertTrue(effect.poll)
        assertNull(effect.model)
        assertEquals("ses_1", effect.sessionId)
        assertEquals("accepted", json(effect.output).getValue("status").jsonPrimitive.content)
    }

    @Test fun continueOmitsAgentWhenMetadataHasNone() {
        val script = AmbientToolScript(agentName = "build")
        script.handler = { call ->
            when {
                call.method == "GET" && call.path == "/session/ses_1" -> toolHttp(200, sessionBody("ses_1"))
                call.path == "/session/ses_1/message" -> toolHttp(200, "[${historyMessage("m1", "user", 1, "早")}]")
                call.path.endsWith("/prompt_async") -> toolHttp(204, "")
                else -> unexpected(call)
            }
        }
        script.execute(AmbientHostedFunction.CONTINUE_JOB, continueArgs("你好", "ses_1"))
        val body = script.calls.last().jsonBody!!
        assertEquals(AmbientPromptWire.continueData("msg_fixed", "你好", null, null), body)
        assertFalse(body.contains("build"))
        assertFalse(body.contains("model"))
    }

    @Test fun unknownContinueDoesNotFallBackOrCreate() {
        val script = AmbientToolScript(bindingSession = binding("ses_real"))
        script.handler = { call ->
            assertEquals("/session/ses_missing", call.path)
            toolHttp(404, "")
        }
        val effect = script.execute(AmbientHostedFunction.CONTINUE_JOB, continueArgs("你好", "ses_missing"))
        assertEquals(1, script.calls.size)
        assertNull(effect.clearSessionId)
        assertFalse(effect.poll)
        assertEquals("ses_real", script.binding?.sessionId)
    }

    @Test fun authServerAndTransportFailuresPreserveBinding() {
        for (mode in listOf("401", "500", "transport")) {
            val script = AmbientToolScript(bindingSession = binding("ses_1"))
            script.handler = {
                when (mode) {
                    "401" -> toolHttp(401, "{\"password\":\"nope\"}")
                    "500" -> toolHttp(500, "")
                    else -> throw RuntimeException("down")
                }
            }
            val effect = script.execute(AmbientHostedFunction.CONTINUE_JOB, continueArgs("你好", "ses_1"))
            assertNull(effect.clearSessionId)
            assertEquals("ses_1", script.binding?.sessionId)
            assertFalse(effect.poll)
            assertFalse(effect.output.contains("password"))
            assertTrue(script.calls.none { it.path.contains("prompt_async") || it.method == "POST" })
        }
    }

    @Test fun missingIdleIsStoppedAndFailedStatusIsNot() {
        val idle = stopScript { call ->
            when (call.path) {
                "/session/ses_1" -> toolHttp(200, sessionBody("ses_1"))
                "/session/status" -> toolHttp(200, "{}")
                else -> unexpected(call)
            }
        }
        val stopped = idle.execute(AmbientHostedFunction.STOP_JOB, sessionArgs("ses_1"))
        assertEquals("cancelled", json(stopped.output).getValue("status").jsonPrimitive.content)
        assertNoAbort(idle.calls)
        assertEquals(2, idle.calls.size)

        val failed = stopScript { call ->
            when (call.path) {
                "/session/ses_1" -> toolHttp(200, sessionBody("ses_1"))
                "/session/status" -> toolHttp(500, "")
                else -> unexpected(call)
            }
        }
        val unconfirmed = failed.execute(AmbientHostedFunction.STOP_JOB, sessionArgs("ses_1"))
        assertEquals("not_confirmed", json(unconfirmed.output).getValue("error").jsonPrimitive.content)
        assertNoAbort(failed.calls)
        assertNull(unconfirmed.clearSessionId)

        val malformed = stopScript { call ->
            when (call.path) {
                "/session/ses_1" -> toolHttp(200, sessionBody("ses_1"))
                "/session/status" -> toolHttp(200, "[]")
                else -> unexpected(call)
            }
        }
        malformed.execute(AmbientHostedFunction.STOP_JOB, sessionArgs("ses_1"))
        assertNoAbort(malformed.calls)
    }

    @Test fun busyStopAbortsOnceThenRequiresIdle() {
        var reads = 0
        val script = stopScript { call ->
            when {
                call.path == "/session/ses_1" && call.method == "GET" -> toolHttp(200, sessionBody("ses_1"))
                call.path == "/session/status" -> {
                    reads += 1
                    if (reads == 1) toolHttp(200, statusBody("ses_1" to "busy")) else toolHttp(200, "{}")
                }
                call.method == "POST" && call.path == "/session/ses_1/abort" -> toolHttp(200, "")
                else -> unexpected(call)
            }
        }
        val effect = script.execute(AmbientHostedFunction.STOP_JOB, sessionArgs("ses_1"))
        assertEquals("cancelled", json(effect.output).getValue("status").jsonPrimitive.content)
        assertEquals(1, script.calls.count { it.path.endsWith("/abort") })
        assertNull(script.calls.first { it.path.endsWith("/abort") }.jsonBody)

        var lateReads = 0
        val late = stopScript { call ->
            when {
                call.path == "/session/ses_1" && call.method == "GET" -> toolHttp(200, sessionBody("ses_1"))
                call.path == "/session/status" -> {
                    lateReads += 1
                    if (lateReads == 1) toolHttp(200, statusBody("ses_1" to "retry")) else toolHttp(500, "")
                }
                call.path.endsWith("/abort") -> toolHttp(200, "")
                else -> unexpected(call)
            }
        }
        val unconfirmed = late.execute(AmbientHostedFunction.STOP_JOB, sessionArgs("ses_1"))
        assertEquals("not_confirmed", json(unconfirmed.output).getValue("error").jsonPrimitive.content)
        assertEquals(1, late.calls.count { it.path.endsWith("/abort") })
    }

    @Test fun permissionReplyIsOnceOrRejectAndNeverAlways() {
        val once = answerScript(
            permissions = listOf(permissionBody("per_1", "ses_1"), permissionBody("per_other", "ses_other")),
        )
        val allowed = once.execute(AmbientHostedFunction.ANSWER_PENDING, answerArgs(reply = "once"))
        val post = once.calls.single { it.method == "POST" }
        assertEquals("/session/ses_1/permissions/per_1", post.path)
        assertEquals("once", json(post.jsonBody!!).getValue("response").jsonPrimitive.content)
        assertEquals("answered", json(allowed.output).getValue("status").jsonPrimitive.content)
        assertNoAbort(once.calls)

        val reject = answerScript(permissions = listOf(permissionBody("per_1", "ses_1")))
        reject.execute(AmbientHostedFunction.ANSWER_PENDING, answerArgs(reply = "reject"))
        assertEquals("reject", json(reject.calls.last().jsonBody!!).getValue("response").jsonPrimitive.content)

        val always = answerScript(permissions = listOf(permissionBody("per_1", "ses_1")))
        val refused = always.execute(AmbientHostedFunction.ANSWER_PENDING, answerArgs(reply = "always"))
        assertTrue(always.calls.isEmpty())
        assertEquals("bad_arguments", json(refused.output).getValue("error").jsonPrimitive.content)

        val many = answerScript(permissions = listOf(permissionBody("per_1", "ses_1"), permissionBody("per_2", "ses_1")))
        many.execute(AmbientHostedFunction.ANSWER_PENDING, answerArgs(reply = "once"))
        assertTrue(many.calls.none { it.method == "POST" })
    }

    @Test fun questionAnswersMustMatchTheBoundPendingQuestion() {
        val matched = answerScript(questions = listOf(questionBody("que_1", "ses_1", 2), questionBody("que_other", "ses_other", 1)))
        val effect = matched.execute(
            AmbientHostedFunction.ANSWER_PENDING,
            answerArgs(answers = listOf(listOf("password reset"), listOf("No"))),
        )
        val post = matched.calls.single { it.method == "POST" }
        assertEquals("/question/que_1/reply", post.path)
        assertTrue(post.jsonBody!!.contains("password reset"))
        assertEquals("answered", json(effect.output).getValue("status").jsonPrimitive.content)
        assertNoAbort(matched.calls)

        val other = answerScript(questions = listOf(questionBody("que_other", "ses_other", 1)))
        other.execute(AmbientHostedFunction.ANSWER_PENDING, answerArgs(answers = listOf(listOf("Yes"))))
        assertTrue(other.calls.none { it.method == "POST" })

        val short = answerScript(questions = listOf(questionBody("que_1", "ses_1", 2)))
        short.execute(AmbientHostedFunction.ANSWER_PENDING, answerArgs(answers = listOf(listOf("Yes"))))
        assertTrue(short.calls.none { it.method == "POST" })
    }

    @Test fun unknownFunctionReturnsStructuredError() {
        val script = AmbientToolScript()
        val effect = script.execute("web_search", "{}")
        assertTrue(script.calls.isEmpty())
        assertEquals("unknown_function", json(effect.output).getValue("error").jsonPrimitive.content)
        assertFalse(effect.output.isEmpty())
    }
}

internal class AmbientToolScript(
    directory: String? = "/work",
    agentName: String? = "plan",
    bindingSession: AmbientBinding? = null,
) {
    var lease: AmbientLease? = AmbientLease("host", 3L, directory, 4L, "owner")
    var binding: AmbientBinding? = bindingSession
    var agent: String? = agentName
    val calls = mutableListOf<AmbientGatewayCall>()
    var handler: (AmbientGatewayCall) -> AmbientHttpResult = { unexpected(it) }
    val token: Long
    val runner: AmbientToolRunner
    val captured: AmbientLease

    init {
        captured = checkNotNull(lease)
        val gate = AmbientDispatchGate()
        token = gate.configure(
            AmbientCapturedConfig(
                hostProfileId = "host",
                runtimeGeneration = 3L,
                directory = directory,
                epoch = 4L,
                ownerToken = "owner",
                baseUrl = "https://example.invalid",
                username = "synthetic-user",
                password = "synthetic-credential",
            ),
        )
        val gateway = AmbientAuthorizingGateway(gate) { _, call, dispatched ->
            check(dispatched == token)
            calls += call
            handler(call)
        }
        runner = AmbientToolRunner(
            gateway = gateway,
            currentLease = { lease },
            currentBinding = { binding },
            currentAgent = { agent },
            nowEpochMillis = { 1_700_000_000_000L },
            nextMessageId = { "msg_fixed" },
        )
    }

    fun execute(name: String, arguments: String): AmbientToolEffect = runBlocking {
        runner.execute(name, arguments, captured, token)
    }
}

internal fun toolHttp(code: Int, body: String, headers: Map<String, List<String>> = emptyMap()) =
    AmbientHttpResult(code, headers, body)

internal fun binding(id: String, directory: String = "/work") =
    AmbientBinding(id, null, directory, AmbientToolDefaults.DEEPSEEK)

internal fun sessionBody(
    id: String,
    directory: String = "/work",
    parentId: String? = null,
    archived: Long = 0L,
    title: String = id,
    agent: String? = null,
    variant: String? = null,
): String = buildJsonObject {
    put("id", id)
    put("title", title)
    put("directory", directory)
    if (parentId == null) put("parentID", JsonNull) else put("parentID", parentId)
    put("time", buildJsonObject {
        put("updated", 2)
        put("archived", archived)
    })
    if (agent != null) put("agent", agent)
    if (variant != null) put("variant", variant)
}.toString()

internal fun providersBody(connected: Map<String, List<String>>): String = buildJsonObject {
    put("connected", buildJsonArray { connected.keys.forEach { add(JsonPrimitive(it)) } })
    put("all", buildJsonArray {
        connected.forEach { (provider, models) ->
            add(buildJsonObject {
                put("id", provider)
                put("models", buildJsonObject {
                    models.forEach { model -> put(model, buildJsonObject { put("id", model) }) }
                })
            })
        }
    })
}.toString()

internal fun historyMessage(
    id: String,
    role: String,
    created: Long,
    text: String? = null,
    reasoning: String? = null,
    completed: Long? = created,
    agent: String? = null,
    variant: String? = null,
    model: Boolean = false,
): String = buildJsonObject {
    put("info", buildJsonObject {
        put("id", id)
        put("role", role)
        put("time", buildJsonObject {
            put("created", created)
            if (completed != null) put("completed", completed)
        })
        if (agent != null) put("agent", agent)
        if (variant != null) put("variant", variant)
        if (model) {
            put("model", buildJsonObject {
                put("providerID", "openai")
                put("modelID", "gpt-6.1-sol")
            })
        }
    })
    put("parts", buildJsonArray {
        if (reasoning != null) {
            add(buildJsonObject {
                put("type", "reasoning")
                put("text", reasoning)
            })
        }
        if (text != null) {
            add(buildJsonObject {
                put("type", "text")
                put("text", text)
            })
        }
    })
}.toString()

internal fun statusBody(vararg entries: Pair<String, String>): String = buildJsonObject {
    entries.forEach { (id, type) -> put(id, buildJsonObject { put("type", type) }) }
}.toString()

internal fun permissionBody(id: String, sessionId: String): String = buildJsonObject {
    put("id", id)
    put("sessionID", sessionId)
    put("permission", "bash")
}.toString()

internal fun questionBody(id: String, sessionId: String, count: Int): String = buildJsonObject {
    put("id", id)
    put("sessionID", sessionId)
    put("questions", buildJsonArray {
        repeat(count) { index ->
            add(buildJsonObject {
                put("question", "q$index")
                put("header", "h")
                put("options", buildJsonArray { })
            })
        }
    })
}.toString()

internal fun queryArgs(
    scope: String = "recent",
    sessionId: String? = null,
    archivedOnly: Boolean? = true,
    skip: Int? = 0,
    count: Int? = 10,
): String = buildJsonObject {
    put("queryScope", scope)
    if (sessionId == null) put("sessionID", JsonNull) else put("sessionID", sessionId)
    put("text", "")
    if (archivedOnly == null) put("archivedOnly", JsonNull) else put("archivedOnly", archivedOnly)
    if (skip == null) put("skip", JsonNull) else put("skip", skip)
    if (count == null) put("count", JsonNull) else put("count", count)
}.toString()

internal fun startArgs(text: String, model: String? = null): String = buildJsonObject {
    put("text", text)
    if (model == null) put("requestedModel", JsonNull) else put("requestedModel", model)
}.toString()

internal fun continueArgs(text: String, sessionId: String? = null): String = buildJsonObject {
    put("text", text)
    if (sessionId == null) put("sessionID", JsonNull) else put("sessionID", sessionId)
}.toString()

internal fun sessionArgs(sessionId: String? = null, text: String = ""): String = buildJsonObject {
    put("text", text)
    if (sessionId == null) put("sessionID", JsonNull) else put("sessionID", sessionId)
}.toString()

internal fun answerArgs(reply: String? = null, answers: List<List<String>> = emptyList()): String = buildJsonObject {
    put("answers", buildJsonArray {
        answers.forEach { row ->
            add(buildJsonArray { row.forEach { add(JsonPrimitive(it)) } })
        }
    })
    if (reply == null) put("permissionReply", JsonNull) else put("permissionReply", reply)
    put("text", "")
}.toString()

internal fun assertNoAbort(calls: List<AmbientGatewayCall>) {
    assertTrue(calls.none { it.method == "DELETE" || it.path.endsWith("/abort") || it.path.contains("prompt_async") })
}

internal fun unexpected(call: AmbientGatewayCall): Nothing =
    throw CancellationException("unexpected ${call.method} ${call.path}")

private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

private fun pagingScript(): AmbientToolScript {
    val page1 = listOf(
        sessionBody("a1", archived = 5),
        sessionBody("a2", archived = 6),
        sessionBody("child", parentId = "a1", archived = 7),
        sessionBody("u1", archived = 0),
    ).joinToString(",")
    val page2 = (3..12).joinToString(",") { sessionBody("a$it", archived = 8) }
    val script = AmbientToolScript()
    script.handler = { call ->
        if (call.query["cursor"] == null) {
            toolHttp(200, "[$page1]", mapOf("x-next-cursor" to listOf("50")))
        } else {
            toolHttp(200, "[$page2]")
        }
    }
    return script
}

private fun startScript(
    connected: Map<String, List<String>>,
    directory: String? = "/work",
    createdDirectory: String? = null,
): AmbientToolScript {
    val script = AmbientToolScript(directory = directory)
    script.handler = { call ->
        when {
            call.method == "GET" && call.path == "/provider" -> toolHttp(200, providersBody(connected))
            call.method == "POST" && call.path == "/session" -> {
                val created = createdDirectory ?: call.query["directory"] ?: "/server-default"
                toolHttp(201, sessionBody("ses_new", directory = created))
            }
            call.method == "POST" && call.path == "/session/ses_new/prompt_async" -> toolHttp(204, "")
            else -> unexpected(call)
        }
    }
    return script
}

private fun stopScript(handler: (AmbientGatewayCall) -> AmbientHttpResult): AmbientToolScript {
    val script = AmbientToolScript(bindingSession = binding("ses_1"))
    script.handler = handler
    return script
}

private fun answerScript(
    permissions: List<String> = emptyList(),
    questions: List<String> = emptyList(),
): AmbientToolScript {
    val script = AmbientToolScript(bindingSession = binding("ses_1"))
    script.handler = { call ->
        when {
            call.method == "GET" && call.path == "/permission" -> toolHttp(200, "[${permissions.joinToString(",")}]")
            call.method == "GET" && call.path == "/question" -> toolHttp(200, "[${questions.joinToString(",")}]")
            call.method == "POST" -> toolHttp(200, "")
            else -> unexpected(call)
        }
    }
    return script
}

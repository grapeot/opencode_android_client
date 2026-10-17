package com.yage.opencode_client.ambient.tools

import com.yage.opencode_client.ambient.protocol.AmbientArchiveWire
import com.yage.opencode_client.ambient.protocol.AmbientAuthorizingGateway
import com.yage.opencode_client.ambient.protocol.AmbientBinding
import com.yage.opencode_client.ambient.protocol.AmbientBindingPolicy
import com.yage.opencode_client.ambient.protocol.AmbientFunctionOutput
import com.yage.opencode_client.ambient.protocol.AmbientGatewayCall
import com.yage.opencode_client.ambient.protocol.AmbientGatewayExchange
import com.yage.opencode_client.ambient.protocol.AmbientHistoryDraft
import com.yage.opencode_client.ambient.protocol.AmbientHistoryText
import com.yage.opencode_client.ambient.protocol.AmbientHostedFunction
import com.yage.opencode_client.ambient.protocol.AmbientHttpResult
import com.yage.opencode_client.ambient.protocol.AmbientLease
import com.yage.opencode_client.ambient.protocol.AmbientListItem
import com.yage.opencode_client.ambient.protocol.AmbientListOutput
import com.yage.opencode_client.ambient.protocol.AmbientListPage
import com.yage.opencode_client.ambient.protocol.AmbientListWindow
import com.yage.opencode_client.ambient.protocol.AmbientModelRef
import com.yage.opencode_client.ambient.protocol.AmbientModels
import com.yage.opencode_client.ambient.protocol.AmbientPromptWire
import com.yage.opencode_client.ambient.protocol.AmbientProtocol
import com.yage.opencode_client.ambient.protocol.AmbientSessionWalk
import com.yage.opencode_client.ambient.protocol.AmbientStartPolicy
import com.yage.opencode_client.ambient.protocol.AmbientToolCodes
import com.yage.opencode_client.ambient.protocol.AmbientToolDefaults
import com.yage.opencode_client.ambient.protocol.AmbientToolEffect
import com.yage.opencode_client.ambient.protocol.AmbientToolExecutor
import com.yage.opencode_client.ambient.protocol.AmbientToolJson
import com.yage.opencode_client.ambient.protocol.asArray
import com.yage.opencode_client.ambient.protocol.asObject
import com.yage.opencode_client.ambient.protocol.jsonBoolean
import com.yage.opencode_client.ambient.protocol.jsonObject
import com.yage.opencode_client.ambient.protocol.jsonString
import com.yage.opencode_client.ambient.protocol.jsonWhole
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.UUID
import kotlin.collections.ArrayDeque

/**
 * Eight OpenCode tools for one Ambient run.
 *
 * Coordinator wiring, all read at call time:
 * - [currentLease] returns the active [AmbientLease], or null when the run is gone,
 *   disconnected, or the workspace snapshot changed. Compared with the lease passed
 *   to [execute] before and after every await.
 * - [currentBinding] is the tracked session. This type never writes it. A 404 for
 *   that same id sets [AmbientToolEffect.clearSessionId]. 401, 500, and transport
 *   failure leave it null so the coordinator keeps the binding.
 * - [currentAgent] is the selected agent used only by `start_job`. Continuation
 *   copies agent and variant only when session or message JSON already has them.
 *
 * [AmbientAuthorizingGateway.exchange] is the send-time authority. [nowEpochMillis]
 * and [nextMessageId] have defaults; tests replace them. HTTP 204 sets
 * [AmbientToolEffect.poll] and does not wait for the agent. [AmbientToolEffect.model]
 * is set only for an accepted `start_job`.
 */
class AmbientToolRunner(
    private val gateway: AmbientAuthorizingGateway,
    private val currentLease: () -> AmbientLease?,
    private val currentBinding: () -> AmbientBinding?,
    private val currentAgent: () -> String?,
    private val nowEpochMillis: () -> Long = { System.currentTimeMillis() },
    private val nextMessageId: () -> String = {
        "msg_" + UUID.randomUUID().toString().replace("-", "")
    },
) : AmbientToolExecutor {

    override suspend fun execute(
        name: String,
        arguments: String,
        lease: AmbientLease,
        dispatchToken: Long,
    ): AmbientToolEffect {
        val effect = if (!owns(lease)) {
            staleEffect()
        } else {
            when (name) {
                AmbientHostedFunction.QUERY_JOBS -> queryJobs(arguments, lease, dispatchToken)
                AmbientHostedFunction.GET_SESSION_MESSAGES -> history(arguments, lease, dispatchToken)
                AmbientHostedFunction.START_JOB -> startJob(arguments, lease, dispatchToken)
                AmbientHostedFunction.CONTINUE_JOB -> continueJob(arguments, lease, dispatchToken)
                AmbientHostedFunction.STOP_JOB -> stopJob(arguments, lease, dispatchToken)
                AmbientHostedFunction.ANSWER_PENDING -> answerPending(arguments, lease, dispatchToken)
                AmbientHostedFunction.ARCHIVE_JOB -> archiveJob(arguments, lease, dispatchToken, archiving = true)
                AmbientHostedFunction.UNARCHIVE_JOB -> archiveJob(arguments, lease, dispatchToken, archiving = false)
                else -> unknownEffect()
            }
        }
        val bounded = AmbientFunctionOutput.ensureSendable(effect.output)
        return if (bounded == effect.output) effect else AmbientToolEffect(output = bounded)
    }

    private suspend fun queryJobs(arguments: String, lease: AmbientLease, token: Long): AmbientToolEffect {
        val args = jsonObject(arguments) ?: return badEffect()
        return when (jsonString(args["queryScope"])) {
            "recent" -> queryRecent(args, lease, token)
            "current" -> queryCurrent(lease, token)
            "specified" -> querySpecified(args, lease, token)
            else -> badEffect()
        }
    }

    private suspend fun queryRecent(args: JsonObject, lease: AmbientLease, token: Long): AmbientToolEffect {
        val paging = paging(args) ?: return badEffect()
        val pages = ArrayList<AmbientListPage>()
        var cursor: Int? = null
        while (true) {
            when (val fetched = fetch(lease, token, get("/experimental/session", pageQuery(cursor)))) {
                Fetched.Stale -> return staleEffect()
                Fetched.Transport -> return failed(AmbientToolCodes.LIST_FAILED_MESSAGE)
                is Fetched.Ok -> {
                    if (fetched.http.code != 200) {
                        return failed(AmbientToolCodes.LIST_FAILED_MESSAGE, fetched.http.code)
                    }
                    val page = parseListPage(fetched.http) ?: return failed(AmbientToolCodes.LIST_FAILED_MESSAGE)
                    pages += page
                    val window = AmbientSessionWalk.walk(pages, paging.archivedOnly, paging.skip, paging.count)
                    if (!window.fetchMore) return done(renderList(window))
                    val next = page.nextCursor
                    if (next == null || next == cursor) {
                        return done(renderList(window.copy(hasMore = null, nextSkip = null, partial = true, fetchMore = false)))
                    }
                    cursor = next
                }
            }
        }
    }

    private suspend fun queryCurrent(lease: AmbientLease, token: Long): AmbientToolEffect {
        val id = when (val tracked = trackedId()) {
            is SessionRef.Id -> tracked.value
            SessionRef.Missing -> return needsInput("还没有可跟踪的会话")
            SessionRef.Invalid -> return badEffect()
        }
        return loadRootRecord(id, lease, token)
    }

    private suspend fun querySpecified(args: JsonObject, lease: AmbientLease, token: Long): AmbientToolEffect {
        val id = when (val ref = sessionRef(args)) {
            is SessionRef.Id -> ref.value
            else -> return badEffect()
        }
        return loadRootRecord(id, lease, token)
    }

    private suspend fun loadRootRecord(id: String, lease: AmbientLease, token: Long): AmbientToolEffect {
        when (val fetched = fetch(lease, token, get(sessionPath(id)))) {
            Fetched.Stale -> return staleEffect()
            Fetched.Transport -> return failed(AmbientToolCodes.LIST_FAILED_MESSAGE)
            is Fetched.Ok -> {
                val code = fetched.http.code
                if (code == 404) return missingSession(id, code)
                if (code != 200) return failed(AmbientToolCodes.LIST_FAILED_MESSAGE, code)
                val item = parseSessionElement(fetched.http.jsonOrNull()) ?: return failed(AmbientToolCodes.LIST_FAILED_MESSAGE)
                if (item.id != id) return failed("会话没有对上")
                if (item.parentId.isNotEmpty()) return failed("指定的会话不是根会话")
                if (item.directory.isBlank()) return directoryUnknown()
                return done(renderList(singleWindow(item)))
            }
        }
    }

    private suspend fun history(arguments: String, lease: AmbientLease, token: Long): AmbientToolEffect {
        val args = jsonObject(arguments) ?: return badEffect()
        val paging = paging(args) ?: return badEffect()
        val id = when (val ref = sessionRef(args)) {
            is SessionRef.Id -> ref.value
            SessionRef.Missing -> when (val tracked = trackedId()) {
                is SessionRef.Id -> tracked.value
                SessionRef.Missing -> return needsInput("还没有可跟踪的会话")
                SessionRef.Invalid -> return badEffect()
            }
            SessionRef.Invalid -> return badEffect()
        }
        val session = when (val fetched = fetch(lease, token, get(sessionPath(id)))) {
            Fetched.Stale -> return staleEffect()
            Fetched.Transport -> return failed(AmbientToolCodes.HISTORY_FAILED_MESSAGE)
            is Fetched.Ok -> {
                if (fetched.http.code == 404) return missingSession(id, fetched.http.code, AmbientToolCodes.HISTORY_FAILED_MESSAGE)
                if (fetched.http.code != 200) return failed(AmbientToolCodes.HISTORY_FAILED_MESSAGE, fetched.http.code)
                parseSessionElement(fetched.http.jsonOrNull()) ?: return failed(AmbientToolCodes.HISTORY_FAILED_MESSAGE)
            }
        }
        if (session.id != id) return failed("会话没有对上")
        if (session.directory.isBlank()) return directoryUnknown()
        when (val fetched = fetch(lease, token, get(sessionPath(id, "/message"), mapOf("limit" to AmbientProtocol.HISTORY_FETCH_LIMIT.toString())))) {
            Fetched.Stale -> return staleEffect()
            Fetched.Transport -> return failed(AmbientToolCodes.HISTORY_FAILED_MESSAGE)
            is Fetched.Ok -> {
                if (fetched.http.code == 404) return failed(AmbientToolCodes.HISTORY_FAILED_MESSAGE, 404)
                if (fetched.http.code != 200) return failed(AmbientToolCodes.HISTORY_FAILED_MESSAGE, fetched.http.code)
                val parsed = parseHistory(fetched.http.body) ?: return failed(AmbientToolCodes.HISTORY_FAILED_MESSAGE)
                return done(
                    AmbientHistoryText.render(
                        sessionId = session.id,
                        title = session.title,
                        directory = session.directory,
                        drafts = parsed.drafts,
                        skip = paging.skip,
                        count = paging.count,
                        hasOlder = parsed.rawCount >= AmbientProtocol.HISTORY_FETCH_LIMIT,
                    ),
                )
            }
        }
    }

    private suspend fun startJob(arguments: String, lease: AmbientLease, token: Long): AmbientToolEffect {
        val args = jsonObject(arguments) ?: return badEffect()
        val text = jsonString(args["text"]) ?: return badEffect()
        if (text.isBlank()) return badEffect()
        val requested = args["requestedModel"]
        val rawModel = when {
            requested == null || requested is JsonNull -> null
            else -> jsonString(requested) ?: return badEffect()
        }
        val canonical = AmbientModels.canonicalize(rawModel).getOrElse { return unknownModel() }
        val ref = AmbientModels.split(canonical) ?: return unknownModel()
        if (selectedAgent().isEmpty()) return needsInput("还没有选定 agent")
        when (val providers = fetch(lease, token, get("/provider"))) {
            Fetched.Stale -> return staleEffect()
            Fetched.Transport -> return failed("请求没有完成")
            is Fetched.Ok -> {
                if (providers.http.code != 200) return failed("请求没有完成", providers.http.code)
                if (!providerHas(providers.http.body, ref)) return failed("所选模型没有连接")
            }
        }
        val agent = selectedAgent()
        if (agent.isEmpty()) return needsInput("还没有选定 agent")
        val directory = AmbientStartPolicy.createDirectoryArgument(lease.directory)
        val query = LinkedHashMap<String, String>()
        if (directory != null) query["directory"] = directory
        val created = when (val fetched = fetch(lease, token, post("/session", AmbientPromptWire.sessionCreate(null), query))) {
            Fetched.Stale -> return staleEffect()
            Fetched.Transport -> return failed("请求没有完成")
            is Fetched.Ok -> {
                if (fetched.http.code != 200 && fetched.http.code != 201) return failed("请求没有完成", fetched.http.code)
                parseSessionElement(fetched.http.jsonOrNull()) ?: return directoryUnknown()
            }
        }
        if (!isSafeId(created.id) || created.directory.isBlank()) return directoryUnknown()
        if (directory != null && created.directory != directory) return directoryUnknown()
        val messageId = nextMessageId()
        if (messageId.isBlank()) return badEffect()
        val body = AmbientPromptWire.startData(messageId, text, agent, ref.providerId, ref.modelId)
        return when (val prompted = fetch(lease, token, post(sessionPath(created.id, "/prompt_async"), body))) {
            Fetched.Stale -> staleEffect()
            Fetched.Transport -> failed("请求没有完成")
            is Fetched.Ok -> {
                if (!accepted(prompted.http.code)) return failed("请求没有完成", prompted.http.code)
                acceptedEffect(created.id, messageId, created.directory, canonical)
            }
        }
    }

    private suspend fun continueJob(arguments: String, lease: AmbientLease, token: Long): AmbientToolEffect {
        val args = jsonObject(arguments) ?: return badEffect()
        val text = jsonString(args["text"]) ?: return badEffect()
        if (text.isEmpty()) return badEffect()
        val id = when (val ref = sessionRef(args)) {
            is SessionRef.Id -> ref.value
            SessionRef.Missing -> when (val tracked = trackedId()) {
                is SessionRef.Id -> tracked.value
                SessionRef.Missing -> return needsInput("还没有可跟踪的会话")
                SessionRef.Invalid -> return badEffect()
            }
            SessionRef.Invalid -> return badEffect()
        }
        val loaded = loadWritableSession(id, lease, token) ?: return directoryUnknown()
        if (loaded is SessionLoad.Stop) return loaded.effect
        val sessionHttp = (loaded as SessionLoad.Ready).http
        val sessionElement = sessionHttp.jsonOrNull() as? JsonObject ?: return failed("请求没有完成")
        val session = parseSessionElement(sessionElement) ?: return failed("请求没有完成")
        val messages = when (val fetched = fetch(lease, token, get(sessionPath(id, "/message"), mapOf("limit" to AmbientProtocol.HISTORY_FETCH_LIMIT.toString())))) {
            Fetched.Stale -> return staleEffect()
            Fetched.Transport -> return failed("请求没有完成")
            is Fetched.Ok -> {
                if (fetched.http.code == 404) return failed("请求没有完成", 404)
                if (fetched.http.code != 200) return failed("请求没有完成", fetched.http.code)
                messageArray(fetched.http.body) ?: return failed("请求没有完成")
            }
        }
        val inherited = inheritAgent(sessionElement, messages)
        val messageId = nextMessageId()
        val body = AmbientPromptWire.continueData(messageId, text, inherited.agent, inherited.variant)
        return when (val prompted = fetch(lease, token, post(sessionPath(id, "/prompt_async"), body))) {
            Fetched.Stale -> staleEffect()
            Fetched.Transport -> failed("请求没有完成")
            is Fetched.Ok -> {
                if (!accepted(prompted.http.code)) return failed("请求没有完成", prompted.http.code)
                acceptedEffect(session.id, messageId, session.directory, model = null)
            }
        }
    }

    private suspend fun stopJob(arguments: String, lease: AmbientLease, token: Long): AmbientToolEffect {
        val args = jsonObject(arguments) ?: return badEffect()
        if (args.containsKey("text") && args["text"] !is JsonNull && jsonString(args["text"]) == null) return badEffect()
        val id = when (val ref = sessionRef(args)) {
            is SessionRef.Id -> ref.value
            SessionRef.Missing -> when (val tracked = trackedId()) {
                is SessionRef.Id -> tracked.value
                SessionRef.Missing -> return needsInput("还没有可跟踪的会话")
                SessionRef.Invalid -> return badEffect()
            }
            SessionRef.Invalid -> return badEffect()
        }
        when (val loaded = loadWritableSession(id, lease, token)) {
            null -> return directoryUnknown()
            is SessionLoad.Stop -> return loaded.effect
            is SessionLoad.Ready -> Unit
        }
        when (val idle = readIdle(id, lease, token)) {
            IdleRead.Stale -> return staleEffect()
            IdleRead.Failed -> return notConfirmed(null)
            is IdleRead.Value -> if (idle.idle) return done(stoppedJson())
        }
        when (val aborted = fetch(lease, token, AmbientGatewayCall("POST", sessionPath(id, "/abort")))) {
            Fetched.Stale -> return staleEffect()
            Fetched.Transport -> return notConfirmed(null)
            is Fetched.Ok -> if (aborted.http.code != 200 && aborted.http.code != 204) return notConfirmed(aborted.http.code)
        }
        return when (val after = readIdle(id, lease, token)) {
            IdleRead.Stale -> staleEffect()
            IdleRead.Failed -> notConfirmed(null)
            is IdleRead.Value -> if (after.idle) done(stoppedJson()) else notConfirmed(null)
        }
    }

    private suspend fun answerPending(arguments: String, lease: AmbientLease, token: Long): AmbientToolEffect {
        val args = jsonObject(arguments) ?: return badEffect()
        if (args.containsKey("text") && args["text"] !is JsonNull && jsonString(args["text"]) == null) return badEffect()
        val id = when (val tracked = trackedId()) {
            is SessionRef.Id -> tracked.value
            SessionRef.Missing -> return needsInput("还没有可跟踪的会话")
            SessionRef.Invalid -> return badEffect()
        }
        return when (val reply = permissionReply(args)) {
            ReplyKind.Invalid -> badEffect()
            is ReplyKind.Permission -> replyPermission(id, reply.value, lease, token)
            ReplyKind.Question -> {
                val answers = parseAnswers(args["answers"]) ?: return badEffect()
                if (answers.isEmpty() || answers.any { it.isEmpty() }) return badEffect()
                replyQuestion(id, answers, lease, token)
            }
        }
    }

    private suspend fun replyPermission(sessionId: String, reply: String, lease: AmbientLease, token: Long): AmbientToolEffect {
        if (reply != "once" && reply != "reject") return badEffect()
        val pending = when (val fetched = fetch(lease, token, get("/permission"))) {
            Fetched.Stale -> return staleEffect()
            Fetched.Transport -> return failed("请求没有完成")
            is Fetched.Ok -> {
                if (fetched.http.code != 200) return failed("请求没有完成", fetched.http.code)
                parsePermissions(fetched.http.body) ?: return failed("请求没有完成")
            }
        }
        if (trackedId() != SessionRef.Id(sessionId)) return staleEffect()
        val matches = pending.filter { it.sessionId == sessionId && isSafeId(it.id) }
        if (matches.size != 1) return failed("没有唯一的待确认权限")
        val permission = matches.single()
        val body = buildJsonObject { put("response", reply) }.toString()
        return when (val posted = fetch(lease, token, post(sessionPath(sessionId, "/permissions/${permission.id}"), body))) {
            Fetched.Stale -> staleEffect()
            Fetched.Transport -> failed("请求没有完成")
            is Fetched.Ok -> {
                if (posted.http.code != 200 && posted.http.code != 204) return failed("请求没有完成", posted.http.code)
                done(answeredJson(sessionId))
            }
        }
    }

    private suspend fun replyQuestion(
        sessionId: String,
        answers: List<List<String>>,
        lease: AmbientLease,
        token: Long,
    ): AmbientToolEffect {
        val pending = when (val fetched = fetch(lease, token, get("/question"))) {
            Fetched.Stale -> return staleEffect()
            Fetched.Transport -> return failed("请求没有完成")
            is Fetched.Ok -> {
                if (fetched.http.code != 200) return failed("请求没有完成", fetched.http.code)
                parseQuestions(fetched.http.body) ?: return failed("请求没有完成")
            }
        }
        if (trackedId() != SessionRef.Id(sessionId)) return staleEffect()
        val matches = pending.filter { it.sessionId == sessionId && isSafeId(it.id) }
        if (matches.size != 1) return failed("没有对上待确认的问题")
        val question = matches.single()
        if (answers.size != question.questionCount || question.questionCount <= 0) return badEffect()
        val body = buildJsonObject {
            put("answers", buildJsonArray {
                answers.forEach { row ->
                    add(buildJsonArray { row.forEach { add(JsonPrimitive(it)) } })
                }
            })
        }.toString()
        return when (val posted = fetch(lease, token, post("/question/${question.id}/reply", body))) {
            Fetched.Stale -> staleEffect()
            Fetched.Transport -> failed("请求没有完成")
            is Fetched.Ok -> {
                if (posted.http.code != 200 && posted.http.code != 204) return failed("请求没有完成", posted.http.code)
                done(answeredJson(sessionId))
            }
        }
    }

    private suspend fun archiveJob(
        arguments: String,
        lease: AmbientLease,
        token: Long,
        archiving: Boolean,
    ): AmbientToolEffect {
        val args = jsonObject(arguments) ?: return badEffect()
        val id = when (val ref = sessionRef(args)) {
            is SessionRef.Id -> ref.value
            else -> return badEffect()
        }
        return when (val tree = discover(id, lease, token)) {
            Discovery.Stale -> staleEffect()
            is Discovery.Failed -> tree.effect
            is Discovery.Ready -> writeTree(tree, lease, token, archiving)
        }
    }

    private suspend fun discover(id: String, lease: AmbientLease, token: Long): Discovery {
        val rootHttp = when (val fetched = fetch(lease, token, get(sessionPath(id)))) {
            Fetched.Stale -> return Discovery.Stale
            Fetched.Transport -> return Discovery.Failed(failed("传输失败"))
            is Fetched.Ok -> fetched.http
        }
        if (rootHttp.code == 404) return Discovery.Failed(missingSession(id, 404))
        if (rootHttp.code != 200) return Discovery.Failed(failed("请求没有完成", rootHttp.code))
        val root = parseSessionElement(rootHttp.jsonOrNull()) ?: return Discovery.Failed(failed("请求没有完成"))
        if (root.id != id) return Discovery.Failed(failed("会话没有对上"))
        if (root.directory.isBlank()) return Discovery.Failed(directoryUnknown())
        val pinned = AmbientStartPolicy.createDirectoryArgument(lease.directory)
        if (pinned != null && root.directory != pinned) return Discovery.Failed(directoryUnknown())
        val bound = currentBinding()
        if (bound != null && bound.sessionId == root.id && bound.directory != root.directory) {
            return Discovery.Failed(directoryUnknown())
        }
        val nodes = LinkedHashMap<String, AmbientListItem>()
        val children = LinkedHashMap<String, MutableList<String>>()
        nodes[root.id] = root
        val stack = ArrayDeque<String>()
        stack.addLast(root.id)
        when (val walked = collect(root.id, 0, lease, token, nodes, children, stack)) {
            Collect.Stale -> return Discovery.Stale
            is Collect.Failed -> return Discovery.Failed(walked.effect)
            Collect.Ok -> Unit
        }
        return Discovery.Ready(root, nodes, children.mapValues { it.value.toList() })
    }

    private suspend fun collect(
        parentId: String,
        depth: Int,
        lease: AmbientLease,
        token: Long,
        nodes: MutableMap<String, AmbientListItem>,
        children: MutableMap<String, MutableList<String>>,
        stack: ArrayDeque<String>,
    ): Collect {
        val listed = when (val fetched = fetch(lease, token, get(sessionPath(parentId, "/children")))) {
            Fetched.Stale -> return Collect.Stale
            Fetched.Transport -> return Collect.Failed(failed("传输失败"))
            is Fetched.Ok -> {
                if (fetched.http.code == 404) return Collect.Failed(failed("子会话没有读到", 404))
                if (fetched.http.code != 200) return Collect.Failed(failed("请求没有完成", fetched.http.code))
                parseSessionList(fetched.http.body) ?: return Collect.Failed(failed("请求没有完成"))
            }
        }
        val parent = nodes.getValue(parentId)
        for (child in listed) {
            if (child.id in stack) return Collect.Failed(failed("子会话成环"))
            if (child.id in nodes) continue
            if (!isSafeId(child.id)) return Collect.Failed(badEffect())
            if (depth + 1 > AmbientProtocol.ARCHIVE_MAX_DEPTH) return Collect.Failed(failed("子树超过深度上限"))
            if (nodes.size >= AmbientProtocol.ARCHIVE_MAX_NODES) return Collect.Failed(failed("子树超过节点上限"))
            if (child.directory.isBlank() || child.directory != parent.directory) return Collect.Failed(directoryUnknown())
            nodes[child.id] = child
            children.getOrPut(parentId) { mutableListOf() }.add(child.id)
            stack.addLast(child.id)
            when (val nested = collect(child.id, depth + 1, lease, token, nodes, children, stack)) {
                Collect.Ok -> stack.removeLast()
                else -> return nested
            }
        }
        return Collect.Ok
    }

    private suspend fun writeTree(
        tree: Discovery.Ready,
        lease: AmbientLease,
        token: Long,
        archiving: Boolean,
    ): AmbientToolEffect {
        val order = AmbientArchiveWire.ids(tree.root.id, tree.children, parentFirst = !archiving)
        val stamp = if (archiving) AmbientArchiveWire.archiveMillis(nowEpochMillis()) else 0L
        val body = AmbientArchiveWire.body(stamp)
        val verified = ArrayList<String>()
        for (index in order.indices) {
            val id = order[index]
            val node = tree.nodes[id] ?: return archivePartial(tree, archiving, verified, listOf(id), order.drop(index + 1), "请求没有完成", null)
            when (val patched = fetch(lease, token, patch(sessionPath(id), body))) {
                Fetched.Stale -> return staleEffect()
                Fetched.Transport -> return archivePartial(tree, archiving, verified, listOf(id), order.drop(index + 1), "传输失败", null)
                is Fetched.Ok -> {
                    val code = patched.http.code
                    if (code != 200 && code != 204) {
                        return archivePartial(
                            tree, archiving, verified, listOf(id), order.drop(index + 1),
                            "请求没有完成",
                            if (code == 404) clearFor(id) else null,
                        )
                    }
                }
            }
            when (val read = fetch(lease, token, get(sessionPath(id)))) {
                Fetched.Stale -> return staleEffect()
                Fetched.Transport -> return archivePartial(tree, archiving, verified, listOf(id), order.drop(index + 1), "传输失败", null)
                is Fetched.Ok -> {
                    if (!archiveVerified(read.http, node, stamp, archiving)) {
                        val code = read.http.code
                        return archivePartial(
                            tree, archiving, verified, listOf(id), order.drop(index + 1),
                            if (code == 404) "会话不存在" else "请求没有完成",
                            if (code == 404) clearFor(id) else null,
                        )
                    }
                }
            }
            verified += id
        }
        return done(archiveSuccess(tree.root, archiving, verified))
    }

    private fun archiveVerified(http: AmbientHttpResult, node: AmbientListItem, stamp: Long, archiving: Boolean): Boolean {
        if (http.code != 200) return false
        val read = parseSessionElement(http.jsonOrNull()) ?: return false
        if (read.id != node.id || read.directory != node.directory || read.directory.isBlank()) return false
        return if (archiving) read.archivedAt == stamp else AmbientArchiveWire.isUnarchived(read.archivedAt)
    }

    private suspend fun loadWritableSession(id: String, lease: AmbientLease, token: Long): SessionLoad? {
        when (val fetched = fetch(lease, token, get(sessionPath(id)))) {
            Fetched.Stale -> return SessionLoad.Stop(staleEffect())
            Fetched.Transport -> return SessionLoad.Stop(failed("请求没有完成"))
            is Fetched.Ok -> {
                if (fetched.http.code == 404) return SessionLoad.Stop(missingSession(id, 404))
                if (fetched.http.code != 200) return SessionLoad.Stop(failed("请求没有完成", fetched.http.code))
                val item = parseSessionElement(fetched.http.jsonOrNull()) ?: return SessionLoad.Stop(failed("请求没有完成"))
                if (item.id != id) return SessionLoad.Stop(failed("会话没有对上"))
                if (item.directory.isBlank()) return null
                val pinned = AmbientStartPolicy.createDirectoryArgument(lease.directory)
                if (pinned != null && item.directory != pinned) return null
                val bound = currentBinding()
                if (bound != null && bound.sessionId == item.id && bound.directory != item.directory) return null
                return SessionLoad.Ready(fetched.http)
            }
        }
    }

    /**
     * Missing id in a parsed 200 map is idle. A failed GET, malformed body,
     * or transport error is [IdleRead.Failed], not idle.
     */
    private suspend fun readIdle(id: String, lease: AmbientLease, token: Long): IdleRead {
        return when (val fetched = fetch(lease, token, get("/session/status"))) {
            Fetched.Stale -> IdleRead.Stale
            Fetched.Transport -> IdleRead.Failed
            is Fetched.Ok -> {
                if (fetched.http.code != 200) return IdleRead.Failed
                val map = parseStatus(fetched.http.body) ?: return IdleRead.Failed
                when (map[id]) {
                    null, "idle" -> IdleRead.Value(idle = true)
                    "busy", "retry" -> IdleRead.Value(idle = false)
                    else -> IdleRead.Failed
                }
            }
        }
    }

    private suspend fun fetch(lease: AmbientLease, token: Long, call: AmbientGatewayCall): Fetched {
        if (!owns(lease)) return Fetched.Stale
        return when (val exchange = gateway.exchange(token, call)) {
            AmbientGatewayExchange.StaleLease -> Fetched.Stale
            is AmbientGatewayExchange.TransportFailed -> Fetched.Transport
            is AmbientGatewayExchange.Completed -> if (owns(lease)) Fetched.Ok(exchange.http) else Fetched.Stale
        }
    }

    private fun owns(lease: AmbientLease): Boolean {
        if (lease.ownerToken.isEmpty()) return false
        return currentLease() == lease
    }

    private fun selectedAgent(): String = currentAgent()?.trim().orEmpty()

    private fun trackedId(): SessionRef {
        val id = currentBinding()?.sessionId?.trim().orEmpty()
        if (id.isEmpty()) return SessionRef.Missing
        if (!isSafeId(id)) return SessionRef.Invalid
        return SessionRef.Id(id)
    }

    private fun clearFor(id: String): String? {
        val before = currentBinding()
        val after = AmbientBindingPolicy.afterLookup(before, id, 404)
        return if (before != null && after == null) id else null
    }

    private fun missingSession(id: String, code: Int, message: String = "会话不存在"): AmbientToolEffect =
        failed(message, code, clearFor(id))

    private fun providerHas(body: String, ref: AmbientModelRef): Boolean {
        val root = jsonObject(body) ?: return false
        val connected = asArray(root["connected"])?.mapNotNull { jsonString(it) } ?: return false
        if (ref.providerId !in connected) return false
        val provider = asArray(root["all"])?.firstNotNullOfOrNull { element ->
            val objectJson = asObject(element) ?: return@firstNotNullOfOrNull null
            if (jsonString(objectJson["id"]) == ref.providerId) objectJson else null
        } ?: return false
        val models = asObject(provider["models"]) ?: return false
        return models.containsKey(ref.modelId)
    }

    private fun inheritAgent(session: JsonObject, messages: JsonArray): Inherited {
        var agent = jsonString(session["agent"])?.trim()?.ifEmpty { null }
        var variant = jsonString(session["variant"])?.trim()?.ifEmpty { null }
        val rows = ArrayList<Triple<Long, String?, String?>>()
        for (element in messages) {
            val wrapper = asObject(element) ?: continue
            val info = asObject(wrapper["info"]) ?: wrapper
            val created = jsonLong(asObject(info["time"])?.get("created")) ?: Long.MIN_VALUE
            rows += Triple(
                created,
                jsonString(info["agent"])?.trim()?.ifEmpty { null },
                jsonString(info["variant"])?.trim()?.ifEmpty { null },
            )
        }
        rows.sortByDescending { it.first }
        for ((_, rowAgent, rowVariant) in rows) {
            if (agent == null && rowAgent != null) agent = rowAgent
            if (variant == null && rowVariant != null) variant = rowVariant
        }
        return Inherited(agent, variant)
    }

    private fun paging(args: JsonObject): Paging? {
        val archivedOnly = when (val element = args["archivedOnly"]) {
            null, is JsonNull -> AmbientToolDefaults.ARCHIVED_ONLY
            else -> jsonBoolean(element) ?: return null
        }
        val skip = when (val element = args["skip"]) {
            null, is JsonNull -> AmbientToolDefaults.SKIP
            else -> jsonWhole(element) ?: return null
        }
        val count = when (val element = args["count"]) {
            null, is JsonNull -> AmbientToolDefaults.COUNT
            else -> jsonWhole(element) ?: return null
        }
        if (skip < 0 || count < AmbientProtocol.TOOL_COUNT_MIN || count > AmbientProtocol.TOOL_COUNT_MAX) return null
        return Paging(archivedOnly, skip, count)
    }

    private fun permissionReply(args: JsonObject): ReplyKind {
        if (!args.containsKey("permissionReply")) return ReplyKind.Invalid
        val element = args["permissionReply"] ?: return ReplyKind.Invalid
        if (element is JsonNull) return ReplyKind.Question
        val text = jsonString(element) ?: return ReplyKind.Invalid
        return if (text == "once" || text == "reject") ReplyKind.Permission(text) else ReplyKind.Invalid
    }

    private fun sessionRef(args: JsonObject): SessionRef {
        if (!args.containsKey("sessionID")) return SessionRef.Missing
        val element = args["sessionID"] ?: return SessionRef.Missing
        if (element is JsonNull) return SessionRef.Missing
        val text = jsonString(element) ?: return SessionRef.Invalid
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return SessionRef.Missing
        if (!isSafeId(trimmed)) return SessionRef.Invalid
        return SessionRef.Id(trimmed)
    }

    private fun isSafeId(id: String): Boolean =
        id.isNotEmpty() &&
            id.length <= 200 &&
            id.none { it == '/' || it == '\\' || it == '?' || it == '#' || it.isWhitespace() || it.isISOControl() }

    private fun pageQuery(cursor: Int?): Map<String, String> =
        LinkedHashMap<String, String>().apply {
            for ((key, value) in AmbientPromptWire.sessionPageQuery(cursor)) put(key, value)
        }

    private fun sessionPath(id: String, suffix: String = ""): String = "/session/$id$suffix"

    private fun get(path: String, query: Map<String, String> = emptyMap()) =
        AmbientGatewayCall("GET", path, query)

    private fun post(path: String, body: String, query: Map<String, String> = emptyMap()) =
        AmbientGatewayCall("POST", path, query, body)

    private fun patch(path: String, body: String) =
        AmbientGatewayCall("PATCH", path, emptyMap(), body)

    private fun accepted(code: Int): Boolean = code == 200 || code == 202 || code == 204

    private fun done(
        output: String,
        sessionId: String? = null,
        messageId: String? = null,
        directory: String? = null,
        model: String? = null,
        clearSessionId: String? = null,
        poll: Boolean = false,
    ) = AmbientToolEffect(output, sessionId, messageId, directory, model, clearSessionId, poll)

    private fun staleEffect() = done(AmbientToolJson.error(AmbientToolCodes.STALE_CONTEXT, AmbientToolCodes.STALE_CONTEXT_MESSAGE))

    private fun badEffect() = done(AmbientToolJson.error(AmbientToolCodes.BAD_ARGUMENTS, AmbientToolCodes.BAD_ARGUMENTS_MESSAGE))

    private fun unknownEffect() = done(AmbientToolJson.error(AmbientToolCodes.UNKNOWN_FUNCTION, AmbientToolCodes.UNKNOWN_FUNCTION_MESSAGE))

    private fun unknownModel() = done(AmbientToolJson.error(AmbientToolCodes.UNKNOWN_MODEL, "未知模型"))

    private fun directoryUnknown() = done(AmbientToolJson.error(AmbientToolCodes.DIRECTORY_UNKNOWN, AmbientToolCodes.DIRECTORY_UNKNOWN_MESSAGE))

    private fun needsInput(message: String) = done(AmbientToolJson.error(AmbientToolCodes.NEEDS_INPUT, message))

    private fun failed(message: String, httpStatus: Int? = null, clearSessionId: String? = null) =
        done(AmbientToolJson.error(AmbientToolCodes.FAILED, message, httpStatus), clearSessionId = clearSessionId)

    private fun notConfirmed(httpStatus: Int?) =
        done(AmbientToolJson.error(AmbientToolCodes.NOT_CONFIRMED, AmbientToolCodes.NOT_CONFIRMED_MESSAGE, httpStatus))

    private fun acceptedEffect(sessionId: String, messageId: String, directory: String, model: String?) =
        done(
            output = AmbientToolJson.accepted(sessionId, messageId, model),
            sessionId = sessionId,
            messageId = messageId,
            directory = directory,
            model = model,
            poll = true,
        )

    private fun stoppedJson(): String = AmbientToolJson.stopped()

    private fun answeredJson(sessionId: String): String = buildJsonObject {
        put("ok", true)
        put("status", "answered")
        put("sessionID", sessionId)
    }.toString()

    private fun archiveSuccess(root: AmbientListItem, archiving: Boolean, verified: List<String>): String = buildJsonObject {
        put("ok", true)
        put("status", if (archiving) "archived" else "unarchived")
        put("sessionID", root.id)
        put("title", root.title)
        put("archived", archiving)
        put("snapshot", true)
        put("verified", verified.size)
        put("verifiedIDs", buildJsonArray { verified.forEach { add(JsonPrimitive(it)) } })
    }.toString()

    private fun archivePartial(
        tree: Discovery.Ready,
        archiving: Boolean,
        verified: List<String>,
        failedIds: List<String>,
        pending: List<String>,
        message: String,
        clearSessionId: String?,
    ): AmbientToolEffect {
        val rootVerified = tree.root.id in verified
        val archived = if (rootVerified) archiving else AmbientArchiveWire.isArchived(tree.root.archivedAt)
        return done(
            output = AmbientArchiveWire.partial(
                sessionId = tree.root.id,
                title = tree.root.title,
                archived = archived,
                verified = verified,
                failed = failedIds,
                pending = pending,
                error = AmbientToolCodes.FAILED,
                message = message,
            ),
            clearSessionId = clearSessionId,
        )
    }

    private fun renderList(window: AmbientListWindow): String {
        val rows = buildJsonArray { window.records.forEach { add(recordJson(it)) } }
        val payload = buildJsonObject {
            put("ok", true)
            put("records", rows)
            put("sessions", rows)
            put("pagination", buildJsonObject {
                put("skip", window.skip)
                put("count", window.count)
                put("returned", window.returned)
                if (window.hasMore != null) put("hasMore", window.hasMore)
                if (window.nextSkip != null) put("nextSkip", window.nextSkip)
                if (window.partial) put("partial", true)
            })
        }
        return AmbientListOutput.bound(payload)
    }

    private fun singleWindow(item: AmbientListItem) = AmbientListWindow(
        records = listOf(item),
        skip = 0,
        count = 1,
        returned = 1,
        hasMore = false,
        nextSkip = null,
        partial = false,
        fetchMore = false,
    )

    private fun recordJson(item: AmbientListItem): JsonObject = buildJsonObject {
        put("id", item.id)
        put("title", item.title)
        put("directory", item.directory)
        put("status", item.status)
        put("updated", item.updated.toString())
        put("archived", if (item.archivedAt > 0L) "true" else "false")
    }

    private fun parseListPage(http: AmbientHttpResult): AmbientListPage? {
        val rows = parseSessionList(http.body) ?: return null
        val header = http.header(AmbientProtocol.CURSOR_HEADER)
        val next = when (header) {
            null -> null
            else -> header.toIntOrNull() ?: return null
        }
        return AmbientListPage(rows, next)
    }

    private fun parseSessionList(body: String): List<AmbientListItem>? {
        val element = parseElement(body) ?: return null
        val array = asArray(element) ?: asArray(asObject(element)?.get("sessions")) ?: return null
        val rows = ArrayList<AmbientListItem>(array.size)
        for (item in array) {
            rows += parseSessionElement(item) ?: return null
        }
        return rows
    }

    private fun parseSessionElement(element: JsonElement?): AmbientListItem? {
        val objectJson = asObject(element) ?: return null
        val id = jsonString(objectJson["id"])?.trim().orEmpty()
        if (!isSafeId(id)) return null
        val time = asObject(objectJson["time"])
        return AmbientListItem(
            id = id,
            title = jsonString(objectJson["title"]).orEmpty(),
            directory = jsonString(objectJson["directory"]).orEmpty(),
            status = jsonString(objectJson["status"]).orEmpty(),
            updated = jsonLong(time?.get("updated")) ?: 0L,
            archivedAt = jsonLong(time?.get("archived")) ?: 0L,
            parentId = jsonString(objectJson["parentID"]).orEmpty(),
        )
    }

    private fun parseHistory(body: String): HistoryPage? {
        val array = messageArray(body) ?: return null
        val drafts = ArrayList<AmbientHistoryDraft>()
        for (element in array) {
            val wrapper = asObject(element) ?: return null
            val info = asObject(wrapper["info"]) ?: wrapper
            val role = jsonString(info["role"]) ?: return null
            if (role != "user" && role != "assistant") continue
            val id = jsonString(info["id"])?.trim().orEmpty()
            if (!isSafeId(id)) return null
            val time = asObject(info["time"]) ?: asObject(wrapper["time"])
            val parts = wrapper["parts"]
            val texts = ArrayList<String>()
            var synthetic = false
            if (parts != null && parts !is JsonNull) {
                val partArray = asArray(parts) ?: return null
                for (partElement in partArray) {
                    val part = asObject(partElement) ?: return null
                    if (jsonString(part["type"]) != "text") continue
                    val text = jsonString(part["text"]) ?: return null
                    texts += text
                    if (jsonBoolean(part["synthetic"]) == true) synthetic = true
                }
            }
            if (texts.isEmpty()) continue
            drafts += AmbientHistoryDraft(
                id = id,
                role = role,
                createdMs = jsonLong(time?.get("created")),
                completedMs = jsonLong(time?.get("completed")),
                text = texts.joinToString(separator = ""),
                synthetic = synthetic,
            )
        }
        return HistoryPage(drafts, array.size)
    }

    private fun messageArray(body: String): JsonArray? {
        val element = parseElement(body) ?: return null
        return asArray(element) ?: asArray(asObject(element)?.get("messages"))
    }

    private fun parseStatus(body: String): Map<String, String>? {
        val objectJson = jsonObject(body) ?: return null
        val map = LinkedHashMap<String, String>()
        for ((key, value) in objectJson) {
            val type = jsonString(asObject(value)?.get("type")) ?: return null
            map[key] = type
        }
        return map
    }

    private fun parsePermissions(body: String): List<PendingPermission>? {
        val array = asArray(parseElement(body)) ?: return null
        val rows = ArrayList<PendingPermission>()
        for (element in array) {
            val objectJson = asObject(element) ?: return null
            val id = jsonString(objectJson["id"])?.trim().orEmpty()
            val sessionId = jsonString(objectJson["sessionID"])?.trim().orEmpty()
            if (!isSafeId(id) || !isSafeId(sessionId)) return null
            rows += PendingPermission(id, sessionId)
        }
        return rows
    }

    private fun parseQuestions(body: String): List<PendingQuestion>? {
        val array = asArray(parseElement(body)) ?: return null
        val rows = ArrayList<PendingQuestion>()
        for (element in array) {
            val objectJson = asObject(element) ?: return null
            val id = jsonString(objectJson["id"])?.trim().orEmpty()
            val sessionId = jsonString(objectJson["sessionID"])?.trim().orEmpty()
            val questions = asArray(objectJson["questions"]) ?: return null
            if (!isSafeId(id) || !isSafeId(sessionId)) return null
            rows += PendingQuestion(id, sessionId, questions.size)
        }
        return rows
    }

    private fun parseAnswers(element: JsonElement?): List<List<String>>? {
        val array = asArray(element) ?: return null
        val rows = ArrayList<List<String>>()
        for (rowElement in array) {
            val row = asArray(rowElement) ?: return null
            val values = ArrayList<String>()
            for (value in row) values += jsonString(value) ?: return null
            rows += values
        }
        return rows
    }

    private fun parseElement(body: String): JsonElement? = try {
        com.yage.opencode_client.ambient.protocol.ambientJson.parseToJsonElement(body)
    } catch (_: Exception) {
        null
    }

    private fun jsonLong(element: JsonElement?): Long? {
        val primitive = element as? JsonPrimitive ?: return null
        if (primitive.isString || primitive.booleanOrNull != null) return null
        primitive.longOrNull?.let { return it }
        val value = primitive.doubleOrNull ?: return null
        if (!value.isFinite() || value < 0.0) return null
        return value.toLong()
    }

    private data class Paging(val archivedOnly: Boolean, val skip: Int, val count: Int)
    private data class Inherited(val agent: String?, val variant: String?)
    private data class HistoryPage(val drafts: List<AmbientHistoryDraft>, val rawCount: Int)
    private data class PendingPermission(val id: String, val sessionId: String)
    private data class PendingQuestion(val id: String, val sessionId: String, val questionCount: Int)

    private sealed interface Fetched {
        data class Ok(val http: AmbientHttpResult) : Fetched
        data object Stale : Fetched
        data object Transport : Fetched
    }

    private sealed interface IdleRead {
        data class Value(val idle: Boolean) : IdleRead
        data object Stale : IdleRead
        data object Failed : IdleRead
    }

    private sealed interface ReplyKind {
        data class Permission(val value: String) : ReplyKind
        data object Question : ReplyKind
        data object Invalid : ReplyKind
    }

    private sealed interface SessionRef {
        data class Id(val value: String) : SessionRef
        data object Missing : SessionRef
        data object Invalid : SessionRef
    }

    private sealed interface SessionLoad {
        data class Ready(val http: AmbientHttpResult) : SessionLoad
        data class Stop(val effect: AmbientToolEffect) : SessionLoad
    }

    private sealed interface Discovery {
        data class Ready(
            val root: AmbientListItem,
            val nodes: Map<String, AmbientListItem>,
            val children: Map<String, List<String>>,
        ) : Discovery
        data object Stale : Discovery
        data class Failed(val effect: AmbientToolEffect) : Discovery
    }

    private sealed interface Collect {
        data object Ok : Collect
        data object Stale : Collect
        data class Failed(val effect: AmbientToolEffect) : Collect
    }
}

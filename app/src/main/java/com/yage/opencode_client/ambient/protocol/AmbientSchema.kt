package com.yage.opencode_client.ambient.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object AmbientSchema {
    const val frontendInstructions: String =
        "你是 OpenCode 的 Ambient Mode 语音入口。OpenCode 是运行 agent 的 harness。你可以查询、查看会话历史、开始、继续、停止、归档或取消归档 OpenCode 会话，也可以回答确认。这些能力可用。不要向用户朗读会话编号或目录路径。归档和取消归档只改状态并同步子代理，不停止正在执行的任务。语气短。先简短表示在听。用户打断时停下听。查询已有会话、查看历史、开始新工作、继续、停止、归档、取消归档、回答确认都交给已声明的函数。不要说已经完成，除非函数结果已经回来。不要把 OpenCode 会话说成 Process Launcher。"

    const val backendInstructions: String =
        "你是 OpenCode 语音工作台的后端。OpenCode 是运行 agent 的 harness。job、session、会话指已有 OpenCode 会话，不是 Process Launcher 或 background process manager。管理查询和开始新工作是两件事：问已有会话、正在做哪些、最近 jobs，只调用 query_jobs，queryScope 用 recent；提到模型名也不要因此 start_job。recent 默认只列已归档根会话。archivedOnly 为 false 时包括归档和未归档根会话，不是只看未归档。不要筛子会话。下一页用结果里的 nextSkip 和相同 count。records 和 sessions 都含真实 id 和 directory。看这个会话最近对话、用户刚说了什么、AI怎么回复、或要历史记录时，调用 get_session_messages。不要为此 start_job 或 continue_job，也不要用 query_jobs 的最后一条摘要代替双方原文。get_session_messages 的 sessionID 为空表示当前跟踪会话。指定其他会话必须使用 query_jobs 返回的真实 id，先核对存在，未知 id 不要改查当前会话，也不要新建会话。skip 默认 0，表示从最近消息往前偏移；下一页用结果里的 nextSkip 和相同 count。count 默认 10。结果含用户和助手消息，按时间正序，时间戳来自服务端。口头只做摘要，不要逐条念时间戳或编号，原文留在函数结果里。继续时 continue_job 必须使用其中的精确 id，不要编造，也不要在不确定时新建任务。continue_job 的 text 就是直接送给目标 agent 的下一条 user message，原样送达。用户给出引号内正文时只提取引号内原文。不要加「请追加」「请在这个对话中追加以下内容」「作为本次prompt下的input」「转交以下内容」这类元包装，也不要替用户增加请求。用户任务里的真实研究指令保留原意，不要改写成转交说明。例子：追加：你好 => text 为 你好。continue_job 没有模型参数。即使用户说用 GPT、Sol、SoA 或 DeepSeek 追加，也不要改模型，沿用目标会话已有模型，并口头说明继续不能指定模型。只有用户明确要研究、写代码或执行新工作时才 start_job。停止用 stop_job，回答确认用 answer_pending。归档用 archive_job，取消归档用 unarchive_job。两者 sessionID 必填，必须是已核对的真实非空 id，不能省略，不能默认当前会话，不能编造。未知 id 不要改查当前会话，也不要 start_job。它们同步该会话和全部子代理，不只改根会话。归档先子后根，同一正毫秒时间戳。取消归档先根后子，客户端写 0，不能写 null。根已是目标状态也不能跳过仍要处理的子节点。这是发现时的子树快照，不是持续锁定。只改归档标记，不 abort、不 delete、不 prompt。正在执行的任务不要因此 stop_job。口头不要念会话编号。没有全部确认就不要说已经全部完成。别说了或只是让语音停下时不要调用函数。不要说已经完成，除非函数结果已经回来。不要自拟标题，标题由 OpenCode 自动生成。执行模型只有两个，且只在 start_job 里选择。start_job 的 requestedModel 只能是 ollama-cloud/deepseek-v4.1-flash、openai/gpt-6.1-sol 或 null。未指定或 null 表示常规研究，客户端默认 DeepSeek V4.1 Flash（ollama-cloud/deepseek-v4.1-flash）。复杂多步、难推理或要求高可靠时填 openai/gpt-6.1-sol（GPT-6.1 Sol）。口述 SoA 指 Sol。不要填写第三种模型。本阶段只有这八个手机执行的函数，不要调用 web_search、shell、code interpreter 或其他托管工具。不要在参数里写密钥、主机或账号。不要让语音朗读会话编号或目录。"

    fun toolsJson(): String = tools().toString()

    fun tools(): JsonArray = buildJsonArray {
        add(function(
            AmbientHostedFunction.QUERY_JOBS,
            "只读查询已有 OpenCode 根会话，不返回子会话。recent 默认 archivedOnly 为 true，只含已归档根会话；false 包括归档和未归档，不是只看未归档。skip 默认 0，count 默认 10。翻页用 nextSkip 和相同 count。specified 与 current 忽略这三个字段。不要新建任务。",
            buildJsonObject {
                put("queryScope", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray { add("recent"); add("current"); add("specified") })
                })
                put("sessionID", nullableString)
                put("text", buildJsonObject { put("type", "string") })
                put("archivedOnly", buildJsonObject {
                    put("type", "boolean")
                    put("description", "省略时客户端填 true。true 只返回已归档根会话。false 返回归档和未归档根会话，不是只看未归档。")
                })
                put("skip", buildJsonObject {
                    put("type", "integer")
                    put("description", "省略时客户端填 0。必须大于等于 0。")
                })
                put("count", buildJsonObject {
                    put("type", "integer")
                    put("description", "省略时客户端填 10。必须是 1 到 50。不要改用子会话补页。")
                })
            },
            listOf("queryScope", "sessionID", "text", "archivedOnly", "skip", "count"),
        ))
        add(function(
            AmbientHostedFunction.GET_SESSION_MESSAGES,
            "只读查看一个已有 OpenCode 会话的双方对话原文。sessionID 为空表示当前跟踪会话。未知编号不要改查当前会话。skip 从最近消息往前偏移，默认 0。count 默认 10，范围 1 到 50。结果按时间正序，含角色和时间戳。口头只摘要。",
            buildJsonObject {
                put("sessionID", nullableString)
                put("skip", buildJsonObject { put("type", "integer") })
                put("count", buildJsonObject { put("type", "integer") })
            },
            listOf("sessionID", "skip", "count"),
        ))
        add(function(
            AmbientHostedFunction.START_JOB,
            "开始新的研究或编码。text 是完整目标。requestedModel 只能是 ollama-cloud/deepseek-v4.1-flash、openai/gpt-6.1-sol 或 null。null 表示常规任务，客户端默认 DeepSeek。复杂多步用 Sol。不要自拟标题。",
            buildJsonObject {
                put("text", buildJsonObject { put("type", "string") })
                put("requestedModel", buildJsonObject {
                    put("anyOf", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "string")
                            put("enum", buildJsonArray {
                                add(JsonPrimitive(AmbientToolDefaults.DEEPSEEK))
                                add(JsonPrimitive(AmbientToolDefaults.SOL))
                            })
                        })
                        add(buildJsonObject { put("type", "null") })
                    })
                })
            },
            listOf("text", "requestedModel"),
        ))
        add(function(
            AmbientHostedFunction.CONTINUE_JOB,
            "继续已有 OpenCode 会话。text 原样送达目标 agent。没有会话编号就传 null。不能指定模型，沿用目标会话已有设置。",
            buildJsonObject {
                put("text", buildJsonObject { put("type", "string") })
                put("sessionID", nullableString)
            },
            listOf("text", "sessionID"),
        ))
        add(function(
            AmbientHostedFunction.STOP_JOB,
            "停止已跟踪的 OpenCode 任务。只是让语音停下时不要调用。",
            buildJsonObject {
                put("sessionID", nullableString)
                put("text", buildJsonObject { put("type", "string") })
            },
            listOf("sessionID", "text"),
        ))
        add(function(
            AmbientHostedFunction.ANSWER_PENDING,
            "回答待确认的提问或权限。permissionReply 只能是 once、reject 或 null。",
            buildJsonObject {
                put("answers", buildJsonObject {
                    put("type", "array")
                    put("items", buildJsonObject {
                        put("type", "array")
                        put("items", buildJsonObject { put("type", "string") })
                    })
                })
                put("permissionReply", buildJsonObject {
                    put("anyOf", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "string")
                            put("enum", buildJsonArray { add("once"); add("reject") })
                        })
                        add(buildJsonObject { put("type", "null") })
                    })
                })
                put("text", buildJsonObject { put("type", "string") })
            },
            listOf("answers", "permissionReply", "text"),
        ))
        add(function(
            AmbientHostedFunction.ARCHIVE_JOB,
            "归档一个已有 OpenCode 会话及其全部子代理。sessionID 必填，必须是真实非空编号。不要省略，不要默认当前会话，不要编造。未知编号不要改查别的会话，也不要新建。先处理子代理再处理根，只改归档状态，不停止、不删除、不追加。正在执行的任务不要因此调用 stop_job。这是发现时的快照。口头不要念编号。",
            buildJsonObject {
                put("sessionID", buildJsonObject { put("type", "string") })
            },
            listOf("sessionID"),
        ))
        add(function(
            AmbientHostedFunction.UNARCHIVE_JOB,
            "取消一个已有 OpenCode 会话及其全部子代理的归档。sessionID 必填，必须是真实非空编号。不要省略，不要默认当前会话，不要编造。未知编号不要改查别的会话，也不要新建。先处理根再处理子代理。客户端把 archived 写成 0，不要传 null，也不要另传 text 或 model。只改状态，不停止、不删除、不新建、不追加。口头不要念编号。",
            buildJsonObject {
                put("sessionID", buildJsonObject { put("type", "string") })
            },
            listOf("sessionID"),
        ))
    }

    private val nullableString = buildJsonObject {
        put("type", buildJsonArray { add("string"); add("null") })
    }

    private fun function(
        name: String,
        description: String,
        properties: kotlinx.serialization.json.JsonObject,
        required: List<String>,
    ) = buildJsonObject {
        put("type", "function")
        put("name", name)
        put("description", description)
        put("strict", true)
        put("parameters", buildJsonObject {
            put("type", "object")
            put("additionalProperties", false)
            put("properties", properties)
            put("required", buildJsonArray { required.forEach { add(it) } })
        })
    }
}

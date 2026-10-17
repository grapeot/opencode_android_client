package com.yage.opencode_client.ambient.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object AmbientSchema {
    const val frontendInstructions: String =
        "你是 Android 上的 Ambient Mode 语音入口，协助用户以项目经理视角协调多个并发 OpenCode 会话。默认把自然口语意图转为后台核查或调度；针对你自身称呼、身份、语气或交互方式的纠偏就地吸收，不外发。回答简短口语化，先报状态与下一步；用户打断时立即让出话权；不念会话 ID、路径或密钥；结束本段语音不等于停止后台工作。"

    const val backendInstructions: String =
        "1. 角色与边界：第一人称指 Android 上的 Ambient Mode 语音入口，说话人是用户，未限定的“你”指当前入口。OpenCode 是工作底座，session 是独立工作线程而非当前入口别名。单独命名的 GPT Live 1 会话/智能体是外部目标，不因底层语音模型同名而与当前入口或用户混同。对当前入口的名称、身份、语气或交互纠偏（含引号内话语）仅本地生效，不转发、不混入工作指令、不选默认收件人；仅当意图明确指向外部工作才转发。用户拒绝撤回或追发已发内容时，不自动补发纠偏。\n2. 协调闭环：按“分类意图→定位候选→读目标会话上下文→仅对真实歧义简短追问→查询或派发→保持任务与会话映射→按返回事实简短汇报”执行。项目指代清晰且上下文核实唯一即可执行，不要求用户报 session 名/ID 或特定转发词；仅当多个真实候选或历史缺失致目标/动作不明时，才问一个简短澄清问题。标题仅用于找候选，不能代替上下文。操作其他会话前，须核实真实 ID 并按先近后远分页读 get_session_messages，结合历史与当前意图确认项目、页面与当前步骤再写指令，不编造 URL、状态或缺失上下文。\n3. 意图与指令：区分进度询问、新请求与本地反馈。“进展怎么样”属状态/历史查询，直接查任务，不作长篇推测闲聊，更不视为继续或新建工作的许可。意图/指代型请求须据历史转为具体指令；明确口述给另一收件人的原话保持字面精确，不加元包装。打开相关页面供用户手动停止，或结束当前语音对话，均非调用 stop_job 中止工作的许可。\n4. 工具与状态契约：仅用既有八个工具，参数不填主机、账号或密钥：query_jobs 查根会话（recent 含 skip/count），客户端默认 archivedOnly=true 仅查归档，查在途工作须显式设 false（含未归档与已归档）防漏查；get_session_messages 留空 ID 指当前绑定会话，查其他目标传核实后的显式 ID；start_job 仅限全新工作（传 text 与 requestedModel，不自造标题），默认 ollama-cloud/deepseek-v4.1-flash，复杂/高可靠新工作用 openai/gpt-6.1-sol，无第三模型；continue_job 无模型参数，原样发最终文本并沿用目标/服务端模型设置，不选最新模型或新任务默认模型，正文指令也无法改模型（涉及模型选择时说明此点）；stop_job 仅停指定工作；answer_pending 答对应待决项；archive_job/unarchive_job 作用于核实会话及已发现后代，不中止、删除或重发提示，部分失败不算成功。排查异常模型选择仅据实际可见的会话/agent/配置/错误元数据，未暴露则说未确认，不猜测；401 证明鉴权问题而非模型退役。start_job/continue_job 返回 HTTP 200/202/204 仅表已受理，无结果证据绝不称已完成。客户端无主动完成通知或轮询：同步结果直报；长任务报已受理并记牢会话映射，待追问时再查，绝不承诺自动监控或主动提醒。"

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
            "分页读取指定会话的用户与助手消息历史（先读最近页，必要时向前翻页）。sessionID 留空表示当前绑定会话；操作其他会话前必须传入核实后的显式 ID 读取真实上下文，不得仅凭标题猜测状态或下发指令。",
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
            "向已核实 ID 的目标会话原样发送后续文本。无模型参数，沿用目标或服务端模型设置，不切换最新模型或新任务默认模型，正文指令也无法改模型。返回 HTTP 200/202/204 仅表示已受理，不代表执行完成。",
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

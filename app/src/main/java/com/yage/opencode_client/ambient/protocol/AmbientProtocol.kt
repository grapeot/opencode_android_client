package com.yage.opencode_client.ambient.protocol

/**
 * Wire and budget constants for Ambient. Token prices and history byte caps are transport
 * limits, not the native model's context window.
 */
object AmbientProtocol {
    const val FIXED_VOICE_MODEL = "gpt-live-1"
    const val FIXED_BACKEND_MODEL = "gpt-6-luna"
    const val PARALLEL_TOOL_CALLS = false
    const val DEFAULT_VOICE = "marin"
    const val SESSIONS_PATH = "/v1/audio/live/sessions"
    const val CREATE_TIMEOUT_MILLIS = 20_000L
    const val FOLLOW_REDIRECTS = false
    const val CLOSE_GRACE_NANOS = 15_000_000_000L
    const val CLOSE_POLL_NANOS = 20_000_000L
    const val MAX_APPEND_BYTES = 2000
    const val MAX_FUNCTION_OUTPUT_BYTES = 64 * 1024
    const val MAX_AUDIO_BYTES = 480_000
    const val MAX_HISTORY_MESSAGES = 128
    const val MAX_HISTORY_BYTES = 8192
    const val SAMPLE_RATE_HZ = 24_000
    const val SHUTDOWN_ABORTS_OPEN_CODE = false
    const val CURSOR_HEADER = "x-next-cursor"
    const val ROOT_PAGE_LIMIT = 50
    const val ROOT_PAGE_MAX = 8
    const val HISTORY_FETCH_LIMIT = 200
    const val TOOL_COUNT_MIN = 1
    const val TOOL_COUNT_MAX = 50
    const val TOOL_COUNT_DEFAULT = 10
    const val ARCHIVE_MAX_NODES = 64
    const val ARCHIVE_MAX_DEPTH = 32
    const val CAPTION_LIMIT = 200
    const val ACTIVITY_LIMIT = 50
    const val SPEECH_GAP_MS = 2_000

    const val ESTIMATE_NOTE = "标准价估算，不是账单"
}

object AmbientStringKeys {
    const val TITLE = "ambient_title"
    const val NAV = "ambient_nav"
    const val ENABLE = "ambient_enable"
    const val MUTE = "ambient_mute"
    const val STOP_PLAYBACK = "ambient_stop_playback"
    const val END = "ambient_end"
    const val NOTIFICATION_CHANNEL = "ambient_notification_channel"
    const val NOTIFICATION_TEXT = "ambient_notification_text"
    const val NOTIFICATION_STOP = "ambient_notification_stop"
    const val USAGE_UNCONFIRMED = "ambient_usage_unconfirmed"
}

object AmbientHostedFunction {
    const val QUERY_JOBS = "query_jobs"
    const val GET_SESSION_MESSAGES = "get_session_messages"
    const val START_JOB = "start_job"
    const val CONTINUE_JOB = "continue_job"
    const val STOP_JOB = "stop_job"
    const val ANSWER_PENDING = "answer_pending"
    const val ARCHIVE_JOB = "archive_job"
    const val UNARCHIVE_JOB = "unarchive_job"

    val names: List<String> = listOf(
        QUERY_JOBS,
        GET_SESSION_MESSAGES,
        START_JOB,
        CONTINUE_JOB,
        STOP_JOB,
        ANSWER_PENDING,
        ARCHIVE_JOB,
        UNARCHIVE_JOB,
    )
}

object AmbientToolDefaults {
    const val ARCHIVED_ONLY = true
    const val SKIP = 0
    const val COUNT = AmbientProtocol.TOOL_COUNT_DEFAULT
    const val DEEPSEEK = "ollama-cloud/deepseek-v4.1-flash"
    const val SOL = "openai/gpt-6.1-sol"
    val schemaRefs: List<String> = listOf(DEEPSEEK, SOL)
}

object AmbientToolCodes {
    const val STALE_CONTEXT = "stale_context"
    const val STALE_CONTEXT_MESSAGE = "语音上下文已关闭"
    const val DIRECTORY_UNKNOWN = "directory_unknown"
    const val DIRECTORY_UNKNOWN_MESSAGE = "会话目录没有核对"
    const val BAD_ARGUMENTS = "bad_arguments"
    const val BAD_ARGUMENTS_MESSAGE = "参数无法解析"
    const val UNKNOWN_FUNCTION = "unknown_function"
    const val UNKNOWN_FUNCTION_MESSAGE = "未知函数"
    const val UNKNOWN_MODEL = "unknown_model"
    const val NEEDS_INPUT = "needs_input"
    const val OUTPUT_TOO_LARGE = "output_too_large"
    const val OUTPUT_TOO_LARGE_MESSAGE = "结果超过 64KiB，已改为有界错误"
    const val ENCODE = "encode"
    const val ENCODE_MESSAGE = "结果没有送出"
    const val NOT_CONFIRMED = "not_confirmed"
    const val NOT_CONFIRMED_MESSAGE = "停止尚未确认"
    const val FAILED = "failed"
    const val HISTORY_FAILED_MESSAGE = "会话记录没有送出"
    const val LIST_FAILED_MESSAGE = "查询结果没有送出"
}

object AmbientRates {
    const val MODEL_ID = "gpt-6-luna"
    const val LONG_CONTEXT_INPUT = 272_000
    const val INPUT = 0.10
    const val CACHED_READ = 0.01
    const val CACHE_WRITE = 0.125
    const val OUTPUT = 0.50
    const val LONG_INPUT_MULTIPLIER = 2.0
    const val LONG_OUTPUT_MULTIPLIER = 1.5
    val standardTiers: Set<String> = setOf("default", "standard", "auto")

    fun prices(model: String): Boolean {
        val token = model.trim().lowercase()
        return token.isEmpty() || token == MODEL_ID || token.startsWith("$MODEL_ID-")
    }
}

object AmbientModels {
    fun canonicalize(raw: String?): Result<String> {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return Result.success(AmbientToolDefaults.DEEPSEEK)
        if (trimmed in AmbientToolDefaults.schemaRefs) return Result.success(trimmed)
        val folded = trimmed.lowercase().filter { it.isLetterOrDigit() }
        if (folded in setOf("deepseek", "deepseekv41flash", "ollamaclouddeepseekv41flash")) {
            return Result.success(AmbientToolDefaults.DEEPSEEK)
        }
        if (folded in setOf("gpt", "gpt61", "gpt61sol", "sol", "soa", "openaigpt61sol")) {
            return Result.success(AmbientToolDefaults.SOL)
        }
        return Result.failure(AmbientModelException(AmbientToolCodes.UNKNOWN_MODEL))
    }

    fun split(ref: String): AmbientModelRef? {
        val slash = ref.indexOf('/')
        if (slash <= 0 || slash == ref.lastIndex) return null
        val provider = ref.substring(0, slash)
        val model = ref.substring(slash + 1)
        if (provider.isEmpty() || model.isEmpty()) return null
        return AmbientModelRef(provider, model)
    }
}

data class AmbientModelRef(val providerId: String, val modelId: String)

class AmbientModelException(val code: String) : Exception(code)

class AmbientRejected(val reason: String) : Exception(reason)

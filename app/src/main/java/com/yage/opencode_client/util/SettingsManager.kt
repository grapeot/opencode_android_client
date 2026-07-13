package com.yage.opencode_client.util

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsManager @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val encryptedPrefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "opencode_settings",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    var serverUrl: String
        get() = encryptedPrefs.getString(KEY_SERVER_URL, DEFAULT_SERVER) ?: DEFAULT_SERVER
        set(value) = encryptedPrefs.edit().putString(KEY_SERVER_URL, value).apply()

    var username: String?
        get() = encryptedPrefs.getString(KEY_USERNAME, null)
        set(value) = encryptedPrefs.edit().putString(KEY_USERNAME, value).apply()

    var password: String?
        get() = encryptedPrefs.getString(KEY_PASSWORD, null)
        set(value) = encryptedPrefs.edit().putString(KEY_PASSWORD, value).apply()

    var hostProfilesJson: String?
        get() = encryptedPrefs.getString(KEY_HOST_PROFILES, null)
        set(value) = encryptedPrefs.edit().putString(KEY_HOST_PROFILES, value).apply()

    var currentHostProfileId: String?
        get() = encryptedPrefs.getString(KEY_CURRENT_HOST_PROFILE_ID, null)
        set(value) = encryptedPrefs.edit().putString(KEY_CURRENT_HOST_PROFILE_ID, value).apply()

    var sshPrivateKeyPem: String?
        get() = encryptedPrefs.getString(KEY_SSH_PRIVATE_KEY, null)
        set(value) = encryptedPrefs.edit().putString(KEY_SSH_PRIVATE_KEY, value).apply()

    var sshPublicKey: String?
        get() = encryptedPrefs.getString(KEY_SSH_PUBLIC_KEY, null)
        set(value) = encryptedPrefs.edit().putString(KEY_SSH_PUBLIC_KEY, value).apply()

    var knownHostsJson: String?
        get() = encryptedPrefs.getString(KEY_KNOWN_HOSTS, null)
        set(value) = encryptedPrefs.edit().putString(KEY_KNOWN_HOSTS, value).apply()

    fun basicAuthPassword(passwordId: String): String? {
        if (passwordId == LEGACY_BASIC_AUTH_PASSWORD_ID) return password
        return encryptedPrefs.getString(basicAuthPasswordKey(passwordId), null)
    }

    fun setBasicAuthPassword(passwordId: String, value: String?) {
        encryptedPrefs.edit().apply {
            if (value.isNullOrBlank()) remove(basicAuthPasswordKey(passwordId)) else putString(basicAuthPasswordKey(passwordId), value)
        }.apply()
    }

    var currentSessionId: String?
        get() = encryptedPrefs.getString(KEY_SESSION_ID, null)
        set(value) = encryptedPrefs.edit().putString(KEY_SESSION_ID, value).apply()

    var selectedModelIndex: Int
        get() = encryptedPrefs.getInt(KEY_MODEL_INDEX, 1)
        set(value) = encryptedPrefs.edit().putInt(KEY_MODEL_INDEX, value).apply()

    fun migrateRemovedGpt56SolProModelIndices() {
        if (encryptedPrefs.getInt(KEY_MODEL_PRESET_SCHEMA_VERSION, 0) >= MODEL_PRESET_SCHEMA_VERSION) return

        val sessionModels = encryptedPrefs.getString(KEY_SESSION_MODELS, null)?.let { encoded ->
            try {
                Json.decodeFromString<Map<String, String>>(encoded).mapValues { (_, value) ->
                    value.toIntOrNull()?.let(::migrateLegacyModelIndex)?.toString() ?: value
                }
            } catch (_: Exception) {
                null
            }
        }

        encryptedPrefs.edit().apply {
            if (encryptedPrefs.contains(KEY_MODEL_INDEX)) {
                putInt(KEY_MODEL_INDEX, migrateLegacyModelIndex(encryptedPrefs.getInt(KEY_MODEL_INDEX, 1)))
            }
            if (sessionModels != null) putString(KEY_SESSION_MODELS, Json.encodeToString(sessionModels))
            putInt(KEY_MODEL_PRESET_SCHEMA_VERSION, MODEL_PRESET_SCHEMA_VERSION)
        }.apply()
    }

    var selectedAgentName: String?
        get() = encryptedPrefs.getString(KEY_AGENT_NAME, null)
        set(value) = encryptedPrefs.edit().putString(KEY_AGENT_NAME, value).apply()

    var themeMode: ThemeMode
        get() = ThemeMode.valueOf(encryptedPrefs.getString(KEY_THEME, ThemeMode.SYSTEM.name) ?: ThemeMode.SYSTEM.name)
        set(value) = encryptedPrefs.edit().putString(KEY_THEME, value.name).apply()

    var languageMode: LanguageMode
        get() = LanguageMode.valueOf(encryptedPrefs.getString(KEY_LANGUAGE, LanguageMode.SYSTEM.name) ?: LanguageMode.SYSTEM.name)
        set(value) = encryptedPrefs.edit().putString(KEY_LANGUAGE, value.name).apply()

    var aiBuilderBaseURL: String
        get() = encryptedPrefs.getString(KEY_AI_BUILDER_BASE_URL, DEFAULT_AI_BUILDER_BASE_URL) ?: DEFAULT_AI_BUILDER_BASE_URL
        set(value) = encryptedPrefs.edit().putString(KEY_AI_BUILDER_BASE_URL, value).apply()

    var aiBuilderToken: String
        get() = encryptedPrefs.getString(KEY_AI_BUILDER_TOKEN, "") ?: ""
        set(value) = encryptedPrefs.edit().putString(KEY_AI_BUILDER_TOKEN, value).apply()

    var aiBuilderCustomPrompt: String
        get() = encryptedPrefs.getString(KEY_AI_BUILDER_CUSTOM_PROMPT, DEFAULT_AI_BUILDER_CUSTOM_PROMPT) ?: DEFAULT_AI_BUILDER_CUSTOM_PROMPT
        set(value) = encryptedPrefs.edit().putString(KEY_AI_BUILDER_CUSTOM_PROMPT, value).apply()

    var aiBuilderTerminology: String
        get() = encryptedPrefs.getString(KEY_AI_BUILDER_TERMINOLOGY, DEFAULT_AI_BUILDER_TERMINOLOGY) ?: DEFAULT_AI_BUILDER_TERMINOLOGY
        set(value) = encryptedPrefs.edit().putString(KEY_AI_BUILDER_TERMINOLOGY, value).apply()

    var aiBuilderLastOKSignature: String?
        get() = encryptedPrefs.getString(KEY_AI_BUILDER_LAST_OK_SIG, null)
        set(value) = encryptedPrefs.edit().putString(KEY_AI_BUILDER_LAST_OK_SIG, value).apply()

    var aiBuilderLastOKTestedAt: Long
        get() = encryptedPrefs.getLong(KEY_AI_BUILDER_LAST_OK_TESTED, 0L)
        set(value) = encryptedPrefs.edit().putLong(KEY_AI_BUILDER_LAST_OK_TESTED, value).apply()

    var aiUsageDashboardUrl: String
        get() = encryptedPrefs.getString(KEY_AI_USAGE_DASHBOARD_URL, "") ?: ""
        set(value) = encryptedPrefs.edit().putString(KEY_AI_USAGE_DASHBOARD_URL, value).apply()

    var nfcEnabled: Boolean
        get() = encryptedPrefs.getBoolean(KEY_NFC_ENABLED, false)
        set(value) = encryptedPrefs.edit().putBoolean(KEY_NFC_ENABLED, value).apply()

    var nfcPrompt: String
        get() = encryptedPrefs.getString(KEY_NFC_PROMPT, "") ?: ""
        set(value) = encryptedPrefs.edit().putString(KEY_NFC_PROMPT, value).apply()

    var nfcAutoSend: Boolean
        get() = encryptedPrefs.getBoolean(KEY_NFC_AUTO_SEND, false)
        set(value) = encryptedPrefs.edit().putBoolean(KEY_NFC_AUTO_SEND, value).apply()

    fun getDraftText(sessionId: String): String {
        val json = encryptedPrefs.getString(KEY_SESSION_DRAFTS, null) ?: return ""
        return try {
            Json.decodeFromString<Map<String, String>>(json)[sessionId] ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    fun setDraftText(sessionId: String, text: String) {
        val json = encryptedPrefs.getString(KEY_SESSION_DRAFTS, null)
        val map: MutableMap<String, String> = try {
            json?.let { Json.decodeFromString<Map<String, String>>(it).toMutableMap() } ?: mutableMapOf()
        } catch (e: Exception) {
            mutableMapOf()
        }
        if (text.isBlank()) {
            map.remove(sessionId)
        } else {
            map[sessionId] = text
        }
        encryptedPrefs.edit().putString(KEY_SESSION_DRAFTS, Json.encodeToString(map)).apply()
    }

    fun getModelForSession(sessionId: String): Int? {
        val json = encryptedPrefs.getString(KEY_SESSION_MODELS, null) ?: return null
        return try {
            Json.decodeFromString<Map<String, String>>(json)[sessionId]?.toIntOrNull()
        } catch (e: Exception) {
            null
        }
    }

    fun setModelForSession(sessionId: String, modelIndex: Int) {
        val json = encryptedPrefs.getString(KEY_SESSION_MODELS, null)
        val map: MutableMap<String, String> = try {
            json?.let { Json.decodeFromString<Map<String, String>>(it).toMutableMap() } ?: mutableMapOf()
        } catch (e: Exception) {
            mutableMapOf()
        }
        map[sessionId] = modelIndex.toString()
        encryptedPrefs.edit().putString(KEY_SESSION_MODELS, Json.encodeToString(map)).apply()
    }

    fun getAgentForSession(sessionId: String): String? {
        val json = encryptedPrefs.getString(KEY_SESSION_AGENTS, null) ?: return null
        return try {
            Json.decodeFromString<Map<String, String>>(json)[sessionId]
        } catch (e: Exception) {
            null
        }
    }

    fun setAgentForSession(sessionId: String, agentName: String) {
        val json = encryptedPrefs.getString(KEY_SESSION_AGENTS, null)
        val map: MutableMap<String, String> = try {
            json?.let { Json.decodeFromString<Map<String, String>>(it).toMutableMap() } ?: mutableMapOf()
        } catch (e: Exception) {
            mutableMapOf()
        }
        map[sessionId] = agentName
        encryptedPrefs.edit().putString(KEY_SESSION_AGENTS, Json.encodeToString(map)).apply()
    }

    companion object {
        const val DEFAULT_SERVER = "http://localhost:4096"
        const val DEFAULT_AI_BUILDER_BASE_URL = "https://space.ai-builders.com/backend"
        const val DEFAULT_AI_BUILDER_CUSTOM_PROMPT = "All file and directory names should use snake_case (lowercase with underscores)."
        const val DEFAULT_AI_BUILDER_TERMINOLOGY = "adhoc_jobs, life_consulting, survey_sessions, thought_review"
        const val LEGACY_BASIC_AUTH_PASSWORD_ID = "legacy_basic_auth_password"
        const val NFC_PROMPT_MAX_BYTES = 480
        const val NFC_TAG_MAX_BYTES = 504
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val KEY_HOST_PROFILES = "host_profiles_json"
        private const val KEY_CURRENT_HOST_PROFILE_ID = "current_host_profile_id"
        private const val KEY_SSH_PRIVATE_KEY = "ssh_private_key_pem"
        private const val KEY_SSH_PUBLIC_KEY = "ssh_public_key"
        private const val KEY_KNOWN_HOSTS = "ssh_known_hosts_json"
        private const val KEY_SESSION_ID = "session_id"
        private const val KEY_MODEL_INDEX = "model_index"
        private const val KEY_MODEL_PRESET_SCHEMA_VERSION = "model_preset_schema_version"
        private const val KEY_AGENT_NAME = "agent_name"
        private const val KEY_THEME = "theme"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_AI_BUILDER_BASE_URL = "ai_builder_base_url"
        private const val KEY_AI_BUILDER_TOKEN = "ai_builder_token"
        private const val KEY_AI_BUILDER_CUSTOM_PROMPT = "ai_builder_custom_prompt"
        private const val KEY_AI_BUILDER_TERMINOLOGY = "ai_builder_terminology"
        private const val KEY_AI_BUILDER_LAST_OK_SIG = "ai_builder_last_ok_sig"
        private const val KEY_AI_BUILDER_LAST_OK_TESTED = "ai_builder_last_ok_tested"
        private const val KEY_AI_USAGE_DASHBOARD_URL = "ai_usage_dashboard_url"
        private const val KEY_SESSION_DRAFTS = "session_drafts"
        private const val KEY_SESSION_MODELS = "session_models"
        private const val KEY_SESSION_AGENTS = "session_agents"
        private const val KEY_NFC_ENABLED = "nfc_enabled"
        private const val KEY_NFC_PROMPT = "nfc_prompt"
        private const val KEY_NFC_AUTO_SEND = "nfc_auto_send"

        private const val MODEL_PRESET_SCHEMA_VERSION = 1

        private fun basicAuthPasswordKey(passwordId: String): String = "basic_auth_password_$passwordId"
    }
}

internal fun migrateLegacyModelIndex(index: Int): Int = when (index) {
    6 -> 1 // Removed GPT-5.6 Sol Pro now falls back to regular Sol.
    7 -> 6 // GPT-5.6 Sol Fast shifted left by one slot.
    else -> index
}

enum class ThemeMode {
    LIGHT, DARK, SYSTEM
}

enum class LanguageMode(val languageTag: String) {
    SYSTEM(""),
    ENGLISH("en"),
    CHINESE("zh")
}

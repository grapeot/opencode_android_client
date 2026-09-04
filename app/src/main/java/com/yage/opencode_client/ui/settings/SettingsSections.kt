package com.yage.opencode_client.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.yage.opencode_client.R
import com.yage.opencode_client.ui.AIBuilderSettings
import com.yage.voiceflowkit.VoiceFlowRecordingStrategy
import com.yage.opencode_client.ui.AppState
import com.yage.opencode_client.data.model.HostProfile
import com.yage.opencode_client.data.model.HostTransport
import com.yage.opencode_client.util.LanguageMode
import com.yage.opencode_client.util.ThemeMode

@Composable
internal fun ConnectionProfileSection(
    profile: HostProfile,
    isTesting: Boolean,
    state: AppState,
    testResult: TestResult?,
    onTestConnection: () -> Unit,
    onManageProfiles: () -> Unit
) {
    SectionHeader(title = stringResource(R.string.settings_connection_profile))

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(profile.displayName, style = MaterialTheme.typography.titleMedium)
                    Text(profile.connectionSummary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    if (profile.transport == HostTransport.SSH_TUNNEL) stringResource(R.string.host_profile_ssh_tunnel) else stringResource(R.string.host_profile_direct),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onTestConnection, enabled = !isTesting) {
                    if (isTesting) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(stringResource(R.string.settings_test_connection))
                }
                OutlinedButton(onClick = onManageProfiles) {
                    Text(stringResource(R.string.settings_manage_profiles))
                }
            }
        }
    }

    testResult?.let { ResultCard(result = it) }

    if (state.isConnected) {
        Spacer(modifier = Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.settings_connected), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            state.serverVersion?.let { version ->
                Text(" (v$version)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

@Composable
internal fun ServerConnectionSection(
    serverUrl: String,
    username: String,
    password: String,
    showPassword: Boolean,
    isTesting: Boolean,
    state: AppState,
    testResult: TestResult?,
    onServerUrlChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onTogglePasswordVisibility: () -> Unit,
    onTestConnection: () -> Unit,
    onSave: () -> Unit
) {
    SectionHeader(title = stringResource(R.string.settings_server_connection))

    OutlinedTextField(
        value = serverUrl,
        onValueChange = onServerUrlChange,
        label = { Text(stringResource(R.string.settings_server_url)) },
        placeholder = { Text("http://localhost:4096") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        leadingIcon = { Icon(Icons.Default.Cloud, contentDescription = null) }
    )

    Spacer(modifier = Modifier.height(12.dp))

    OutlinedTextField(
        value = username,
        onValueChange = onUsernameChange,
        label = { Text(stringResource(R.string.settings_username_optional)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) }
    )

    Spacer(modifier = Modifier.height(12.dp))

    OutlinedTextField(
        value = password,
        onValueChange = onPasswordChange,
        label = { Text(stringResource(R.string.settings_password_optional)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = onTogglePasswordVisibility) {
                Icon(
                    if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = if (showPassword) stringResource(R.string.settings_hide_password) else stringResource(R.string.settings_show_password)
                )
            }
        },
        leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) }
    )

    Spacer(modifier = Modifier.height(16.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Button(
            onClick = onTestConnection,
            enabled = serverUrl.isNotBlank() && !isTesting
        ) {
            if (isTesting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(stringResource(R.string.settings_test_connection))
        }

        OutlinedButton(
            onClick = onSave,
            enabled = serverUrl.isNotBlank()
        ) {
            Text(stringResource(R.string.settings_save))
        }
    }

    testResult?.let { ResultCard(result = it) }

    if (state.isConnected) {
        Spacer(modifier = Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                stringResource(R.string.settings_connected),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
            state.serverVersion?.let { version ->
                Text(
                    " (v$version)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppearanceSection(
    themeMode: ThemeMode,
    languageMode: LanguageMode,
    onThemeSelected: (ThemeMode) -> Unit,
    onLanguageSelected: (LanguageMode) -> Unit
) {
    SectionHeader(title = stringResource(R.string.settings_appearance))

    val modes = ThemeMode.values()
    Text(stringResource(R.string.settings_theme), style = MaterialTheme.typography.labelMedium)
    Spacer(modifier = Modifier.height(8.dp))
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier.fillMaxWidth()
    ) {
        modes.forEachIndexed { index, mode ->
            SegmentedButton(
                selected = themeMode == mode,
                onClick = { onThemeSelected(mode) },
                shape = SegmentedButtonDefaults.itemShape(
                    index = index,
                    count = modes.size
                ),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = MaterialTheme.colorScheme.primary,
                    activeContentColor = MaterialTheme.colorScheme.onPrimary,
                    activeBorderColor = MaterialTheme.colorScheme.primary,
                    inactiveContainerColor = MaterialTheme.colorScheme.surface,
                    inactiveContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    inactiveBorderColor = MaterialTheme.colorScheme.outline
                )
            ) {
                Text(
                    when (mode) {
                        ThemeMode.LIGHT -> stringResource(R.string.settings_theme_light)
                        ThemeMode.DARK -> stringResource(R.string.settings_theme_dark)
                        ThemeMode.SYSTEM -> stringResource(R.string.settings_follow_system)
                    }
                )
            }
        }
    }

    Spacer(modifier = Modifier.height(16.dp))
    Text(stringResource(R.string.settings_language), style = MaterialTheme.typography.labelMedium)
    Spacer(modifier = Modifier.height(8.dp))
    val languages = LanguageMode.values()
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        languages.forEachIndexed { index, mode ->
            SegmentedButton(
                selected = languageMode == mode,
                onClick = { onLanguageSelected(mode) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = languages.size),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = MaterialTheme.colorScheme.primary,
                    activeContentColor = MaterialTheme.colorScheme.onPrimary,
                    activeBorderColor = MaterialTheme.colorScheme.primary,
                    inactiveContainerColor = MaterialTheme.colorScheme.surface,
                    inactiveContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    inactiveBorderColor = MaterialTheme.colorScheme.outline
                )
            ) {
                Text(
                    when (mode) {
                        LanguageMode.SYSTEM -> stringResource(R.string.settings_follow_system)
                        LanguageMode.ENGLISH -> stringResource(R.string.settings_language_english)
                        LanguageMode.CHINESE -> stringResource(R.string.settings_language_chinese)
                    }
                )
            }
        }
    }
}

@Composable
internal fun AIUsageDashboardSection(
    state: AppState,
    dashboardUrl: String,
    saveMessage: String? = null,
    onUrlChange: (String) -> Unit,
    onTestConnection: () -> Unit,
    onSave: () -> Unit
) {
    SectionHeader(title = stringResource(R.string.settings_ai_usage_dashboard))
    Text(
        stringResource(R.string.settings_ai_usage_description),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(modifier = Modifier.height(12.dp))
    OutlinedTextField(
        value = dashboardUrl,
        onValueChange = onUrlChange,
        label = { Text(stringResource(R.string.settings_ai_usage_url)) },
        modifier = Modifier.fillMaxWidth().testTag("settings.ai_usage.url"),
        singleLine = true,
        leadingIcon = { Icon(Icons.Default.Cloud, contentDescription = null) }
    )
    Spacer(modifier = Modifier.height(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = onTestConnection,
            enabled = dashboardUrl.isNotBlank() && !state.isLoadingAIUsage
        ) {
            if (state.isLoadingAIUsage) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(stringResource(R.string.settings_test_connection))
        }
        OutlinedButton(onClick = onSave) {
            Text(stringResource(R.string.settings_save))
        }
    }
    when {
        saveMessage != null -> ResultCard(TestResult(success = true, message = saveMessage))
        state.aiUsageError != null -> ResultCard(TestResult(success = false, message = state.aiUsageError))
        state.aiUsageQuotaSnapshot != null -> ResultCard(
            TestResult(success = true, message = stringResource(R.string.settings_connected_successfully))
        )
    }
}

@Composable
internal fun SpeechRecognitionSection(
    state: AppState,
    aiBuilderBaseURL: String,
    aiBuilderToken: String,
    aiBuilderCustomPrompt: String,
    aiBuilderTerminology: String,
    aiBuilderRecordingStrategy: String,
    showAIBuilderToken: Boolean,
    saveMessage: String? = null,
    onBaseUrlChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
    onPromptChange: (String) -> Unit,
    onTerminologyChange: (String) -> Unit,
    onRecordingStrategyChange: (String) -> Unit,
    onToggleTokenVisibility: () -> Unit,
    onSave: () -> Unit
) {
    SectionHeader(title = stringResource(R.string.settings_speech_recognition))

    OutlinedTextField(
        value = aiBuilderBaseURL,
        onValueChange = onBaseUrlChange,
        label = { Text(stringResource(R.string.settings_ai_builder_base_url)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        leadingIcon = { Icon(Icons.Default.Cloud, contentDescription = null) }
    )

    Spacer(modifier = Modifier.height(12.dp))

    OutlinedTextField(
        value = aiBuilderToken,
        onValueChange = onTokenChange,
        label = { Text(stringResource(R.string.settings_ai_builder_token)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        visualTransformation = if (showAIBuilderToken) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = onToggleTokenVisibility) {
                Icon(
                    if (showAIBuilderToken) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = if (showAIBuilderToken) stringResource(R.string.settings_hide_token) else stringResource(R.string.settings_show_token)
                )
            }
        },
        leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) }
    )

    Spacer(modifier = Modifier.height(12.dp))

    Text(
        text = stringResource(R.string.settings_recording_strategy),
        style = MaterialTheme.typography.labelLarge,
    )
    Spacer(modifier = Modifier.height(8.dp))
    val strategies = listOf(
        VoiceFlowRecordingStrategy.OPENAI_REALTIME to R.string.settings_recording_strategy_openai,
        VoiceFlowRecordingStrategy.GPT_LIVE_TRANSCRIBE to R.string.settings_recording_strategy_gpt_live,
        VoiceFlowRecordingStrategy.GROK_BATCH to R.string.settings_recording_strategy_grok,
    )
    val selected = VoiceFlowRecordingStrategy.fromRaw(aiBuilderRecordingStrategy)
    var showStrategyHelp by remember { mutableStateOf(false) }
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier.fillMaxWidth(),
    ) {
        strategies.forEachIndexed { index, (strategy, labelRes) ->
            SegmentedButton(
                selected = selected == strategy,
                onClick = { onRecordingStrategyChange(strategy.name) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = strategies.size),
            ) {
                Text(stringResource(labelRes))
            }
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.Info,
            contentDescription = stringResource(R.string.settings_recording_strategy_dialog_title),
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .size(18.dp)
                .clickable { showStrategyHelp = true },
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = stringResource(R.string.settings_recording_strategy_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable { showStrategyHelp = true },
        )
    }
    if (showStrategyHelp) {
        AlertDialog(
            onDismissRequest = { showStrategyHelp = false },
            title = { Text(stringResource(R.string.settings_recording_strategy_dialog_title)) },
            text = {
                Text(
                    text = stringResource(R.string.settings_recording_strategy_dialog_body),
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = {
                TextButton(onClick = { showStrategyHelp = false }) {
                    Text(stringResource(R.string.ok))
                }
            },
        )
    }
    if (selected.usesRealtimeTransport) {
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = aiBuilderCustomPrompt,
            onValueChange = onPromptChange,
            label = { Text(stringResource(R.string.settings_custom_prompt)) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            maxLines = 6
        )
    }

    Spacer(modifier = Modifier.height(12.dp))

    OutlinedTextField(
        value = aiBuilderTerminology,
        onValueChange = onTerminologyChange,
        label = { Text(stringResource(R.string.settings_terminology)) },
        placeholder = { Text(stringResource(R.string.settings_terminology_placeholder)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true
    )

    Spacer(modifier = Modifier.height(16.dp))

    // Save auto-tests the connection; no separate Test button. The result card
    // below shows success/failure from the live probe, so users never have to
    // remember to "test after save" — and never lose a prior "connected" state
    // by merely saving unchanged credentials.
    Button(
        onClick = onSave,
        enabled = aiBuilderBaseURL.isNotBlank() && !state.isTestingAIBuilderConnection,
        modifier = Modifier.fillMaxWidth()
    ) {
        if (state.isTestingAIBuilderConnection) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.settings_save_testing))
        } else {
            Text(stringResource(R.string.settings_save))
        }
    }

    // A "Settings saved" notice takes precedence (it's the most recent action and
    // auto-dismisses); otherwise show the latest connection test result.
    if (saveMessage != null) {
        ResultCard(result = TestResult(success = true, message = saveMessage))
    } else if (state.aiBuilderConnectionOK || state.aiBuilderConnectionError != null) {
        ResultCard(
            result = TestResult(
                success = state.aiBuilderConnectionOK,
                message = if (state.aiBuilderConnectionOK) {
                    stringResource(R.string.settings_connected_successfully)
                } else {
                    state.aiBuilderConnectionError ?: stringResource(R.string.settings_connection_failed)
                }
            )
        )
    }
}

@Composable
internal fun NfcExperimentalSection(
    enabled: Boolean,
    prompt: String,
    autoSend: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onPromptChange: (String) -> Unit,
    onAutoSendChange: (Boolean) -> Unit,
    onWriteToTag: () -> Unit
) {
    SectionHeader(title = stringResource(R.string.nfc_section_title))

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Nfc, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.nfc_quick_prompt), style = MaterialTheme.typography.bodyLarge)
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = onEnabledChange,
                    modifier = Modifier.testTag("nfc.enabled_switch")
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = prompt,
                onValueChange = onPromptChange,
                label = { Text(stringResource(R.string.nfc_prompt_label)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("nfc.prompt_input"),
                minLines = 3,
                maxLines = 8,
                enabled = enabled
            )

            Spacer(modifier = Modifier.height(4.dp))

            val promptBytes = prompt.toByteArray(Charsets.UTF_8).size
            Text(
                text = "$promptBytes / ${com.yage.opencode_client.util.SettingsManager.NFC_PROMPT_MAX_BYTES} bytes",
                style = MaterialTheme.typography.labelSmall,
                color = if (promptBytes > com.yage.opencode_client.util.SettingsManager.NFC_PROMPT_MAX_BYTES) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.testTag("nfc.byte_counter")
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.nfc_auto_send), style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = autoSend,
                    onCheckedChange = onAutoSendChange,
                    modifier = Modifier.testTag("nfc.auto_send_switch"),
                    enabled = enabled
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = onWriteToTag,
                enabled = enabled && prompt.isNotBlank() && promptBytes <= com.yage.opencode_client.util.SettingsManager.NFC_PROMPT_MAX_BYTES,
                modifier = Modifier.testTag("nfc.write_button")
            ) {
                Text(stringResource(R.string.nfc_write_to_tag))
            }
        }
    }
}

@Composable
internal fun AboutSection() {
    SectionHeader(title = stringResource(R.string.settings_about))

    Text(
        "OpenCode Android Client",
        style = MaterialTheme.typography.bodyLarge
    )
    Text(
        "Version 1.0",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline
    )

    Spacer(modifier = Modifier.height(8.dp))

    Text(
        stringResource(R.string.settings_about_description),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline
    )
}

@Composable
internal fun ModelShortlistEntry(
    modelCount: Int,
    currentModelName: String?,
    onManage: () -> Unit
) {
    SectionHeader(title = stringResource(R.string.settings_model_shortlist))
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onManage)
            .testTag("settings.model_shortlist.entry"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(pluralStringResource(R.plurals.model_shortlist_count, modelCount, modelCount), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.settings_model_shortlist_entry),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    currentModelName?.let {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            stringResource(R.string.model_shortlist_current, it),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
internal fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium
    )
    Spacer(modifier = Modifier.height(16.dp))
}

@Composable
private fun ResultCard(result: TestResult) {
    Spacer(modifier = Modifier.height(12.dp))
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (result.success) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.errorContainer
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (result.success) Icons.Default.Check else Icons.Default.Error,
                contentDescription = null,
                tint = if (result.success) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onErrorContainer
                }
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                result.message,
                color = if (result.success) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onErrorContainer
                }
            )
        }
    }
}

@Composable
internal fun SettingsSectionDivider() {
    Spacer(modifier = Modifier.height(32.dp))
    HorizontalDivider()
    Spacer(modifier = Modifier.height(32.dp))
}

internal data class TestResult(
    val success: Boolean,
    val message: String
)

internal fun buildAIBuilderSettings(
    baseURL: String,
    token: String,
    customPrompt: String,
    terminology: String,
    recordingStrategy: String = VoiceFlowRecordingStrategy.GPT_LIVE_TRANSCRIBE.name,
): AIBuilderSettings {
    return AIBuilderSettings(
        baseURL = baseURL,
        token = token,
        customPrompt = customPrompt,
        terminology = terminology,
        recordingStrategy = recordingStrategy,
    )
}

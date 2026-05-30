package com.yage.opencode_client.ui.settings

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
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.yage.opencode_client.ui.AIBuilderSettings
import com.yage.opencode_client.ui.AppState
import com.yage.opencode_client.util.ThemeMode

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
    SectionHeader(title = "Server Connection")

    OutlinedTextField(
        value = serverUrl,
        onValueChange = onServerUrlChange,
        label = { Text("Server URL") },
        placeholder = { Text("http://localhost:4096") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        leadingIcon = { Icon(Icons.Default.Cloud, contentDescription = null) }
    )

    Spacer(modifier = Modifier.height(12.dp))

    OutlinedTextField(
        value = username,
        onValueChange = onUsernameChange,
        label = { Text("Username (optional)") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) }
    )

    Spacer(modifier = Modifier.height(12.dp))

    OutlinedTextField(
        value = password,
        onValueChange = onPasswordChange,
        label = { Text("Password (optional)") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = onTogglePasswordVisibility) {
                Icon(
                    if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = if (showPassword) "Hide password" else "Show password"
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
            Text("Test Connection")
        }

        OutlinedButton(
            onClick = onSave,
            enabled = serverUrl.isNotBlank()
        ) {
            Text("Save")
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
                "Connected",
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
    onThemeSelected: (ThemeMode) -> Unit
) {
    SectionHeader(title = "Appearance")

    val modes = ThemeMode.values()
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
                        ThemeMode.LIGHT -> "Light"
                        ThemeMode.DARK -> "Dark"
                        ThemeMode.SYSTEM -> "System"
                    }
                )
            }
        }
    }
}

@Composable
internal fun SpeechRecognitionSection(
    state: AppState,
    aiBuilderBaseURL: String,
    aiBuilderToken: String,
    aiBuilderCustomPrompt: String,
    aiBuilderTerminology: String,
    showAIBuilderToken: Boolean,
    saveMessage: String? = null,
    onBaseUrlChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
    onPromptChange: (String) -> Unit,
    onTerminologyChange: (String) -> Unit,
    onToggleTokenVisibility: () -> Unit,
    onTestConnection: () -> Unit,
    onSave: () -> Unit
) {
    SectionHeader(title = "Speech Recognition")

    OutlinedTextField(
        value = aiBuilderBaseURL,
        onValueChange = onBaseUrlChange,
        label = { Text("AI Builder Base URL") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        leadingIcon = { Icon(Icons.Default.Cloud, contentDescription = null) }
    )

    Spacer(modifier = Modifier.height(12.dp))

    OutlinedTextField(
        value = aiBuilderToken,
        onValueChange = onTokenChange,
        label = { Text("AI Builder Token") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        visualTransformation = if (showAIBuilderToken) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = onToggleTokenVisibility) {
                Icon(
                    if (showAIBuilderToken) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = if (showAIBuilderToken) "Hide token" else "Show token"
                )
            }
        },
        leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) }
    )

    Spacer(modifier = Modifier.height(12.dp))

    OutlinedTextField(
        value = aiBuilderCustomPrompt,
        onValueChange = onPromptChange,
        label = { Text("Custom Prompt") },
        modifier = Modifier.fillMaxWidth(),
        minLines = 3,
        maxLines = 6
    )

    Spacer(modifier = Modifier.height(12.dp))

    OutlinedTextField(
        value = aiBuilderTerminology,
        onValueChange = onTerminologyChange,
        label = { Text("Terminology") },
        placeholder = { Text("comma-separated terms") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true
    )

    Spacer(modifier = Modifier.height(16.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Button(
            onClick = onTestConnection,
            enabled = aiBuilderBaseURL.isNotBlank() && !state.isTestingAIBuilderConnection
        ) {
            if (state.isTestingAIBuilderConnection) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text("Test Connection")
        }

        OutlinedButton(
            onClick = onSave,
            enabled = aiBuilderBaseURL.isNotBlank()
        ) {
            Text("Save")
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
                    "Connected successfully"
                } else {
                    state.aiBuilderConnectionError ?: "Connection failed"
                }
            )
        )
    }
}

@Composable
internal fun AboutSection() {
    SectionHeader(title = "About")

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
        "A native Android client for OpenCode AI coding agent.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline
    )
}

@Composable
private fun SectionHeader(title: String) {
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
    terminology: String
): AIBuilderSettings {
    return AIBuilderSettings(
        baseURL = baseURL,
        token = token,
        customPrompt = customPrompt,
        terminology = terminology
    )
}

package com.yage.opencode_client

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.yage.opencode_client.data.model.HostProfile
import com.yage.opencode_client.data.model.HostTransport
import com.yage.opencode_client.data.model.SshTunnelConfig
import com.yage.opencode_client.ui.AppState
import com.yage.opencode_client.ui.settings.ConnectionProfileSection
import com.yage.opencode_client.ui.settings.DevicePublicKeySection
import com.yage.opencode_client.ui.settings.HostProfileDetailDialog
import com.yage.opencode_client.ui.settings.HostProfileEditorDialog
import com.yage.opencode_client.ui.settings.SpeechRecognitionSection
import org.junit.Rule
import org.junit.Test

class SettingsSectionsInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun speechSectionDisablesActionsWhenBaseUrlIsBlank() {
        composeRule.setContent {
            MaterialTheme {
                SpeechRecognitionSection(
                    state = AppState(),
                    aiBuilderBaseURL = "",
                    aiBuilderToken = "",
                    aiBuilderCustomPrompt = "",
                    aiBuilderTerminology = "",
                    aiBuilderRecordingStrategy = "OPENAI_REALTIME",
                    showAIBuilderToken = false,
                    onBaseUrlChange = {},
                    onTokenChange = {},
                    onPromptChange = {},
                    onTerminologyChange = {},
                    onRecordingStrategyChange = {},
                    onToggleTokenVisibility = {},
                    onSave = {}
                )
            }
        }

        composeRule.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test
    fun speechSectionShowsSuccessResultAndEnablesActionsWhenConfigured() {
        composeRule.setContent {
            MaterialTheme {
                SpeechRecognitionSection(
                    state = AppState(aiBuilderConnectionOK = true),
                    aiBuilderBaseURL = "https://builder.example.com",
                    aiBuilderToken = "token",
                    aiBuilderCustomPrompt = "",
                    aiBuilderTerminology = "",
                    aiBuilderRecordingStrategy = "OPENAI_REALTIME",
                    showAIBuilderToken = false,
                    onBaseUrlChange = {},
                    onTokenChange = {},
                    onPromptChange = {},
                    onTerminologyChange = {},
                    onRecordingStrategyChange = {},
                    onToggleTokenVisibility = {},
                    onSave = {}
                )
            }
        }

        composeRule.onNodeWithText("Save").assertIsEnabled()
        composeRule.onNodeWithText("Connected successfully").assertIsDisplayed()
    }

    @Test
    fun connectionProfileSectionShowsCurrentSshProfileSummary() {
        val profile = HostProfile(
            id = "ssh-1",
            name = "VPS OpenCode",
            transport = HostTransport.SSH_TUNNEL,
            serverUrl = "http://127.0.0.1:4096",
            ssh = SshTunnelConfig(host = "gateway.example.com", port = 8006, username = "opencode", remotePort = 19001)
        )

        composeRule.setContent {
            MaterialTheme {
                ConnectionProfileSection(
                    profile = profile,
                    isTesting = false,
                    state = AppState(isConnected = true, serverVersion = "1.0.0"),
                    testResult = null,
                    onTestConnection = {},
                    onManageProfiles = {}
                )
            }
        }

        composeRule.onNodeWithText("VPS OpenCode").assertIsDisplayed()
        composeRule.onNodeWithText("gateway.example.com:8006 -> :19001").assertIsDisplayed()
        composeRule.onNodeWithText("SSH Tunnel").assertIsDisplayed()
        composeRule.onNodeWithText("Manage Profiles").assertIsDisplayed()
    }

    @Test
    fun hostProfileEditorShowsSshFieldsWithoutDeviceKeyBlock() {
        val profile = HostProfile(
            id = "ssh-1",
            name = "VPS OpenCode",
            transport = HostTransport.SSH_TUNNEL,
            serverUrl = "http://127.0.0.1:4096",
            ssh = SshTunnelConfig(host = "gateway.example.com", port = 8006, username = "opencode", remotePort = 19001)
        )

        composeRule.setContent {
            MaterialTheme {
                HostProfileEditorDialog(
                    initial = profile,
                    onDismiss = {},
                    onSave = { _, _ -> },
                )
            }
        }

        composeRule.onNodeWithText("SSH gateway host").assertIsDisplayed()
        composeRule.onNodeWithText("Assigned remote port").assertIsDisplayed()
        composeRule.onAllNodesWithText("Device public key").assertCountEquals(0)
    }

    @Test
    fun hostProfileEditorSwitchesBetweenDirectAndSshModes() {
        val profile = HostProfile.defaultDirect("http://localhost:4096")

        composeRule.setContent {
            MaterialTheme {
                HostProfileEditorDialog(
                    initial = profile,
                    onDismiss = {},
                    onSave = { _, _ -> },
                )
            }
        }

        composeRule.onNodeWithText("Server URL").assertIsDisplayed()
        composeRule.onNodeWithTag("host.editor.transport.ssh").performClick()
        composeRule.onNodeWithText("SSH gateway host").assertIsDisplayed()
        composeRule.onNodeWithText("Copy Device Public Key").assertIsDisplayed()
    }

    @Test
    fun devicePublicKeySectionIsGlobalToHostProfiles() {
        composeRule.setContent {
            MaterialTheme {
                DevicePublicKeySection(copied = false, onCopy = {}, onRotate = {})
            }
        }

        composeRule.onNodeWithText("Device Key").assertIsDisplayed()
        composeRule.onNodeWithText("This Android device uses one SSH public key for all SSH Tunnel profiles.").assertIsDisplayed()
        composeRule.onNodeWithTag("ssh.publicKey.copy").assertIsDisplayed()
        composeRule.onNodeWithTag("ssh.publicKey.rotate").assertIsDisplayed()
    }

    @Test
    fun hostProfileDetailShowsUseAndCopyActionsForSshProfile() {
        val profile = HostProfile(
            id = "ssh-1",
            name = "VPS OpenCode",
            transport = HostTransport.SSH_TUNNEL,
            serverUrl = "http://127.0.0.1:4096",
            ssh = SshTunnelConfig(host = "gateway.example.com", port = 8006, username = "opencode", remotePort = 19001)
        )

        composeRule.setContent {
            MaterialTheme {
                HostProfileDetailDialog(
                    profile = profile,
                    isCurrent = false,
                    onDismiss = {},
                    onUse = {},
                    onEdit = {},
                    onExport = {},
                    onCopyPublicKey = {},
                    onTest = {}
                )
            }
        }

        composeRule.onNodeWithText("Use This Host").assertIsDisplayed()
        composeRule.onNodeWithText("Copy Config JSON").assertIsDisplayed()
        composeRule.onNodeWithText("Copy Device Public Key").assertIsDisplayed()
        composeRule.onNodeWithText("Host: gateway.example.com").assertIsDisplayed()
    }
}

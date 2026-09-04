package com.yage.opencode_client.ui.settings

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.activity.compose.BackHandler
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yage.opencode_client.R
import com.yage.opencode_client.NfcWriterActivity
import com.yage.opencode_client.data.model.BasicAuthConfig
import com.yage.opencode_client.data.model.HostProfile
import com.yage.opencode_client.data.model.HostTransport
import com.yage.opencode_client.data.model.SshTunnelConfig
import com.yage.opencode_client.ui.MainViewModel
import com.yage.opencode_client.ui.AIUsageSettings
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    onBack: (() -> Unit)? = null
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val savedAIBuilder = remember(viewModel) { viewModel.getAIBuilderSettings() }
    val savedAIUsage = remember(viewModel) { viewModel.getAIUsageSettings() }
    val context = LocalContext.current

    var showHostProfiles by remember { mutableStateOf(false) }
    var showModelShortlist by rememberSaveable { mutableStateOf(false) }
    var isTesting by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<TestResult?>(null) }
    var aiBuilderBaseURL by remember { mutableStateOf(savedAIBuilder.baseURL) }
    var aiBuilderToken by remember { mutableStateOf(savedAIBuilder.token) }
    var aiBuilderCustomPrompt by remember { mutableStateOf(savedAIBuilder.customPrompt) }
    var aiBuilderTerminology by remember { mutableStateOf(savedAIBuilder.terminology) }
    var aiBuilderRecordingStrategy by remember { mutableStateOf(savedAIBuilder.recordingStrategy) }
    var showAIBuilderToken by remember { mutableStateOf(false) }
    // Independent "Settings saved" notice for the Speech section, so it shows in
    // its own section rather than reusing the server section's testResult.
    var aiBuilderSaveMessage by remember { mutableStateOf<String?>(null) }
    var aiUsageDashboardUrl by remember { mutableStateOf(savedAIUsage.dashboardUrl) }
    var aiUsageSaveMessage by remember { mutableStateOf<String?>(null) }
    var nfcEnabled by remember { mutableStateOf(viewModel.getNfcEnabled()) }
    var nfcPrompt by remember { mutableStateOf(viewModel.getNfcPrompt()) }
    var nfcAutoSend by remember { mutableStateOf(viewModel.getNfcAutoSend()) }

    LaunchedEffect(state.isConnecting) {
        if (!state.isConnecting && isTesting) {
            isTesting = false
            testResult = TestResult(
                success = state.isConnected,
                message = if (state.isConnected) {
                    "Connected successfully" + (state.serverVersion?.let { " (v$it)" } ?: "")
                } else {
                    state.error ?: "Connection failed"
                }
            )
        }
    }

    // Auto-dismiss the transient "Settings saved" notices after a short delay,
    // for both the server and speech sections. Connection results (success/error)
    // stay until the user changes a field.
    LaunchedEffect(testResult) {
        if (testResult?.message == "Settings saved") {
            delay(2000)
            testResult = null
        }
    }
    LaunchedEffect(aiBuilderSaveMessage) {
        if (aiBuilderSaveMessage != null) {
            delay(2000)
            aiBuilderSaveMessage = null
        }
    }
    LaunchedEffect(aiUsageSaveMessage) {
        if (aiUsageSaveMessage != null) {
            delay(2000)
            aiUsageSaveMessage = null
        }
    }

    LaunchedEffect(state.pendingModelShortlistFocus) {
        if (state.pendingModelShortlistFocus) {
            showModelShortlist = true
            viewModel.clearModelShortlistFocus()
        }
    }

    if (showHostProfiles) {
        HostProfilesManagerScreen(
            viewModel = viewModel,
            profiles = state.hostProfiles,
            currentProfileId = state.currentHostProfileId,
            onBack = { showHostProfiles = false }
        )
        return
    }

    if (showModelShortlist) {
        BackHandler(enabled = true) { showModelShortlist = false }
        ModelShortlistScreen(
            viewModel = viewModel,
            onBack = { showModelShortlist = false }
        )
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (onBack != null) {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                }
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            ConnectionProfileSection(
                profile = state.hostProfiles.firstOrNull { it.id == state.currentHostProfileId } ?: viewModel.currentHostProfile(),
                isTesting = isTesting,
                state = state,
                testResult = testResult,
                onTestConnection = {
                    isTesting = true
                    testResult = null
                    viewModel.testConnection(force = true)
                },
                onManageProfiles = { showHostProfiles = true }
            )

            SettingsSectionDivider()

            AppearanceSection(
                themeMode = state.themeMode,
                languageMode = state.languageMode,
                onThemeSelected = viewModel::setThemeMode,
                onLanguageSelected = viewModel::setLanguageMode
            )

            SettingsSectionDivider()

            ModelShortlistEntry(
                modelCount = state.modelShortlist.size,
                currentModelName = state.availableModels.getOrNull(state.selectedModelIndex)?.displayName,
                onManage = { showModelShortlist = true }
            )

            SettingsSectionDivider()

            AIUsageDashboardSection(
                state = state,
                dashboardUrl = aiUsageDashboardUrl,
                saveMessage = aiUsageSaveMessage,
                onUrlChange = {
                    aiUsageDashboardUrl = it
                    aiUsageSaveMessage = null
                },
                onTestConnection = {
                    viewModel.saveAIUsageSettings(AIUsageSettings(aiUsageDashboardUrl))
                    viewModel.loadAIUsage()
                },
                onSave = {
                    viewModel.saveAIUsageSettings(AIUsageSettings(aiUsageDashboardUrl))
                    aiUsageSaveMessage = "Settings saved"
                }
            )

            SettingsSectionDivider()

            SpeechRecognitionSection(
                state = state,
                aiBuilderBaseURL = aiBuilderBaseURL,
                aiBuilderToken = aiBuilderToken,
                aiBuilderCustomPrompt = aiBuilderCustomPrompt,
                aiBuilderTerminology = aiBuilderTerminology,
                aiBuilderRecordingStrategy = aiBuilderRecordingStrategy,
                showAIBuilderToken = showAIBuilderToken,
                saveMessage = aiBuilderSaveMessage,
                onBaseUrlChange = {
                    aiBuilderBaseURL = it
                    aiBuilderSaveMessage = null
                },
                onTokenChange = {
                    aiBuilderToken = it
                    aiBuilderSaveMessage = null
                },
                onPromptChange = {
                    aiBuilderCustomPrompt = it
                    aiBuilderSaveMessage = null
                },
                onTerminologyChange = {
                    aiBuilderTerminology = it
                    aiBuilderSaveMessage = null
                },
                onRecordingStrategyChange = {
                    aiBuilderRecordingStrategy = it
                    aiBuilderSaveMessage = null
                },
                onToggleTokenVisibility = { showAIBuilderToken = !showAIBuilderToken },
                onSave = {
                    aiBuilderSaveMessage = null
                    viewModel.saveAIBuilderSettings(
                        buildAIBuilderSettings(
                            baseURL = aiBuilderBaseURL,
                            token = aiBuilderToken,
                            customPrompt = aiBuilderCustomPrompt,
                            terminology = aiBuilderTerminology,
                            recordingStrategy = aiBuilderRecordingStrategy,
                        )
                    )
                    // Save auto-tests; the result card shows success/failure.
                    viewModel.testAIBuilderConnection()
                    aiBuilderSaveMessage = "Settings saved"
                }
            )

            SettingsSectionDivider()

            NfcExperimentalSection(
                enabled = nfcEnabled,
                prompt = nfcPrompt,
                autoSend = nfcAutoSend,
                onEnabledChange = {
                    nfcEnabled = it
                    viewModel.saveNfcEnabled(it)
                },
                onPromptChange = {
                    nfcPrompt = it
                    viewModel.saveNfcPrompt(it)
                },
                onAutoSendChange = {
                    nfcAutoSend = it
                    viewModel.saveNfcAutoSend(it)
                },
                onWriteToTag = {
                    viewModel.saveNfcPrompt(nfcPrompt)
                    viewModel.saveNfcAutoSend(nfcAutoSend)
                    context.startActivity(Intent(context, NfcWriterActivity::class.java))
                }
            )

            SettingsSectionDivider()

            AboutSection()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HostProfilesManagerScreen(
    viewModel: MainViewModel,
    profiles: List<HostProfile>,
    currentProfileId: String?,
    onBack: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    var editingProfile by remember { mutableStateOf<HostProfile?>(null) }
    var importing by remember { mutableStateOf(false) }
    var importText by remember { mutableStateOf("") }
    var exportText by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var publicKeyCopied by remember { mutableStateOf(false) }
    var detailProfile by remember { mutableStateOf<HostProfile?>(null) }
    var confirmKeyRotation by remember { mutableStateOf(false) }

    LaunchedEffect(publicKeyCopied) {
        if (publicKeyCopied) {
            delay(2000)
            publicKeyCopied = false
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.host_profiles_title)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                }
            },
            actions = {
                IconButton(onClick = { importing = true }) {
                    Icon(Icons.Default.FileDownload, contentDescription = stringResource(R.string.host_profile_import_json))
                }
                IconButton(onClick = { editingProfile = newDirectProfile() }) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.host_profile_add))
                }
            }
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .testTag("host.profile.list")
        ) {
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
                Spacer(modifier = Modifier.height(12.dp))
            }
            profiles.forEach { profile ->
                HostProfileRow(
                    profile = profile,
                    selected = profile.id == currentProfileId,
                    canDelete = profiles.size > 1,
                    onOpen = { detailProfile = profile },
                    onEdit = { editingProfile = profile },
                    onDuplicate = { viewModel.duplicateHostProfile(profile.id) },
                    onExport = { exportText = viewModel.exportHostProfile(profile) },
                    onDelete = {
                        runCatching { viewModel.deleteHostProfile(profile.id) }
                            .onFailure { error = it.message }
                    }
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            SettingsSectionDivider()

            DevicePublicKeySection(
                copied = publicKeyCopied,
                onCopy = {
                    val key = viewModel.ensureSshPublicKey()
                    clipboard.setText(AnnotatedString(key))
                    publicKeyCopied = true
                },
                onRotate = { confirmKeyRotation = true }
            )
        }
    }

    editingProfile?.let { profile ->
        HostProfileEditorDialog(
            initial = profile,
            onDismiss = { editingProfile = null },
            onSave = { saved, password ->
                viewModel.saveHostProfile(saved, password)
                editingProfile = null
            },
            onCopyPublicKey = {
                val key = viewModel.ensureSshPublicKey()
                clipboard.setText(AnnotatedString(key))
                publicKeyCopied = true
            }
        )
    }

    detailProfile?.let { profile ->
        HostProfileDetailDialog(
            profile = profile,
            isCurrent = profile.id == currentProfileId,
            onDismiss = { detailProfile = null },
            onUse = {
                viewModel.selectHostProfile(profile.id)
                detailProfile = null
            },
            onEdit = {
                editingProfile = profile
                detailProfile = null
            },
            onExport = { exportText = viewModel.exportHostProfile(profile) },
            onCopyPublicKey = {
                val key = viewModel.ensureSshPublicKey()
                clipboard.setText(AnnotatedString(key))
                publicKeyCopied = true
            },
            onTest = {
                viewModel.selectHostProfile(profile.id)
                detailProfile = null
            }
        )
    }

    if (importing) {
        AlertDialog(
            onDismissRequest = { importing = false },
            title = { Text(stringResource(R.string.host_profile_import_json)) },
            text = {
                OutlinedTextField(
                    value = importText,
                    onValueChange = { importText = it },
                    label = { Text(stringResource(R.string.common_json)) },
                    minLines = 6,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.importHostProfile(importText)
                        .onFailure { error = it.message ?: "Import failed" }
                    importing = false
                    importText = ""
                }) { Text(stringResource(R.string.common_import)) }
            },
            dismissButton = { TextButton(onClick = { importing = false }) { Text(stringResource(R.string.common_cancel)) } }
        )
    }

    exportText?.let { json ->
        AlertDialog(
            onDismissRequest = { exportText = null },
            title = { Text(stringResource(R.string.host_profile_export_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.host_profile_no_secrets), style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(json, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                Button(onClick = {
                    clipboard.setText(AnnotatedString(json))
                    exportText = null
                }) { Text(stringResource(R.string.host_profile_copy_json)) }
            },
            dismissButton = { TextButton(onClick = { exportText = null }) { Text(stringResource(R.string.common_close)) } }
        )
    }

    if (confirmKeyRotation) {
        AlertDialog(
            onDismissRequest = { confirmKeyRotation = false },
            title = { Text(stringResource(R.string.host_profile_rotate_key_title)) },
            text = { Text(stringResource(R.string.host_profile_rotate_key_message)) },
            confirmButton = {
                Button(onClick = {
                    val key = viewModel.rotateSshKey()
                    clipboard.setText(AnnotatedString(key))
                    publicKeyCopied = true
                    confirmKeyRotation = false
                }) { Text(stringResource(R.string.host_profile_rotate_and_copy_key)) }
            },
            dismissButton = { TextButton(onClick = { confirmKeyRotation = false }) { Text(stringResource(R.string.common_cancel)) } }
        )
    }
}

@Composable
internal fun HostProfileRow(
    profile: HostProfile,
    selected: Boolean,
    canDelete: Boolean,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    OutlinedButton(
        onClick = onOpen,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("host.profile.row.${profile.id}")
    ) {
        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
            Text(if (selected) stringResource(R.string.host_profile_current_suffix, profile.displayName) else profile.displayName)
            Text(profile.connectionSummary, style = MaterialTheme.typography.bodySmall)
        }
        Text(if (profile.transport == HostTransport.SSH_TUNNEL) stringResource(R.string.host_profile_ssh_tunnel) else stringResource(R.string.host_profile_direct))
        IconButton(onClick = { menuExpanded = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.host_profile_actions))
        }
        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.common_edit)) }, onClick = { menuExpanded = false; onEdit() }, leadingIcon = { Icon(Icons.Default.Edit, null) })
            DropdownMenuItem(text = { Text(stringResource(R.string.common_duplicate)) }, onClick = { menuExpanded = false; onDuplicate() })
            DropdownMenuItem(text = { Text(stringResource(R.string.host_profile_export_json)) }, onClick = { menuExpanded = false; onExport() }, leadingIcon = { Icon(Icons.Default.ContentCopy, null) })
            DropdownMenuItem(
                text = { Text(if (canDelete) stringResource(R.string.common_delete) else stringResource(R.string.host_profile_keep_one)) },
                onClick = { menuExpanded = false; if (canDelete) onDelete() },
                enabled = canDelete,
                leadingIcon = { Icon(Icons.Default.Delete, null) }
            )
        }
    }
}

@Composable
internal fun HostProfileDetailDialog(
    profile: HostProfile,
    isCurrent: Boolean,
    onDismiss: () -> Unit,
    onUse: () -> Unit,
    onEdit: () -> Unit,
    onExport: () -> Unit,
    onCopyPublicKey: () -> Unit,
    onTest: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(profile.displayName) },
        text = {
            Column {
                val transportLabel = if (profile.transport == HostTransport.SSH_TUNNEL) stringResource(R.string.host_profile_ssh_tunnel) else stringResource(R.string.host_profile_direct)
                Text(stringResource(R.string.host_profile_transport, transportLabel))
                Text(stringResource(R.string.host_profile_opencode_url, if (profile.transport == HostTransport.SSH_TUNNEL) stringResource(R.string.host_profile_managed_by_ssh) else profile.serverUrl))
                Text(stringResource(R.string.host_profile_status, if (isCurrent) stringResource(R.string.host_profile_current) else stringResource(R.string.host_profile_saved)))
                if (profile.transport == HostTransport.SSH_TUNNEL && profile.ssh != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(stringResource(R.string.host_profile_ssh_gateway), style = MaterialTheme.typography.labelMedium)
                    Text(stringResource(R.string.host_profile_host, profile.ssh.host))
                    Text(stringResource(R.string.host_profile_ssh_port_value, profile.ssh.port))
                    Text(stringResource(R.string.host_profile_username, profile.ssh.username))
                    Text(stringResource(R.string.host_profile_remote_port_value, profile.ssh.remotePort))
                }
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                if (!isCurrent) {
                    Button(onClick = onUse) { Text(stringResource(R.string.host_profile_use_this_host)) }
                    Spacer(modifier = Modifier.height(8.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onTest) { Text(stringResource(R.string.settings_test_connection)) }
                    OutlinedButton(onClick = onEdit) { Text(stringResource(R.string.common_edit)) }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onExport) { Text(stringResource(R.string.host_profile_copy_config_json)) }
                    if (profile.transport == HostTransport.SSH_TUNNEL) {
                        OutlinedButton(onClick = onCopyPublicKey) { Text(stringResource(R.string.host_profile_copy_device_key)) }
                    }
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) } }
    )
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun DevicePublicKeySection(
    copied: Boolean,
    onCopy: () -> Unit,
    onRotate: () -> Unit
) {
    SectionHeader(title = stringResource(R.string.host_profile_device_key))
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.host_profile_device_key_footer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onCopy,
                    modifier = Modifier.testTag("ssh.publicKey.copy")
                ) {
                    Text(if (copied) stringResource(R.string.host_profile_public_key_copied) else stringResource(R.string.host_profile_copy_device_key), maxLines = 1)
                }
                OutlinedButton(
                    onClick = onRotate,
                    modifier = Modifier.testTag("ssh.publicKey.rotate")
                ) {
                    Text(stringResource(R.string.host_profile_rotate_key), maxLines = 1)
                }
            }
        }
    }
}

@Composable
internal fun HostProfileEditorDialog(
    initial: HostProfile,
    onDismiss: () -> Unit,
    onSave: (HostProfile, String?) -> Unit,
    onCopyPublicKey: () -> Unit = {},
) {
    var name by remember(initial.id) { mutableStateOf(initial.name) }
    var transport by remember(initial.id) { mutableStateOf(initial.transport) }
    var serverUrl by remember(initial.id) { mutableStateOf(initial.serverUrl) }
    var authUsername by remember(initial.id) { mutableStateOf(initial.basicAuth?.username.orEmpty()) }
    var authPassword by remember(initial.id) { mutableStateOf("") }
    var sshHost by remember(initial.id) { mutableStateOf(initial.ssh?.host.orEmpty()) }
    var sshPort by remember(initial.id) { mutableStateOf((initial.ssh?.port ?: 8006).toString()) }
    var sshUsername by remember(initial.id) { mutableStateOf(initial.ssh?.username ?: "opencode") }
    var remotePort by remember(initial.id) { mutableStateOf((initial.ssh?.remotePort ?: 19001).toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.name.isBlank()) stringResource(R.string.host_profile_add_title) else stringResource(R.string.host_profile_edit_title)) },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(stringResource(R.string.host_profile_name)) }, modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(8.dp))
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = transport == HostTransport.DIRECT,
                        onClick = { transport = HostTransport.DIRECT },
                        shape = SegmentedButtonDefaults.itemShape(0, 2),
                        modifier = Modifier.testTag("host.editor.transport.direct")
                    ) { Text(stringResource(R.string.host_profile_direct)) }
                    SegmentedButton(
                        selected = transport == HostTransport.SSH_TUNNEL,
                        onClick = { transport = HostTransport.SSH_TUNNEL },
                        shape = SegmentedButtonDefaults.itemShape(1, 2),
                        modifier = Modifier.testTag("host.editor.transport.ssh")
                    ) { Text(stringResource(R.string.host_profile_ssh_tunnel)) }
                }
                Spacer(modifier = Modifier.height(8.dp))
                if (transport == HostTransport.DIRECT) {
                    OutlinedTextField(value = serverUrl, onValueChange = { serverUrl = it }, label = { Text(stringResource(R.string.settings_server_url)) }, modifier = Modifier.fillMaxWidth())
                } else {
                    OutlinedTextField(value = sshHost, onValueChange = { sshHost = it }, label = { Text(stringResource(R.string.host_profile_ssh_gateway_host)) }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = sshPort, onValueChange = { sshPort = it }, label = { Text(stringResource(R.string.host_profile_ssh_port)) }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = sshUsername, onValueChange = { sshUsername = it }, label = { Text(stringResource(R.string.host_profile_ssh_username)) }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = remotePort, onValueChange = { remotePort = it }, label = { Text(stringResource(R.string.host_profile_remote_port)) }, modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(onClick = onCopyPublicKey) { Text(stringResource(R.string.host_profile_copy_device_key)) }
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(value = authUsername, onValueChange = { authUsername = it }, label = { Text(stringResource(R.string.host_profile_basic_auth_username)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = authPassword, onValueChange = { authPassword = it }, label = { Text(stringResource(R.string.host_profile_basic_auth_password)) }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            Button(onClick = {
                val basicAuth = authUsername.ifBlank { null }?.let { BasicAuthConfig(username = it, passwordId = initial.id) }
                val saved = if (transport == HostTransport.DIRECT) {
                    initial.copy(name = name.ifBlank { "Untitled" }, transport = HostTransport.DIRECT, serverUrl = serverUrl, basicAuth = basicAuth, ssh = null)
                } else {
                    initial.copy(
                        name = name.ifBlank { "Untitled" },
                        transport = HostTransport.SSH_TUNNEL,
                        serverUrl = "http://127.0.0.1:4096",
                        basicAuth = basicAuth,
                        ssh = SshTunnelConfig(
                            host = sshHost,
                            port = sshPort.toIntOrNull() ?: 8006,
                            username = sshUsername,
                            remotePort = remotePort.toIntOrNull() ?: 19001
                        )
                    )
                }
                onSave(saved, authPassword.ifBlank { null })
            }) { Text(stringResource(R.string.settings_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } }
    )
}

private fun newDirectProfile(): HostProfile = HostProfile.defaultDirect()

package com.yage.opencode_client.ui.files

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(
    viewModel: FilesViewModel = hiltViewModel(),
    pathToShow: String? = null,
    sessionDirectory: String? = null,
    onCloseFile: () -> Unit = {},
    onFileClick: (String) -> Unit = {}
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(pathToShow, sessionDirectory) {
        viewModel.syncPathToShow(pathToShow, sessionDirectory)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (state.selectedFilePath == null) {
            TopAppBar(
                title = { Text(state.currentPath.ifEmpty { "Files" }) },
                navigationIcon = {
                    if (state.currentPath.isNotEmpty()) {
                        IconButton(onClick = viewModel::navigateUp) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        }

        state.error?.let { message ->
            Snackbar(
                modifier = Modifier.padding(16.dp),
                action = {
                    TextButton(onClick = viewModel::clearError) {
                        Text("Dismiss")
                    }
                }
            ) {
                Text(message)
            }
        }

        when {
            state.selectedFilePath != null && state.selectedFileContent != null -> {
                FilePreviewPane(
                    path = state.selectedFilePath!!,
                    fileContent = state.selectedFileContent!!,
                    repository = viewModel.repository,
                    onClose = {
                        viewModel.closePreview()
                        onCloseFile()
                    }
                )
            }

            state.isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    androidx.compose.material3.CircularProgressIndicator()
                }
            }

            else -> {
                FileBrowserPane(
                    files = state.files,
                    fileStatuses = state.fileStatuses,
                    onFileSelected = { file -> viewModel.selectFile(file, onFileClick) }
                )
            }
        }
    }
}

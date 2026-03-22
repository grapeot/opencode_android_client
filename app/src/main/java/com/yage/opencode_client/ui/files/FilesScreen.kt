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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yage.opencode_client.data.model.FileContent
import com.yage.opencode_client.data.model.FileNode
import com.yage.opencode_client.data.repository.OpenCodeRepository
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(
    repository: OpenCodeRepository,
    pathToShow: String? = null,
    sessionDirectory: String? = null,
    onCloseFile: () -> Unit = {},
    onFileClick: (String) -> Unit = {}
) {
    var currentPath by remember { mutableStateOf("") }
    var files by remember { mutableStateOf<List<FileNode>>(emptyList()) }
    var fileStatuses by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var selectedFileContent by remember { mutableStateOf<FileContent?>(null) }
    var selectedFilePath by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScope()

    fun setDirectoryPreview(path: String, relPath: String, tree: List<FileNode>) {
        selectedFilePath = path
        selectedFileContent = FileContent(
            type = "text",
            content = buildDirectoryPreviewContent(relPath, tree)
        )
    }

    fun loadFiles(path: String) {
        scope.launch {
            isLoading = true
            error = null
            repository.getFileTree(path.ifEmpty { null })
                .onSuccess { files = it }
                .onFailure { error = it.message }
            isLoading = false
        }
    }

    fun loadFileStatuses() {
        scope.launch {
            repository.getFileStatus()
                .onSuccess { statuses ->
                    fileStatuses = statuses.mapNotNull { entry ->
                        entry.path?.let { it to (entry.status ?: "untracked") }
                    }.toMap()
                }
        }
    }

    fun loadFileContent(path: String) {
        scope.launch {
            repository.getFileContent(path)
                .onSuccess { content ->
                    selectedFileContent = content
                    selectedFilePath = path
                }
                .onFailure { error = it.message }
        }
    }

    LaunchedEffect(pathToShow, sessionDirectory) {
        if (pathToShow == null) {
            selectedFilePath = null
            selectedFileContent = null
        } else {
            val relPath = resolveRelativePreviewPath(pathToShow, sessionDirectory)
            repository.getFileContent(relPath)
                .onSuccess { content ->
                    if (!content.content.isNullOrBlank()) {
                        selectedFileContent = content
                        selectedFilePath = pathToShow
                    } else {
                        repository.getFileTree(relPath)
                            .onSuccess { tree -> setDirectoryPreview(pathToShow, relPath, tree) }
                            .onFailure { error = it.message }
                    }
                }
                .onFailure {
                    repository.getFileTree(relPath)
                        .onSuccess { tree -> setDirectoryPreview(pathToShow, relPath, tree) }
                        .onFailure { error = it.message }
                }
        }
    }

    LaunchedEffect(Unit) {
        loadFiles(currentPath)
        loadFileStatuses()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (selectedFilePath == null) {
            TopAppBar(
                title = { Text(currentPath.ifEmpty { "Files" }) },
                navigationIcon = {
                    if (currentPath.isNotEmpty()) {
                        IconButton(onClick = {
                            currentPath = currentPath.substringBeforeLast("/", "")
                            loadFiles(currentPath)
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    IconButton(onClick = {
                        loadFiles(currentPath)
                        loadFileStatuses()
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        }

        error?.let { message ->
            Snackbar(
                modifier = Modifier.padding(16.dp),
                action = {
                    TextButton(onClick = { error = null }) {
                        Text("Dismiss")
                    }
                }
            ) {
                Text(message)
            }
        }

        when {
            selectedFilePath != null && selectedFileContent != null -> {
                FilePreviewPane(
                    path = selectedFilePath!!,
                    fileContent = selectedFileContent!!,
                    repository = repository,
                    onClose = {
                        selectedFilePath = null
                        selectedFileContent = null
                        onCloseFile()
                    }
                )
            }

            isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    androidx.compose.material3.CircularProgressIndicator()
                }
            }

            else -> {
                FileBrowserPane(
                    files = files,
                    fileStatuses = fileStatuses,
                    onFileSelected = { file ->
                        if (file.isDirectory) {
                            currentPath = file.path
                            loadFiles(file.path)
                        } else {
                            onFileClick(file.path)
                            loadFileContent(file.path)
                        }
                    }
                )
            }
        }
    }
}

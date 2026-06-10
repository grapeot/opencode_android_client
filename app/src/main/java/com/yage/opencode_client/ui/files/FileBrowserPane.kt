package com.yage.opencode_client.ui.files

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.yage.opencode_client.data.model.FileNode
import com.yage.opencode_client.ui.theme.AddedFile
import com.yage.opencode_client.ui.theme.DeletedFile
import com.yage.opencode_client.ui.theme.ModifiedFile
import com.yage.opencode_client.ui.theme.UntrackedFile

@Composable
internal fun FileBrowserPane(
    files: List<FileNode>,
    fileStatuses: Map<String, String>,
    onFileSelected: (FileNode) -> Unit
) {
    LazyColumn(modifier = Modifier.fillMaxWidth()) {
        items(files, key = { it.path }) { file ->
            FileRow(
                file = file,
                status = fileStatuses[file.path],
                onClick = { onFileSelected(file) }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FileRow(
    file: FileNode,
    status: String?,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    var showMenu by remember { mutableStateOf(false) }

    val statusColor = when (status) {
        "added" -> AddedFile
        "modified" -> ModifiedFile
        "deleted" -> DeletedFile
        else -> if (status == "untracked") UntrackedFile else null
    }

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = { showMenu = true }
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (file.isDirectory) Icons.Default.Folder else Icons.AutoMirrored.Filled.InsertDriveFile,
                contentDescription = null,
                tint = statusColor ?: MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = file.name,
                style = MaterialTheme.typography.bodyLarge,
                color = statusColor ?: MaterialTheme.colorScheme.onSurface
            )
            if (file.ignored == true) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "ignored",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }

        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false }
        ) {
            DropdownMenuItem(
                text = { Text("Copy path") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = null
                    )
                },
                onClick = {
                    showMenu = false
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("file path", file.path)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(context, "Path copied", Toast.LENGTH_SHORT).show()
                }
            )
        }
    }
}

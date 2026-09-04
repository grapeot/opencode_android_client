package com.yage.opencode_client.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yage.opencode_client.R
import com.yage.opencode_client.data.model.ModelShortlistItem
import com.yage.opencode_client.ui.CatalogModel
import com.yage.opencode_client.ui.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelShortlistScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showAddCatalog by remember { mutableStateOf(false) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var editingShortName by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.model_shortlist_title)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                }
            },
            actions = {
                IconButton(onClick = { showAddCatalog = true }) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.model_shortlist_add))
                }
            }
        )

        if (state.modelShortlist.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.model_shortlist_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
                    .testTag("model.shortlist.list")
            ) {
                state.modelShortlist.forEachIndexed { index, item ->
                    ModelShortlistRow(
                        item = item,
                        providerSubtitle = "${state.providerDisplayNames[item.providerId] ?: item.providerId} / ${item.modelId}",
                        isFirst = index == 0,
                        isLast = index == state.modelShortlist.size - 1,
                        onMoveUp = { viewModel.moveModelShortlist(index, index - 1) },
                        onMoveDown = { viewModel.moveModelShortlist(index, index + 1) },
                        onDelete = { viewModel.removeModelShortlistItem(item.id) },
                        onEditShortName = {
                            editingId = item.id
                            editingShortName = item.shortName
                        }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }

    editingId?.let { id ->
        AlertDialog(
            onDismissRequest = { editingId = null },
            title = { Text(stringResource(R.string.model_shortlist_edit_short_name)) },
            text = {
                OutlinedTextField(
                    value = editingShortName,
                    onValueChange = { editingShortName = it },
                    label = { Text(stringResource(R.string.model_shortlist_short_name)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.updateModelShortlistShortName(id, editingShortName)
                    editingId = null
                }) { Text(stringResource(R.string.settings_save)) }
            },
            dismissButton = {
                TextButton(onClick = { editingId = null }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    if (showAddCatalog) {
        AddModelCatalogDialog(
            catalog = state.catalogModels,
            providerDisplayNames = state.providerDisplayNames,
            existingIds = state.modelShortlist.map { it.id }.toSet(),
            onConfirm = { selected ->
                viewModel.addModelsToShortlist(
                    selected.map { cm ->
                        ModelShortlistItem(cm.providerId, cm.modelId, cm.displayName, cm.shortName)
                    }
                )
                showAddCatalog = false
            },
            onDismiss = { showAddCatalog = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelShortlistRow(
    item: ModelShortlistItem,
    providerSubtitle: String,
    isFirst: Boolean,
    isLast: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
    onEditShortName: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    // Aligns with HostProfileRow: whole row opens the short-name editor, all
    // management actions live behind a single compact overflow menu so the
    // text gets the card's full width.
    Card(
        onClick = onEditShortName,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("model.shortlist.row.${item.id}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        item.displayName,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    // The label the chat capsule shows, echoed as a compact badge.
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                        modifier = Modifier.widthIn(max = 120.dp)
                    ) {
                        Text(
                            item.shortName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    providerSubtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = stringResource(R.string.chat_more_options),
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.model_shortlist_edit_short_name)) },
                        onClick = { menuExpanded = false; onEditShortName() },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.model_shortlist_move_up)) },
                        enabled = !isFirst,
                        onClick = { menuExpanded = false; onMoveUp() },
                        leadingIcon = { Icon(Icons.Default.KeyboardArrowUp, contentDescription = null) }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.model_shortlist_move_down)) },
                        enabled = !isLast,
                        onClick = { menuExpanded = false; onMoveDown() },
                        leadingIcon = { Icon(Icons.Default.KeyboardArrowDown, contentDescription = null) }
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(R.string.common_delete),
                                color = MaterialTheme.colorScheme.error
                            )
                        },
                        onClick = { menuExpanded = false; onDelete() },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun AddModelCatalogDialog(
    catalog: List<CatalogModel>,
    providerDisplayNames: Map<String, String>,
    existingIds: Set<String>,
    onConfirm: (List<CatalogModel>) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    val q = query.trim().lowercase()
    val filtered = catalog.filter { cm ->
        q.isEmpty() ||
            cm.displayName.lowercase().contains(q) ||
            cm.modelId.lowercase().contains(q) ||
            cm.providerId.lowercase().contains(q)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.model_shortlist_add_title)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.model_shortlist_search)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(12.dp))
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                ) {
                    if (filtered.isEmpty()) {
                        item {
                            Text(
                                stringResource(R.string.model_shortlist_no_models),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    items(filtered, key = { it.id }) { cm ->
                        val inShortlist = cm.id in existingIds
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !inShortlist) {
                                    selected = if (cm.id in selected) selected - cm.id else selected + cm.id
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = cm.id in selected,
                                enabled = !inShortlist,
                                onCheckedChange = {
                                    selected = if (cm.id in selected) selected - cm.id else selected + cm.id
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(cm.displayName, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "${providerDisplayNames[cm.providerId] ?: cm.providerId} / ${cm.modelId}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (inShortlist) {
                                Text(
                                    stringResource(R.string.model_shortlist_already_added),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(catalog.filter { it.id in selected }) },
                enabled = selected.isNotEmpty()
            ) {
                Text(stringResource(R.string.model_shortlist_add_confirm, selected.size))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}
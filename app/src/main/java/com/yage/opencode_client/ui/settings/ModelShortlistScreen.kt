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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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

@Composable
private fun ModelShortlistRow(
    item: ModelShortlistItem,
    isFirst: Boolean,
    isLast: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
    onEditShortName: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("model.shortlist.row.${item.id}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                Text(item.displayName, style = MaterialTheme.typography.bodyLarge)
                Text(
                    item.shortName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onEditShortName) {
                Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.model_shortlist_edit_short_name))
            }
            IconButton(onClick = onMoveUp, enabled = !isFirst) {
                Icon(Icons.Default.KeyboardArrowUp, contentDescription = stringResource(R.string.model_shortlist_move_up))
            }
            IconButton(onClick = onMoveDown, enabled = !isLast) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = stringResource(R.string.model_shortlist_move_down))
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.common_delete))
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
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    if (filtered.isEmpty()) {
                        Text(
                            stringResource(R.string.model_shortlist_no_models),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    filtered.forEach { cm ->
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
            Button(onClick = { onConfirm(filtered.filter { it.id in selected }) }) {
                Text(stringResource(R.string.model_shortlist_add_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}
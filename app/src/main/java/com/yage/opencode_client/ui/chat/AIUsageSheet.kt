package com.yage.opencode_client.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yage.opencode_client.R
import com.yage.opencode_client.data.model.AIUsageQuota
import com.yage.opencode_client.data.model.AIUsageQuotaSnapshot
import java.text.DateFormat
import java.util.Date

@Composable
internal fun AIUsageSheet(
    snapshot: AIUsageQuotaSnapshot?,
    isLoading: Boolean,
    isRefreshing: Boolean,
    error: String?,
    dashboardUrl: String,
    onRefresh: () -> Unit
) {
    val groups = snapshot?.quotas.orEmpty().groupBy { it.provider }.toSortedMap()
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .testTag("ai_usage.sheet")
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(stringResource(R.string.ai_usage_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Button(onClick = onRefresh, enabled = !isLoading) {
                    if (isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        androidx.compose.material3.Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.ai_usage_refresh))
                }
            }
            if (isLoading) {
                Spacer(Modifier.height(12.dp))
                Text(
                    if (isRefreshing) stringResource(R.string.ai_usage_refreshing_providers)
                    else stringResource(R.string.ai_usage_loading),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (error != null) {
                Spacer(Modifier.height(12.dp))
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (!isLoading && groups.isEmpty() && error == null) {
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.ai_usage_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(12.dp))
        }

        groups.forEach { (provider, quotas) ->
            item {
                Text(
                    providerDisplayName(provider),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 10.dp)
                )
            }
            items(quotas.size, key = { quotas[it].provider + "|" + quotas[it].label }) { index ->
                QuotaRow(quotas[index])
                if (index != quotas.lastIndex) HorizontalDivider()
            }
        }

        item {
            Spacer(Modifier.height(16.dp))
            snapshot?.let {
                it.generatedAt?.let { generatedAt ->
                    Text(
                        stringResource(R.string.ai_usage_generated_at, generatedAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("ai_usage.generated_at")
                    )
                    Spacer(Modifier.height(4.dp))
                }
                Text(
                    stringResource(
                        R.string.ai_usage_last_fetched,
                        DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it.fetchedAtMs))
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(dashboardUrl, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun QuotaRow(quota: AIUsageQuota) {
    val remaining = quota.clampedRemainingPercentage
    val color = when {
        remaining <= 10 -> MaterialTheme.colorScheme.error
        remaining <= 20 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }
    Column(modifier = Modifier.padding(vertical = 10.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(quota.label, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.ai_usage_remaining, remaining), color = color, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { remaining / 100f },
            modifier = Modifier.fillMaxWidth(),
            color = color
        )
        Spacer(Modifier.height(6.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.ai_usage_used, quota.clampedUsedPercentage), style = MaterialTheme.typography.labelSmall)
            quota.nextResetTimeMs?.let {
                Text(
                    stringResource(R.string.ai_usage_resets, DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it))),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

private fun providerDisplayName(provider: String): String = when (provider.lowercase()) {
    "codex" -> "OpenAI / Codex"
    "glm" -> "Z.ai / GLM"
    "ollama" -> "Ollama Cloud"
    "claude" -> "Claude"
    "antigravity" -> "Antigravity"
    else -> provider
}

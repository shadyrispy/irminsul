package com.esc.irminsul.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.esc.irminsul.MainViewModel
import com.esc.irminsul.PacketRecord
import com.esc.irminsul.R
import java.text.SimpleDateFormat
import java.util.Locale

/** Newest-first list of decoded game commands with text filter. */
@Composable
fun PacketListScreen(
    viewModel: MainViewModel,
    onOpenDetail: (packetId: Long, commandIndex: Int) -> Unit
) {
    val records by viewModel.packetLog.records.collectAsState()
    var query by remember { mutableStateOf("") }

    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
    val filtered = remember(records, query) {
        val matching = if (query.isBlank()) {
            records
        } else {
            records.filter {
                it.name.contains(query, ignoreCase = true) || it.cmdId.toString().contains(query)
            }
        }
        matching.asReversed()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.packets_title),
                style = MaterialTheme.typography.titleLarge
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = stringResource(R.string.packet_count, filtered.size),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = { viewModel.packetLog.clear() }) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.packet_clear),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text(stringResource(R.string.packet_search_hint)) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(Icons.Filled.Close, contentDescription = null)
                    }
                }
            },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        )

        if (filtered.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.packet_list_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filtered, key = { "${it.packetId}-${it.commandIndex}" }) { record ->
                    PacketRow(record, timeFormat, onClick = {
                        onOpenDetail(record.packetId, record.commandIndex)
                    })
                }
            }
        }
    }
}

@Composable
private fun PacketRow(
    record: PacketRecord,
    timeFormat: SimpleDateFormat,
    onClick: () -> Unit
) {
    val unknown = record.name == "unknown"
    val containerColor = if (unknown) {
        MaterialTheme.colorScheme.surfaceVariant
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }
    val titleColor = if (unknown) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .background(containerColor, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = record.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = timeFormat.format(record.timestampMillis),
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
        Spacer(modifier = Modifier.padding(top = 2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .background(directionColor(record.isSent), CircleShape)
                    .padding(1.dp)
            )
            Text(
                text = directionLabel(record.isSent),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = directionColor(record.isSent),
                modifier = Modifier.padding(start = 6.dp)
            )
            Text(
                text = buildString {
                    append(" · ")
                    append(humanSize(record.sizeBytes))
                    append(" · ")
                    append(record.cmdId)
                    record.fieldCount?.let { append(" · $it fields") }
                },
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (record.parseError) {
                Text(
                    text = stringResource(R.string.packet_parse_error),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 6.dp)
                )
            }
        }
        if (record.briefKeys.isNotEmpty()) {
            Text(
                text = record.briefKeys.joinToString(", "),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

private fun directionLabel(isSent: Boolean): String = if (isSent) "C→S" else "S→C"

@Composable
private fun directionColor(isSent: Boolean) =
    if (isSent) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary

private fun humanSize(bytes: Int): String = when {
    bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024f * 1024f))
    bytes >= 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024f)
    else -> "$bytes B"
}

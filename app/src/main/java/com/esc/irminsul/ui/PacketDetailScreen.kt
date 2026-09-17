package com.esc.irminsul.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.esc.irminsul.MainViewModel
import com.esc.irminsul.NativeLib
import com.esc.irminsul.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Detail view of a single decoded command: the full JSON body is fetched
 * on demand from the native cache and rendered as an expandable tree.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PacketDetailScreen(
    viewModel: MainViewModel,
    packetId: Long,
    commandIndex: Int,
    onBack: () -> Unit
) {
    val record = remember(packetId, commandIndex) {
        viewModel.packetLog.records.value.firstOrNull {
            it.packetId == packetId && it.commandIndex == commandIndex
        }
    }
    var rootNode by remember { mutableStateOf<ProtoNode?>(null) }
    var rawJson by remember { mutableStateOf<String?>(null) }
    var unavailable by remember { mutableStateOf(false) }

    LaunchedEffect(packetId, commandIndex) {
        val json = withContext(Dispatchers.IO) { NativeLib.commandBody(packetId, commandIndex) }
        if (json == null) {
            unavailable = true
        } else {
            rawJson = json
            rootNode = withContext(Dispatchers.Default) {
                buildProtoTree("root", JSONObject(json))
            }
        }
    }

    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val copyJson: () -> Unit = {
        rawJson?.let {
            clipboard.setText(AnnotatedString(it))
            Toast.makeText(context, R.string.toast_copied, Toast.LENGTH_SHORT).show()
        }
        Unit
    }

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        TopAppBar(
            title = {
                Column {
                    Text(
                        text = record?.name ?: "packet $packetId #$commandIndex",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1
                    )
                    record?.let {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(
                                        if (it.isSent) {
                                            MaterialTheme.colorScheme.tertiary
                                        } else {
                                            MaterialTheme.colorScheme.primary
                                        },
                                        CircleShape
                                    )
                            )
                            Text(
                                text = if (it.isSent) "C→S" else "S→C",
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(start = 6.dp)
                            )
                            Text(
                                text = "cmd ${it.cmdId} · ${it.sizeBytes} B",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                }
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = null
                    )
                }
            },
            actions = {
                if (rawJson != null) {
                    TextButton(onClick = copyJson) {
                        Text(stringResource(R.string.packet_copy_json))
                    }
                }
            }
        )

        when {
            rootNode != null -> ProtoJsonTree(rootNode!!, Modifier.fillMaxSize())
            unavailable -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.packet_body_unavailable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 32.dp)
                )
            }
            else -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }
    }
}

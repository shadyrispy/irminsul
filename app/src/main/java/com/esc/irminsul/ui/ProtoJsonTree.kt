package com.esc.irminsul.ui

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import org.json.JSONArray
import org.json.JSONObject

private const val MAX_INLINE_LENGTH = 120
private const val DEFAULT_EXPAND_DEPTH = 2

/** A node of the rendered proto-JSON tree. Path is the stable identity used for expand state and copy. */
sealed interface ProtoNode {
    val key: String
    val path: String
    val depth: Int
}

data class BranchNode(
    override val key: String,
    override val path: String,
    override val depth: Int,
    val children: List<ProtoNode>
) : ProtoNode

data class LeafNode(
    override val key: String,
    override val path: String,
    override val depth: Int,
    /** Full value string; may be huge (e.g. base64 bytes). */
    val fullValue: String,
    val truncated: Boolean
) : ProtoNode

fun buildProtoTree(rootKey: String, value: Any?): ProtoNode =
    nodeFor(rootKey, "$", 0, value)

private fun nodeFor(key: String, path: String, depth: Int, value: Any?): ProtoNode {
    if (value == null || value == JSONObject.NULL) {
        return LeafNode(key, path, depth, "null", false)
    }
    return when (value) {
        is JSONObject -> {
            val children = value.keys().asSequence()
                .toList()
                .sorted()
                .map { k -> nodeFor(k, "$path.$k", depth + 1, value.opt(k)) }
            if (children.isEmpty()) {
                LeafNode(key, path, depth, "{}", false)
            } else {
                BranchNode(key, path, depth, children)
            }
        }
        is JSONArray -> {
            val children = (0 until value.length())
                .map { i -> nodeFor("[$i]", "$path[$i]", depth + 1, value.opt(i)) }
            if (children.isEmpty()) {
                LeafNode(key, path, depth, "[]", false)
            } else {
                BranchNode(key, path, depth, children)
            }
        }
        is String -> {
            val truncated = value.length > MAX_INLINE_LENGTH
            LeafNode(key, path, depth, value, truncated)
        }
        else -> LeafNode(key, path, depth, value.toString(), false)
    }
}

/**
 * Flatten the tree into the visible row list: children of collapsed branches
 * are skipped, so the result size stays proportional to what is on screen.
 * Branches default to expanded above [DEFAULT_EXPAND_DEPTH].
 */
fun flattenTree(root: ProtoNode, expanded: Map<String, Boolean>): List<ProtoNode> {
    val out = ArrayList<ProtoNode>(256)
    fun walk(node: ProtoNode) {
        out.add(node)
        if (node !is BranchNode) return
        if (expanded[node.path] ?: (node.depth < DEFAULT_EXPAND_DEPTH)) {
            node.children.forEach(::walk)
        }
    }
    walk(root)
    return out
}

/**
 * Lazy, flattened JSON tree renderer: vertical indent guide per depth level,
 * tap to expand/collapse (or reveal a truncated string), long-press to copy
 * the node path. Expand state lives in path-keyed maps outside the tree so
 * lazy rows can be recreated freely.
 */
@Composable
fun ProtoJsonTree(root: ProtoNode, modifier: Modifier = Modifier) {
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    val fullTextShown = remember { mutableStateMapOf<String, Boolean>() }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val onCopyPath = { path: String ->
        clipboard.setText(AnnotatedString(path))
        Toast.makeText(context, context.getString(com.esc.irminsul.R.string.toast_copied), Toast.LENGTH_SHORT).show()
    }

    val rows = flattenTree(root, expanded)

    LazyColumn(modifier = modifier.fillMaxSize()) {
        items(rows, key = { it.path }) { node ->
            NodeRow(
                node = node,
                isExpanded = node is BranchNode && (expanded[node.path] ?: (node.depth < DEFAULT_EXPAND_DEPTH)),
                showFullText = fullTextShown[node.path] == true,
                onToggle = {
                    when {
                        node is BranchNode -> {
                            val current = expanded[node.path] ?: (node.depth < DEFAULT_EXPAND_DEPTH)
                            expanded[node.path] = !current
                        }
                        node is LeafNode && node.truncated -> {
                            fullTextShown[node.path] = !(fullTextShown[node.path] ?: false)
                        }
                    }
                },
                onCopyPath = onCopyPath
            )
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun NodeRow(
    node: ProtoNode,
    isExpanded: Boolean,
    showFullText: Boolean,
    onToggle: () -> Unit,
    onCopyPath: (String) -> Unit
) {
    val guideColor = MaterialTheme.colorScheme.surfaceVariant
    val clickable = node is BranchNode || (node is LeafNode && node.truncated)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .combinedClickable(
                onClick = { if (clickable) onToggle() },
                onLongClick = { onCopyPath(node.path) }
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(node.depth) {
            Box(
                modifier = Modifier
                    .width(16.dp)
                    .fillMaxHeight()
                    .drawBehind {
                        drawLine(
                            color = guideColor,
                            start = Offset(size.width / 2, 0f),
                            end = Offset(size.width / 2, size.height),
                            strokeWidth = 1.dp.toPx()
                        )
                    }
            )
        }
        when (node) {
            is BranchNode -> {
                Icon(
                    imageVector = if (isExpanded) {
                        Icons.Filled.KeyboardArrowDown
                    } else {
                        Icons.Filled.KeyboardArrowRight
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = node.key,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 4.dp)
                )
                Text(
                    text = "[${node.children.size}]",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
            is LeafNode -> {
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = node.key,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .weight(1f, fill = false)
                )
                Text(
                    text = if (node.truncated && !showFullText) {
                        node.fullValue.take(MAX_INLINE_LENGTH - 1) + "…"
                    } else {
                        node.fullValue
                    },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .weight(1f)
                )
            }
        }
    }
}

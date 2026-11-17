package com.example.gleannote.core.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.gleannote.core.contract.AppResult
import com.example.gleannote.core.contract.Folder
import com.example.gleannote.core.contract.GleanNoteRepository
import com.example.gleannote.core.contract.Limits
import com.example.gleannote.core.contract.Tag
import kotlinx.coroutines.launch

/*
 * 复用型交互弹窗：重命名、移动到、标签选择。
 * 对应 PRD FR-02（重命名/移动）、FR-06/FR-08（重命名/移动到）、FR-13（标签）。
 */

/* 单行文本输入弹窗：目录名、笔记标题、标签名、昵称共用。 */
@Composable
fun TextInputDialog(
    title: String,
    label: String,
    initial: String,
    maxLength: Int,
    confirmLabel: String = "确定",
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var value by remember { mutableStateOf(initial) }
    val trimmed = value.trim()
    val tooLong = trimmed.length > maxLength
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text("$label（1-$maxLength 字符）") },
                    singleLine = true,
                    isError = tooLong,
                    modifier = Modifier.fillMaxWidth()
                )
                if (tooLong) {
                    Text(
                        "最多 $maxLength 个字符",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(trimmed) },
                enabled = trimmed.isNotEmpty() && !tooLong
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/*
 * 目录选择器：用于「移动到」。
 * 只列出当前账号的目录树（最多 5 层），提供「根级」选项；
 * 目标为自身或子孙时由服务端返回 FOLDER_CYCLE，这里按 excludeSubtreeOf 提前置灰（PRD FR-02）。
 */
@Composable
fun FolderPickerDialog(
    repo: GleanNoteRepository,
    title: String,
    excludeSubtreeOf: String? = null,
    allowRoot: Boolean = true,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    var tree by remember { mutableStateOf<List<Folder>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val collected = mutableListOf<Folder>()
        suspend fun walk(parentId: String?, depth: Int) {
            if (depth > Limits.MAX_DEPTH) return
            val r = repo.refreshFolders(parentId)
            if (r is AppResult.Success) {
                r.data.items.forEach { folder ->
                    collected += folder
                    walk(folder.id, depth + 1)
                }
            }
        }
        walk(null, 1)
        tree = collected
        loading = false
    }

    /* 目标不能是自身或自身子孙：用路径前缀判断，避免额外请求。 */
    fun isBlocked(folder: Folder): Boolean {
        val root = excludeSubtreeOf ?: return false
        return folder.id == root
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.heightIn(max = 420.dp)) {
                when {
                    loading -> Text("正在加载目录…", style = MaterialTheme.typography.bodySmall)
                    error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
                    else -> LazyColumn {
                        if (allowRoot) {
                            item {
                                Text(
                                    "根级（我的笔记）",
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onPick(null) }
                                        .padding(vertical = 12.dp)
                                )
                            }
                        }
                        items(tree, key = { it.id }) { folder ->
                            val blocked = isBlocked(folder)
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !blocked) { onPick(folder.id) }
                                    .padding(vertical = 10.dp, horizontal = ((folder.depth - 1).coerceAtLeast(0) * 12).dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    folder.name.ifBlank { "未命名目录" },
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = if (blocked) MaterialTheme.colorScheme.onSurfaceVariant
                                    else MaterialTheme.colorScheme.onSurface
                                )
                                if (blocked) {
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "不可选择",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/*
 * 笔记标签选择：输入即过滤已有标签，选择即复用；不存在的名称可新建。
 * 最多 10 个（PRD FR-13、Limits.MAX_TAGS_PER_NOTE）。
 */
@Composable
fun TagPickerDialog(
    repo: GleanNoteRepository,
    selected: List<Tag>,
    onConfirm: (List<Tag>) -> Unit,
    onDismiss: () -> Unit
) {
    var all by remember { mutableStateOf<List<Tag>>(emptyList()) }
    var picked by remember { mutableStateOf(selected) }
    var input by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    LaunchedEffect(Unit) {
        (repo.tags() as? AppResult.Success)?.let { all = it.data }
    }

    val limitReached = picked.size >= Limits.MAX_TAGS_PER_NOTE
    val candidates = all.filter { tag -> picked.none { it.id == tag.id } }
        .filter { input.isBlank() || it.name.contains(input.trim(), ignoreCase = true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("标签（最多 ${Limits.MAX_TAGS_PER_NOTE} 个）") },
        text = {
            Column(Modifier.heightIn(max = 420.dp)) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it; message = null },
                    label = { Text("输入以联想或新建") },
                    singleLine = true,
                    enabled = !limitReached,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    picked.forEach { tag ->
                        AssistChip(
                            onClick = { picked = picked.filterNot { it.id == tag.id } },
                            label = { Text("${tag.name} ✕", style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
                if (limitReached) {
                    Text("已达上限，移除一个后才能继续添加", style = MaterialTheme.typography.labelSmall)
                }
                message?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                }
                Spacer(Modifier.height(8.dp))
                LazyColumn {
                    if (input.isNotBlank() && candidates.none { it.name == input.trim() }) {
                        item {
                            TextButton(
                                onClick = {
                                    busy = true
                                    scope.launch {
                                        when (val r = repo.createTag(input.trim())) {
                                            is AppResult.Success -> {
                                                all = all + r.data
                                                picked = picked + r.data
                                                input = ""
                                            }
                                            is AppResult.Failure -> message = r.error.userMessage
                                        }
                                        busy = false
                                    }
                                },
                                enabled = !busy && !limitReached && input.trim().length in 1..20
                            ) { Text("新建「${input.trim()}」") }
                        }
                    }
                    items(candidates, key = { it.id }) { tag ->
                        Text(
                            "${tag.name}（${tag.noteCount} 篇）",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !limitReached) {
                                    picked = picked + tag
                                    input = ""
                                }
                                .padding(vertical = 10.dp)
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(picked) }) { Text("完成") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

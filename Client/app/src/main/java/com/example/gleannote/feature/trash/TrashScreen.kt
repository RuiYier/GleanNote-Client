package com.example.gleannote.feature.trash

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.example.gleannote.core.contract.AppResult
import com.example.gleannote.core.contract.GleanNoteRepository
import com.example.gleannote.core.contract.TrashItem
import com.example.gleannote.core.ui.ConfirmDialog
import com.example.gleannote.core.ui.EmptyState
import com.example.gleannote.core.ui.GleanTopBar
import kotlinx.coroutines.launch

/*
 * 回收站：按删除时间倒序，展示剩余保留天数（30 天），支持恢复与彻底删除。
 * 对应 PRD FR-09、GN-CLIENT-004 SCR-16。
 */
@Composable
fun TrashScreen(nav: NavHostController, repo: GleanNoteRepository) {
    val trashItems by repo.trash().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var pendingExpunge by remember { mutableStateOf<TrashItem?>(null) }

    LaunchedEffect(Unit) {
        when (val r = repo.refreshTrash()) {
            is AppResult.Success -> error = null
            is AppResult.Failure -> error = r.error.userMessage
        }
        loading = false
    }

    Scaffold(topBar = { GleanTopBar("回收站", onBack = { nav.popBackStack() }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text(
                "删除的内容保留 30 天，到期后由服务端自动清理。",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                error != null -> Text(error!!, Modifier.padding(24.dp), color = MaterialTheme.colorScheme.error)
                trashItems.isEmpty() -> EmptyState("回收站是空的")
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(trashItems, key = { it.batchId }) { item ->
                        Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                            Column(Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        item.name.ifBlank { "未命名" },
                                        style = MaterialTheme.typography.bodyLarge,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        if (item.itemType == "FOLDER") "目录" else "笔记",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "${item.originalPath} · 剩余 ${item.remainingDays} 天",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                                    TextButton(onClick = {
                                        scope.launch {
                                            if (item.itemType == "FOLDER") repo.undeleteFolder(item.itemId)
                                            else repo.undeleteNote(item.itemId)
                                            repo.refreshTrash()
                                        }
                                    }) { Text("恢复") }
                                    TextButton(onClick = { pendingExpunge = item }) { Text("彻底删除") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    pendingExpunge?.let { item ->
        ConfirmDialog(
            title = "彻底删除",
            text = "「${item.name}」将被永久删除，无法恢复。",
            onConfirm = {
                pendingExpunge = null
                scope.launch {
                    if (item.itemType == "FOLDER") repo.expungeFolder(item.itemId) else repo.expungeNote(item.itemId)
                    repo.refreshTrash()
                }
            },
            onDismiss = { pendingExpunge = null }
        )
    }
}

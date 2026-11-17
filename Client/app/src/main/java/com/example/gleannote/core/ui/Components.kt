package com.example.gleannote.core.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/* 全局通用组件：骨架屏、空态、错误态、离线标识、同步状态条、状态标签、二次确认、顶栏。
   对应 GN-CLIENT-004 第 2 章全局规格；所有 feature 复用，不各自实现。 */

@Composable
fun LoadingSkeleton(lines: Int = 5) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val shift by transition.animateFloat(
        initialValue = 0f, targetValue = 1000f,
        animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Restart), label = "shift"
    )
    val brush = Brush.linearGradient(
        colors = listOf(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.surface,
            MaterialTheme.colorScheme.surfaceVariant
        ),
        start = Offset(shift, 0f), end = Offset(shift + 300f, 0f)
    )
    Column(Modifier.padding(16.dp)) {
        repeat(lines) { i ->
            Box(
                Modifier
                    .fillMaxWidth(if (i == lines - 1) 0.6f else 1f)
                    .height(if (i == 0) 24.dp else 14.dp)
                    .background(brush, MaterialTheme.shapes.small)
            )
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
fun EmptyState(message: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Column(
        Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(12.dp))
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
fun ErrorState(message: String, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(message, color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(12.dp))
        Button(onClick = onRetry) { Text("重试") }
    }
}

@Composable
fun OfflineBanner() {
    Box(
        Modifier.fillMaxWidth()
            .background(extendedColors().warningContainer)
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Text(
            "离线模式 · 正在使用本地缓存",
            style = MaterialTheme.typography.labelMedium,
            color = extendedColors().warning
        )
    }
}

@Composable
fun SyncStatusBar(pendingCount: Int, syncing: Boolean, error: String?, onRetry: () -> Unit) {
    when {
        error != null -> Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("同步失败：$error", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
            TextButton(onClick = onRetry) { Text("重试") }
        }
        syncing -> Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text("同步中…", style = MaterialTheme.typography.labelSmall)
        }
        pendingCount > 0 -> Text(
            "有 $pendingCount 项内容待同步", Modifier.padding(8.dp), style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
fun StateChip(state: String, pendingSync: Boolean = false) {
    val label = when (state) {
        "DRAFT" -> "草稿"
        "DELETED" -> "已删除"
        "FAILED" -> "转换失败"
        "CANCELED" -> "已取消"
        else -> if (pendingSync) "待同步" else "正常"
    }
    if (label != "正常") AssistChip(onClick = {}, label = { Text(label, style = MaterialTheme.typography.labelSmall) })
}

@Composable
fun ConfirmDialog(title: String, text: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("确认") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GleanTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable (androidx.compose.foundation.layout.RowScope.() -> Unit) = {}
) {
    TopAppBar(
        title = { Text(title, maxLines = 1) },
        navigationIcon = {
            if (onBack != null) IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
        },
        actions = actions
    )
}

@Composable
fun SaveStatusIndicator(state: String) {
    val text = when (state) {
        "SAVING" -> "保存中…"
        "UNSAVED" -> "未保存"
        "PENDING" -> "待同步"
        else -> "已保存"
    }
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

package com.example.gleannote.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.example.gleannote.core.contract.AppResult
import com.example.gleannote.core.contract.GleanNoteRepository
import com.example.gleannote.core.contract.Quota
import com.example.gleannote.core.contract.Routes
import com.example.gleannote.core.contract.User
import com.example.gleannote.core.media.ImageProcessor
import com.example.gleannote.core.ui.ConfirmDialog
import com.example.gleannote.core.ui.GleanTopBar
import kotlinx.coroutines.launch
import java.io.File
import androidx.compose.foundation.clickable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import com.example.gleannote.core.ui.TextInputDialog
import com.example.gleannote.core.common.TimeFormat
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip

/*
 * 设置：账号、额度与云端容量、待同步数量、本地缓存清理、通知开关、退出登录。
 * 对应 PRD FR-11、GN-CLIENT-004 SCR-17（6 项状态）。
 */
@Composable
fun SettingsScreen(nav: NavHostController, repo: GleanNoteRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var user by remember { mutableStateOf<User?>(null) }
    var quota by remember { mutableStateOf<Quota?>(null) }
    var notifyEnabled by remember { mutableStateOf(true) }
    var cacheSize by remember { mutableStateOf(0L) }
    var confirmLogout by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    var renameOpen by remember { mutableStateOf(false) }
    var deleteAccountOpen by remember { mutableStateOf(false) }
    var legalDialog by remember { mutableStateOf<String?>(null) }
    val pending by repo.pendingCount().collectAsState(initial = 0)

    fun refresh() {
        scope.launch {
            (repo.me() as? AppResult.Success)?.let { user = it.data }
            (repo.quota() as? AppResult.Success)?.let { quota = it.data }
            cacheSize = File(context.cacheDir, "gleannote").walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Scaffold(topBar = { GleanTopBar("设置", onBack = { nav.popBackStack() }) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("账号", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(user?.nickname?.ifBlank { "未设置昵称" } ?: "加载中…", style = MaterialTheme.typography.bodyLarge)
                    /* 邮箱按 PRD FR-11 脱敏展示，只保留首字符与域名。 */
                    Text(maskEmail(user?.email.orEmpty()), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { renameOpen = true }) { Text("修改昵称") }
                        OutlinedButton(onClick = { nav.navigate(Routes.RESET_PASSWORD) }) { Text("找回密码") }
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("额度与云端空间", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    quota?.let { q ->
                        Text("今日剩余额度：${q.remaining} / ${q.dailyLimit} 张")
                        Text(
                            "云端空间：${q.storageUsedBytes / 1024 / 1024} MB / ${q.storageLimitBytes / 1024 / 1024} MB",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            "额度重置：${TimeFormat.dateTime(q.resetAt)}（北京时间次日 0 点）",
                            style = MaterialTheme.typography.labelSmall
                        )
                        Spacer(Modifier.height(8.dp))
                        /* 0% 时 M3 默认只留一个停止点，视觉上像孤立的小方块；
                           统一轨道色、加圆角并去掉停止点，空进度也能看出「这是一条容量条」 */
                        LinearProgressIndicator(
                            progress = { (q.storageUsedPercent / 100f).coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            trackColor = MaterialTheme.colorScheme.outlineVariant
                        )
                        Spacer(Modifier.height(4.dp))
                        when {
                            q.storageUsedPercent >= 100 -> Text(
                                "云端空间已满，新转换将被阻止", color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.labelMedium
                            )
                            q.storageUsedPercent >= 90 -> Text(
                                "云端空间即将用尽", color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.labelMedium
                            )
                            q.remaining <= 0 -> Text(
                                "今日额度不足", color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    } ?: Text("加载中…", style = MaterialTheme.typography.bodySmall)
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("待同步内容", style = MaterialTheme.typography.bodyLarge)
                        Text("$pending 项")
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { scope.launch { repo.sync(); refresh() } }) { Text("立即同步") }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().clickable { nav.navigate(Routes.TRASH) }.padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("回收站", style = MaterialTheme.typography.bodyLarge)
                    Text("查看已删除内容 ›", style = MaterialTheme.typography.bodySmall)
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("本地缓存", style = MaterialTheme.typography.bodyLarge)
                        Text("${cacheSize / 1024} KB")
                    }
                    Text(
                        "包含页面图片与临时上传文件；清理不会影响云端内容。",
                        style = MaterialTheme.typography.labelSmall
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            clearing = true
                            ImageProcessor.cleanup(context)
                            context.cacheDir.deleteRecursively()
                            clearing = false
                            refresh()
                        },
                        enabled = !clearing
                    ) { Text(if (clearing) "清理中…" else "清理缓存") }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("转换完成通知", style = MaterialTheme.typography.bodyLarge)
                        Text("默认开启（PRD FR-11）", style = MaterialTheme.typography.labelSmall)
                    }
                    Switch(checked = notifyEnabled, onCheckedChange = { notifyEnabled = it })
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("关于", style = MaterialTheme.typography.titleMedium)
                    Text("拾页笔记 GleanNote 0.1.0", style = MaterialTheme.typography.bodySmall)
                    Text("课程项目 · 客户端 Kotlin + Jetpack Compose", style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { legalDialog = "用户协议" }) { Text("用户协议") }
                        TextButton(onClick = { legalDialog = "隐私政策" }) { Text("隐私政策") }
                        TextButton(onClick = { legalDialog = "意见反馈" }) { Text("意见反馈") }
                    }
                }
            }

            TextButton(onClick = { confirmLogout = true }, modifier = Modifier.fillMaxWidth()) {
                Text("退出登录", color = MaterialTheme.colorScheme.error)
            }
            TextButton(onClick = { deleteAccountOpen = true }, modifier = Modifier.fillMaxWidth()) {
                Text("注销账号", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
            }
        }
    }

    if (renameOpen) {
        TextInputDialog(
            title = "修改昵称",
            label = "昵称",
            initial = user?.nickname.orEmpty(),
            maxLength = 20,
            onConfirm = { name ->
                scope.launch {
                    when (val r = repo.updateNickname(name)) {
                        is AppResult.Success -> { user = r.data; renameOpen = false }
                        is AppResult.Failure -> renameOpen = false
                    }
                }
            },
            onDismiss = { renameOpen = false }
        )
    }

    legalDialog?.let { which ->
        AlertDialog(
            onDismissRequest = { legalDialog = null },
            title = { Text(which) },
            text = {
                Text(
                    when (which) {
                        "意见反馈" -> "课程项目阶段：请通过课程渠道反馈问题，或发送邮件至 feedback@gleannote.example。"
                        "隐私政策" -> "仅收集邮箱、昵称与你上传的页面图片；图片用于转换为 Markdown。注销账号将物理删除全部数据。"
                        else -> "本应用为课程项目演示用途，注册即表示同意仅将数据用于本课程的功能验证。"
                    },
                    style = MaterialTheme.typography.bodySmall
                )
            },
            confirmButton = { TextButton(onClick = { legalDialog = null }) { Text("知道了") } }
        )
    }

    if (deleteAccountOpen) {
        var code by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { deleteAccountOpen = false },
            title = { Text("注销账号") },
            text = {
                Column {
                    Text(
                        "注销将立即删除账号与全部笔记，且无法恢复。确认后本机数据也会被清除。",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it },
                        label = { Text("邮箱验证码") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        when (val r = repo.deleteAccount(code.trim())) {
                            is AppResult.Success -> {
                                deleteAccountOpen = false
                                nav.navigate(Routes.LOGIN) { popUpTo(0) { inclusive = true } }
                            }
                            is AppResult.Failure -> deleteAccountOpen = false
                        }
                    }
                }) { Text("确认注销", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteAccountOpen = false }) { Text("取消") } }
        )
    }

    if (confirmLogout) {
        ConfirmDialog(
            title = "退出登录",
            text = if (pending > 0) "有 $pending 项内容尚未同步，退出后将丢失。确定退出？" else "退出后将清除本机笔记缓存。",
            onConfirm = {
                confirmLogout = false
                scope.launch {
                    repo.logout()
                    nav.navigate(Routes.LOGIN) { popUpTo(0) { inclusive = true } }
                }
            },
            onDismiss = { confirmLogout = false }
        )
    }
}

/* 邮箱脱敏：保留首字符与域名（PRD FR-11）。 */
private fun maskEmail(email: String): String {
    val at = email.lastIndexOf('@')
    if (at <= 0) return email
    val head = email.take(1)
    return "$head***${email.substring(at)}"
}

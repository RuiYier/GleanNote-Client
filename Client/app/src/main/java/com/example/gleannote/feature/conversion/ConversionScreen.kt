package com.example.gleannote.feature.conversion

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import com.example.gleannote.core.contract.Limits
import com.example.gleannote.core.contract.Routes
import com.example.gleannote.core.sync.ConversionWatchService
import com.example.gleannote.core.ui.GleanTopBar
import kotlinx.coroutines.launch

/*
 * 转换进度：轮询由前台服务承担并写入 Room，本页只观察 Flow（主文档 6.12）。
 * 客户端 5 分钟只做提示，不改写任务状态；终态后再拉笔记详情（PRD FR-05）。
 */
@Composable
fun ConversionScreen(nav: NavHostController, repo: GleanNoteRepository, noteId: String?) {
    if (noteId == null) {
        Column(Modifier.fillMaxSize().padding(24.dp)) { Text("缺少笔记参数") }
        return
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val current by repo.observeJob(noteId).collectAsState(initial = null)
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(noteId) {
        ConversionWatchService.start(context, noteId)
        repo.fetchJob(noteId)
    }

    LaunchedEffect(current?.state) {
        if (current?.state == "SUCCEEDED") {
            repo.getNote(noteId)
            nav.navigate(Routes.editor(noteId)) {
                popUpTo(Routes.CONVERSION) { inclusive = true }
            }
        }
    }

    Scaffold(topBar = { GleanTopBar("转换进度", onBack = { nav.popBackStack() }) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when (current?.state) {
                "QUEUED" -> {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text("排队中…", style = MaterialTheme.typography.titleMedium)
                }
                "RUNNING" -> {
                    val phase = when (current?.phase) {
                        "RECOGNIZING" -> "正在识别第 ${current?.processedPages?.coerceAtLeast(1)} / ${current?.totalPages} 页"
                        "GENERATING" -> "正在生成 Markdown"
                        else -> "处理中…"
                    }
                    Text(phase, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    val progress = if ((current?.totalPages ?: 0) > 0) {
                        (current!!.processedPages.toFloat() / current!!.totalPages)
                    } else 0.2f
                    LinearProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${current?.processedPages ?: 0} / ${current?.totalPages ?: 0} 页",
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                "FAILED" -> {
                    Text("转换失败", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(8.dp))
                    Text(current?.failureUserMessage ?: failureText(current?.failureCode))
                    Spacer(Modifier.height(8.dp))
                    Text("失败原因：${current?.failureCode ?: "UNKNOWN"}", style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = {
                        scope.launch {
                            when (val r = repo.retryJob(noteId)) {
                                is AppResult.Success -> {
                                    error = null
                                    ConversionWatchService.start(context, noteId)
                                }
                                is AppResult.Failure -> error = r.error.userMessage
                            }
                        }
                    }) { Text("原地重试（复用已上传图片）") }
                }
                "CANCELED" -> {
                    Text("已取消", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = {
                        scope.launch {
                            when (val r = repo.retryJob(noteId)) {
                                is AppResult.Success -> ConversionWatchService.start(context, noteId)
                                is AppResult.Failure -> error = r.error.userMessage
                            }
                        }
                    }) { Text("重新转换") }
                }
                else -> {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text("正在获取任务状态…")
                }
            }

            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }

            if (current?.state == "QUEUED" || current?.state == "RUNNING") {
                Spacer(Modifier.height(24.dp))
                OutlinedButton(onClick = {
                    scope.launch {
                        when (val r = repo.cancelJob(noteId)) {
                            is AppResult.Success -> Unit
                            is AppResult.Failure -> error = r.error.userMessage
                        }
                    }
                }) { Text("取消转换") }
                Spacer(Modifier.height(8.dp))
                Text(
                    "超过 ${Limits.CONVERSION_TIMEOUT_MS / 60000} 分钟仍未完成可取消后重试",
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

private fun failureText(code: String?): String = when (code) {
    "PROVIDER_UNAVAILABLE" -> "当前使用人数较多，请稍后重试"
    "IMAGE_UNREADABLE" -> "部分页面不清晰，建议重拍后重试"
    "CONTENT_UNSUPPORTED" -> "该内容暂不支持转换"
    "TIMEOUT", "CONVERSION_TIMEOUT" -> "转换超时，请重试"
    "EMPTY_RESULT" -> "未能识别到可用的文字内容"
    "NOTE_MODIFIED" -> "笔记在转换期间被修改，请重试"
    else -> "转换失败，请重试"
}

package com.example.gleannote.feature.p1

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.example.gleannote.core.contract.ExportJob
import com.example.gleannote.core.contract.GleanNoteRepository
import com.example.gleannote.core.contract.NavTarget
import com.example.gleannote.core.contract.Limits
import com.example.gleannote.core.contract.Routes
import com.example.gleannote.core.contract.SearchHit
import com.example.gleannote.core.contract.ShareLink
import com.example.gleannote.core.contract.ImportJob
import com.example.gleannote.core.contract.VersionDetail
import com.example.gleannote.core.contract.Tag
import com.example.gleannote.core.contract.UploadRequest
import com.example.gleannote.core.contract.VersionMeta
import com.example.gleannote.core.markdown.MarkdownRenderer
import com.example.gleannote.core.media.ImageProcessor
import com.example.gleannote.core.ui.ConfirmDialog
import com.example.gleannote.core.ui.EmptyState
import com.example.gleannote.core.ui.FolderPickerDialog
import com.example.gleannote.core.ui.TagPickerDialog
import androidx.compose.material3.AssistChip
import com.example.gleannote.core.ui.GleanTopBar
import com.example.gleannote.core.ui.TextInputDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.example.gleannote.core.common.TimeFormat

/* P1 功能：搜索、标签、版本、分享、导出、导入、追加页面。对应 FR-12~FR-18。 */

@Composable
fun SearchScreen(nav: NavHostController, repo: GleanNoteRepository) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SearchHit>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var folderFilter by remember { mutableStateOf<Pair<String, String>?>(null) } // id to 名称
    var tagFilter by remember { mutableStateOf<List<Tag>>(emptyList()) }
    var folderPickerOpen by remember { mutableStateOf(false) }
    var tagPickerOpen by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf(loadSearchHistory(context)) }
    val scope = rememberCoroutineScope()

    fun remember(keyword: String) {
        if (keyword.isBlank()) return
        history = (listOf(keyword) + history.filterNot { it == keyword }).take(10)
        saveSearchHistory(context, history)
    }

    /* 300ms 防抖自动搜索（PRD FR-12）；条件变化也重新检索。 */
    LaunchedEffect(query, folderFilter, tagFilter) {
        if (query.isBlank()) {
            results = emptyList()
            error = null
            return@LaunchedEffect
        }
        delay(300)
        loading = true
        when (val r = repo.search(query.trim(), folderFilter?.first, tagFilter.map { it.id })) {
            is AppResult.Success -> { results = r.data.items; error = null; remember(query.trim()) }
            is AppResult.Failure -> error = r.error.userMessage
        }
        loading = false
    }

    Scaffold(topBar = { GleanTopBar("搜索", onBack = { nav.popBackStack() }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = { if (it.length <= 100) query = it },
                label = { Text("关键词（1-100 字符）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(12.dp)
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AssistChip(
                    onClick = { folderPickerOpen = true },
                    label = { Text(folderFilter?.second ?: "全部目录") }
                )
                AssistChip(
                    onClick = { tagPickerOpen = true },
                    label = { Text(if (tagFilter.isEmpty()) "全部标签" else tagFilter.joinToString("+") { it.name }) }
                )
                if (folderFilter != null || tagFilter.isNotEmpty()) {
                    TextButton(onClick = { folderFilter = null; tagFilter = emptyList() }) { Text("清除筛选") }
                }
            }

            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                error != null -> Text(error!!, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
                query.isBlank() -> Column(Modifier.padding(16.dp)) {
                    if (history.isEmpty()) {
                        Text("输入关键词开始搜索", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("搜索历史", style = MaterialTheme.typography.titleSmall)
                            TextButton(onClick = {
                                history = emptyList(); saveSearchHistory(context, history)
                            }) { Text("清空") }
                        }
                        history.forEach { item ->
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    item,
                                    Modifier.weight(1f).clickable { query = item }.padding(vertical = 10.dp)
                                )
                                TextButton(onClick = {
                                    history = history.filterNot { it == item }
                                    saveSearchHistory(context, history)
                                }) { Text("删除") }
                            }
                        }
                    }
                }
                results.isEmpty() -> EmptyState("没有找到相关笔记")
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(results, key = { it.noteId }) { hit ->
                        Card(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
                                .clickable { nav.navigate(Routes.editor(hit.noteId)) }
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(hit.title.ifBlank { "未命名笔记" }, style = MaterialTheme.typography.bodyLarge)
                                Text(MarkdownRenderer.plainText(hit.snippet), style = MaterialTheme.typography.bodySmall)
                                Text(
                                    "${hit.folderPath} · ${TimeFormat.date(hit.updatedAt)}",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (folderPickerOpen) {
        FolderPickerDialog(
            repo = repo,
            title = "按目录筛选",
            onPick = { id ->
                folderPickerOpen = false
                folderFilter = if (id == null) null else id to "指定目录"
            },
            onDismiss = { folderPickerOpen = false }
        )
    }

    if (tagPickerOpen) {
        TagPickerDialog(
            repo = repo,
            selected = tagFilter,
            onConfirm = { picked -> tagPickerOpen = false; tagFilter = picked },
            onDismiss = { tagPickerOpen = false }
        )
    }
}

/* 搜索历史落 SharedPreferences：仅本机、最多 10 条（PRD FR-12）。 */
private const val HISTORY_PREF = "gleannote_search_history"

private fun loadSearchHistory(context: Context): List<String> {
    val raw = context.getSharedPreferences(HISTORY_PREF, Context.MODE_PRIVATE).getString("items", "") ?: ""
    return raw.split("\n").filter { it.isNotBlank() }
}

private fun saveSearchHistory(context: Context, items: List<String>) {
    context.getSharedPreferences(HISTORY_PREF, Context.MODE_PRIVATE)
        .edit().putString("items", items.joinToString("\n")).apply()
}

@Composable
fun TagsScreen(nav: NavHostController, repo: GleanNoteRepository) {
    var tags by remember { mutableStateOf<List<Tag>>(emptyList()) }
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<Tag?>(null) }
    var pendingRename by remember { mutableStateOf<Tag?>(null) }
    val scope = rememberCoroutineScope()

    fun reload() {
        scope.launch { (repo.tags() as? AppResult.Success)?.let { tags = it.data } }
    }
    LaunchedEffect(Unit) { reload() }

    Scaffold(topBar = { GleanTopBar("标签", onBack = { nav.popBackStack() }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, label = { Text("新标签（1-20 字符）") },
                    singleLine = true, modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.size(8.dp))
                Button(
                    onClick = {
                        scope.launch {
                            when (val r = repo.createTag(name.trim())) {
                                is AppResult.Success -> { name = ""; reload() }
                                is AppResult.Failure -> error = r.error.userMessage
                            }
                        }
                    },
                    enabled = name.isNotBlank()
                ) { Text("新建") }
            }
            error?.let { Text(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error) }
            if (tags.isEmpty()) EmptyState("还没有标签")
            else LazyColumn(Modifier.fillMaxSize()) {
                items(tags, key = { it.id }) { tag ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("${tag.name} · ${tag.noteCount} 篇", style = MaterialTheme.typography.bodyLarge)
                        Row {
                            TextButton(onClick = { pendingRename = tag }) { Text("重命名") }
                            TextButton(onClick = { pendingDelete = tag }) { Text("删除") }
                        }
                    }
                }
            }
        }
    }

    pendingRename?.let { tag ->
        TextInputDialog(
            title = "重命名标签",
            label = "标签名",
            initial = tag.name,
            maxLength = 20,
            onConfirm = { newName ->
                scope.launch {
                    when (val r = repo.renameTag(tag.id, newName)) {
                        is AppResult.Success -> { pendingRename = null; error = null; reload() }
                        is AppResult.Failure -> { pendingRename = null; error = r.error.userMessage }
                    }
                }
            },
            onDismiss = { pendingRename = null }
        )
    }

    pendingDelete?.let { tag ->
        ConfirmDialog(
            title = "删除标签",
            text = "「${tag.name}」关联了 ${tag.noteCount} 篇笔记，删除后将全部解除关联，笔记本身不受影响。",
            onConfirm = {
                pendingDelete = null
                scope.launch { repo.deleteTag(tag.id); reload() }
            },
            onDismiss = { pendingDelete = null }
        )
    }
}

@Composable
fun VersionsScreen(nav: NavHostController, repo: GleanNoteRepository, noteId: String?) {
    if (noteId == null) return
    var versions by remember { mutableStateOf<List<VersionMeta>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var pendingRestore by remember { mutableStateOf<VersionMeta?>(null) }
    var preview by remember { mutableStateOf<VersionDetail?>(null) }
    val scope = rememberCoroutineScope()

    fun reload() {
        scope.launch {
            when (val r = repo.versions(noteId)) {
                is AppResult.Success -> versions = r.data
                is AppResult.Failure -> error = r.error.userMessage
            }
        }
    }
    LaunchedEffect(noteId) { reload() }

    Scaffold(topBar = { GleanTopBar("版本历史", onBack = { nav.popBackStack() }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            error?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
            if (versions.isEmpty()) EmptyState("暂无历史版本")
            else LazyColumn(Modifier.fillMaxSize()) {
                items(versions, key = { it.versionId }) { v ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable {
                                scope.launch {
                                    preview = (repo.versionDetail(noteId, v.versionId) as? AppResult.Success)?.data
                                }
                            }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(v.source, style = MaterialTheme.typography.bodyLarge)
                            Text("${v.charCount} 字 · ${TimeFormat.relative(v.createdAt)}", style = MaterialTheme.typography.labelSmall)
                        }
                        TextButton(onClick = { pendingRestore = v }) { Text("恢复") }
                    }
                }
            }
        }
    }

    pendingRestore?.let { v ->
        ConfirmDialog(
            title = "恢复版本",
            text = "将用该版本正文替换当前内容，并生成一条新的恢复版本。",
            onConfirm = {
                pendingRestore = null
                scope.launch {
                    when (val r = repo.restoreVersion(noteId, v.versionId)) {
                        is AppResult.Success -> nav.popBackStack()
                        is AppResult.Failure -> error = r.error.userMessage
                    }
                }
            },
            onDismiss = { pendingRestore = null }
        )
    }

    preview?.let { detail ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { preview = null },
            title = { Text("版本预览（只读）") },
            text = {
                Column(Modifier.height(320.dp).verticalScroll(rememberScrollState())) {
                    Text(detail.content.ifBlank { "该版本正文为空" }, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { preview = null }) { Text("关闭") } }
        )
    }
}

@Composable
fun ShareScreen(nav: NavHostController, repo: GleanNoteRepository, noteId: String?) {
    if (noteId == null) return
    val context = LocalContext.current
    var link by remember { mutableStateOf<ShareLink?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(noteId) {
        when (val r = repo.shareLink(noteId)) {
            is AppResult.Success -> link = r.data
            is AppResult.Failure -> error = r.error.userMessage
        }
    }

    Scaffold(topBar = { GleanTopBar("分享", onBack = { nav.popBackStack() }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("开启后，任何拿到链接的人都可以在浏览器中查看这篇笔记。", style = MaterialTheme.typography.bodyMedium)
            link?.let { l ->
                Text(if (l.enabled) "已开启分享" else "未开启分享", style = MaterialTheme.typography.titleMedium)
                if (l.enabled) {
                    Text(l.url, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("share", l.url))
                        }) { Text("复制链接") }
                        OutlinedButton(onClick = {
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"; putExtra(Intent.EXTRA_TEXT, l.url)
                            }
                            context.startActivity(Intent.createChooser(intent, "分享"))
                        }) { Text("系统分享") }
                    }
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    busy = true
                    scope.launch {
                        val r = if (link?.enabled == true) repo.disableShare(noteId) else repo.enableShare(noteId)
                        when (r) {
                            is AppResult.Success -> link = repo.shareLink(noteId).getOrNull() ?: link
                            is AppResult.Failure -> error = r.error.userMessage
                        }
                        busy = false
                    }
                },
                enabled = !busy, modifier = Modifier.fillMaxWidth()
            ) { Text(if (link?.enabled == true) "关闭分享" else "开启分享") }
        }
    }
}

@Composable
fun ExportScreen(nav: NavHostController, repo: GleanNoteRepository, noteId: String?) {
    val context = LocalContext.current
    var format by remember { mutableStateOf("MARKDOWN_ZIP") }
    var job by remember { mutableStateOf<ExportJob?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Scaffold(topBar = { GleanTopBar("导出", onBack = { nav.popBackStack() }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("选择导出格式", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("MARKDOWN_ZIP" to "Markdown ZIP", "PDF" to "PDF").forEach { (v, label) ->
                    OutlinedButton(onClick = { format = v }) {
                        Text(if (format == v) "✓ $label" else label)
                    }
                }
            }
            Text(
                "导出任务由服务端生成，下载地址有效期 1 小时；离线导出需页面图片均已缓存。",
                style = MaterialTheme.typography.labelSmall
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            job?.let {
                Text("状态：${it.state}")
                if (it.downloadUrl.isNotBlank()) {
                    Button(onClick = {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it.downloadUrl)))
                        }
                    }) { Text("打开下载链接") }
                }
            }
            Button(
                onClick = {
                    busy = true
                    scope.launch {
                        when (val r = repo.startExport(noteId, null, format)) {
                            is AppResult.Success -> {
                                var current = r.data
                                job = current
                                for (i in 0 until 30) {
                                    if (current.state == "SUCCEEDED" || current.state == "FAILED") break
                                    delay(2000)
                                    current = (repo.exportStatus(current.exportId) as? AppResult.Success)?.data ?: current
                                    job = current
                                }
                            }
                            is AppResult.Failure -> error = r.error.userMessage
                        }
                        busy = false
                    }
                },
                enabled = !busy, modifier = Modifier.fillMaxWidth()
            ) { Text(if (busy) "导出中…" else "开始导出") }
        }
    }
}

/* 选图 → 预处理 → 三步上传，返回 uploadId 列表；导入与追加共用。 */
private suspend fun uploadAll(
    context: Context,
    repo: GleanNoteRepository,
    images: List<ImageProcessor.ProcessedImage>
): AppResult<List<String>> {
    val requests = images.map { UploadRequest(it.fileName, it.contentType, it.bytes, it.sha256, it.localPath) }
    val sessions = when (val r = repo.uploadImages(requests)) {
        is AppResult.Success -> r.data
        is AppResult.Failure -> return r
    }
    val ids = mutableListOf<String>()
    sessions.forEachIndexed { index, session ->
        val image = images.getOrNull(index) ?: return@forEachIndexed
        (repo.uploadBinary(session, image.localPath) as? AppResult.Failure)?.let { return it }
        when (val done = repo.completeUpload(session.uploadId, image.sha256)) {
            is AppResult.Success -> ids += session.uploadId
            is AppResult.Failure -> return done
        }
    }
    return AppResult.Success(ids)
}

@Composable
fun ImportScreen(nav: NavHostController, repo: GleanNoteRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var images by remember { mutableStateOf<List<ImageProcessor.ProcessedImage>>(emptyList()) }
    var mode by remember { mutableStateOf("PER_IMAGE") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    /* 逐篇状态：下标 → 文案；PRD FR-17 要求逐篇展示且可单独重试。 */
    var statuses by remember { mutableStateOf<Map<Int, String>>(emptyMap()) }
    var uploadIds by remember { mutableStateOf<List<String>>(emptyList()) }
    var cancelled by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(Limits.MAX_PAGES_PER_NOTE)
    ) { uris ->
        scope.launch {
            busy = true
            images = uris.mapNotNull { runCatching { ImageProcessor.process(context, it) }.getOrNull() }
            statuses = emptyMap()
            uploadIds = emptyList()
            message = null
            busy = false
        }
    }

    fun setStatus(index: Int, text: String) {
        statuses = statuses + (index to text)
    }

    /* 单篇提交：创建草稿并等待转换终态。合并模式一次提交多张。 */
    suspend fun submit(folderId: String, indices: List<Int>) {
        val ids = indices.mapNotNull { uploadIds.getOrNull(it) }
        if (ids.isEmpty()) return
        indices.forEach { setStatus(it, "转换中…") }
        when (val created = repo.createFromPages(folderId, ids, null)) {
            is AppResult.Success -> {
                val noteId = created.data.note.id
                var job = created.data.conversionJob
                for (i in 0 until 150) {
                    if (job.state == "SUCCEEDED" || job.state == "FAILED" || job.state == "CANCELED") break
                    delay(2000)
                    job = (repo.fetchJob(noteId) as? AppResult.Success)?.data ?: job
                }
                val text = when (job.state) {
                    "SUCCEEDED" -> "成功"
                    "CANCELED" -> "已取消"
                    else -> "失败：${job.failureUserMessage ?: "转换未完成"}"
                }
                indices.forEach { setStatus(it, text) }
            }
            is AppResult.Failure -> indices.forEach { setStatus(it, "失败：${created.error.userMessage}") }
        }
    }

    fun start() {
        if (mode == "MERGE" && images.size > Limits.MAX_IMAGES_PER_BATCH) {
            message = "合并模式单篇最多 ${Limits.MAX_IMAGES_PER_BATCH} 张，请改用每图一篇"
            return
        }
        busy = true
        cancelled = false
        message = "正在上传…"
        statuses = images.indices.associateWith { "待处理" }
        scope.launch {
            /* 目标目录默认取笔记库当前目录（PRD FR-17）。 */
            val folderId = NavTarget.take()
                ?: (repo.refreshFolders(null) as? AppResult.Success)?.data?.items?.firstOrNull()?.id
            NavTarget.set(null)
            if (folderId == null) {
                message = "未找到根目录"
                busy = false
                return@launch
            }
            when (val uploaded = uploadAll(context, repo, images)) {
                is AppResult.Success -> {
                    uploadIds = uploaded.data
                    message = null
                    if (mode == "MERGE") {
                        submit(folderId, images.indices.toList())
                    } else {
                        for (index in images.indices) {
                            if (cancelled) {
                                setStatus(index, "已取消")
                                continue
                            }
                            submit(folderId, listOf(index))
                        }
                    }
                    val ok = statuses.count { it.value == "成功" }
                    message = if (cancelled) "已取消，已完成 $ok 篇" else "导入结束：成功 $ok / ${images.size} 篇"
                }
                is AppResult.Failure -> message = uploaded.error.userMessage
            }
            busy = false
        }
    }

    Scaffold(topBar = { GleanTopBar("批量导入", onBack = { nav.popBackStack() }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("已选 ${images.size} 张（最多 ${Limits.MAX_PAGES_PER_NOTE}）")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("PER_IMAGE" to "每图一篇", "MERGE" to "合并一篇").forEach { (v, label) ->
                    OutlinedButton(onClick = { mode = v }, enabled = !busy) {
                        Text(if (mode == v) "✓ $label" else label)
                    }
                }
            }
            OutlinedButton(
                onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                enabled = !busy
            ) { Text("选择图片") }
            message?.let { Text(it) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { start() }, enabled = !busy && images.isNotEmpty()) {
                    Text(if (busy) "处理中…" else "开始导入")
                }
                if (busy) {
                    OutlinedButton(onClick = { cancelled = true }) { Text("取消未开始项") }
                }
            }
            if (statuses.isNotEmpty()) {
                val done = statuses.count { it.value == "成功" }
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { done.toFloat() / statuses.size.coerceAtLeast(1) },
                    modifier = Modifier.fillMaxWidth()
                )
                LazyColumn(Modifier.weight(1f)) {
                    items(images.indices.toList(), key = { it }) { index ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("第 ${index + 1} 张", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            Text(
                                statuses[index] ?: "待处理",
                                style = MaterialTheme.typography.labelMedium,
                                color = if ((statuses[index] ?: "").startsWith("失败")) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if ((statuses[index] ?: "").startsWith("失败") && !busy) {
                                TextButton(onClick = {
                                    busy = true
                                    scope.launch {
                                        val folderId = (repo.refreshFolders(null) as? AppResult.Success)
                                            ?.data?.items?.firstOrNull()?.id
                                        if (folderId != null) submit(folderId, listOf(index))
                                        busy = false
                                    }
                                }) { Text("重试") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AppendScreen(nav: NavHostController, repo: GleanNoteRepository, noteId: String?) {
    if (noteId == null) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var images by remember { mutableStateOf<List<ImageProcessor.ProcessedImage>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(
        activityContractPickMultiple()
    ) { uris ->
        scope.launch {
            busy = true
            images = uris.mapNotNull { runCatching { ImageProcessor.process(context, it) }.getOrNull() }
            busy = false
        }
    }

    Scaffold(topBar = { GleanTopBar("追加页面", onBack = { nav.popBackStack() }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("追加结果只写入正文末尾，不覆盖已有内容（PRD FR-18）。", style = MaterialTheme.typography.bodyMedium)
            Text("已选 ${images.size} 张（单次 ≤${Limits.MAX_IMAGES_PER_BATCH}，累计 ≤${Limits.MAX_PAGES_PER_NOTE}）")
            OutlinedButton(
                onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                enabled = !busy
            ) { Text("选择图片") }
            message?.let { Text(it) }
            Button(
                onClick = {
                    busy = true
                    message = "正在上传…"
                    scope.launch {
                        when (val uploaded = uploadAll(context, repo, images)) {
                            is AppResult.Success -> when (val r = repo.appendPages(noteId, uploaded.data)) {
                                is AppResult.Success -> {
                                    message = "已提交追加转换"
                                    nav.navigate(Routes.conversion(noteId))
                                }
                                is AppResult.Failure -> message = r.error.userMessage
                            }
                            is AppResult.Failure -> message = uploaded.error.userMessage
                        }
                        busy = false
                    }
                },
                enabled = !busy && images.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (busy) "处理中…" else "追加并转换") }
        }
    }
}

/* ActivityResultContracts.PickMultipleVisualMedia 需要最大数量参数，这里统一为 20。 */
private fun activityContractPickMultiple() =
    ActivityResultContracts.PickMultipleVisualMedia(Limits.MAX_IMAGES_PER_BATCH)

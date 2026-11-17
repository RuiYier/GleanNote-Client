package com.example.gleannote.feature.library

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
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.example.gleannote.core.contract.AppResult
import com.example.gleannote.core.contract.Folder
import com.example.gleannote.core.contract.GleanNoteRepository
import com.example.gleannote.core.contract.Limits
import com.example.gleannote.core.contract.NavTarget
import com.example.gleannote.core.contract.Note
import com.example.gleannote.core.contract.Routes
import com.example.gleannote.core.ui.ConfirmDialog
import com.example.gleannote.core.ui.EmptyState
import com.example.gleannote.core.ui.ErrorState
import com.example.gleannote.core.ui.FolderPickerDialog
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.ui.platform.LocalContext
import com.example.gleannote.core.ui.OfflineBanner
import com.example.gleannote.core.ui.GleanTopBar
import com.example.gleannote.core.ui.LoadingSkeleton
import com.example.gleannote.core.ui.StateChip
import com.example.gleannote.core.ui.SyncStatusBar
import com.example.gleannote.core.ui.TextInputDialog
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import androidx.compose.foundation.background
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import com.example.gleannote.core.contract.ExportJob
import com.example.gleannote.core.common.TimeFormat

/*
 * 笔记库首页：目录树、面包屑、笔记列表、排序、状态标签、同步状态与新建入口。
 * 对应 PRD FR-02 / FR-07、GN-CLIENT-004 SCR-02。
 * 目录与笔记均提供「重命名 / 移动到 / 删除」，删除目录时提示影响面（FR-02、FR-07）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(nav: NavHostController, repo: GleanNoteRepository) {
    var rootId by remember { mutableStateOf<String?>(null) }
    var currentId by remember { mutableStateOf<String?>(null) }
    var currentDepth by remember { mutableStateOf(1) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var orderBy by remember { mutableStateOf("updateTime desc") }
    var sortOpen by remember { mutableStateOf(false) }
    var createFolderOpen by remember { mutableStateOf(false) }
    var currentPath by remember { mutableStateOf("我的笔记") }
    val pending by repo.pendingCount().collectAsState(initial = 0)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var offline by remember { mutableStateOf(false) }

    /* 离线标识：每 3 秒探测一次网络能力（PRD FR-07「离线时展示离线模式标识」）。 */
    LaunchedEffect(Unit) {
        while (true) {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            offline = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) != true
            kotlinx.coroutines.delay(3000)
        }
    }

    // 目录与笔记的行内操作
    var menuFolder by remember { mutableStateOf<Folder?>(null) }
    var menuNote by remember { mutableStateOf<Note?>(null) }
    var renameFolderTarget by remember { mutableStateOf<Folder?>(null) }
    var renameNoteTarget by remember { mutableStateOf<Note?>(null) }
    var moveFolderTarget by remember { mutableStateOf<Folder?>(null) }
    var moveNoteTarget by remember { mutableStateOf<Note?>(null) }
    var deleteFolderTarget by remember { mutableStateOf<Folder?>(null) }
    var deleteNoteTarget by remember { mutableStateOf<Note?>(null) }
    /* 目录导出（PRD FR-14）：服务端打包为 Markdown ZIP，客户端只轮询与展示链接。 */
    var exportTarget by remember { mutableStateOf<Folder?>(null) }
    var exportJob by remember { mutableStateOf<ExportJob?>(null) }
    var exportRunning by remember { mutableStateOf(false) }
    var exportError by remember { mutableStateOf<String?>(null) }

    val rootFolders by remember { repo.folders(null) }.collectAsState(initial = emptyList())
    LaunchedEffect(rootFolders) {
        if (rootId == null && rootFolders.isNotEmpty()) {
            rootId = rootFolders.first().id
            currentId = rootFolders.first().id
            currentDepth = rootFolders.first().depth
        }
    }

    LaunchedEffect(Unit) {
        loading = true
        when (val r = repo.refreshFolders(null)) {
            is AppResult.Success -> error = null
            is AppResult.Failure -> error = r.error.userMessage
        }
        loading = false
        repo.sync()
    }

    LaunchedEffect(currentId, orderBy) {
        val id = currentId ?: return@LaunchedEffect
        /* 子目录的 noteCount 由服务端维护，进入目录时必须一并刷新，否则计数会停在旧值。 */
        repo.refreshFolders(id)
        repo.refreshNotes(id, orderBy = orderBy)
        currentPath = (repo.folderPath(id) as? AppResult.Success)?.data?.displayPath ?: "我的笔记"
    }

    val folders by remember(currentId) { repo.folders(currentId) }.collectAsState(initial = emptyList())
    val notes by remember(currentId) { repo.notes(currentId ?: "") }.collectAsState(initial = emptyList())

    /* 取图与导入默认落在当前目录（PRD FR-03 / FR-17）。 */
    fun goCapture() {
        NavTarget.set(currentId)
        nav.navigate(Routes.CAPTURE)
    }

    fun goImport() {
        NavTarget.set(currentId)
        nav.navigate(Routes.IMPORT)
    }

    Scaffold(
        topBar = {
            GleanTopBar(
                title = if (currentId == rootId) "我的笔记" else currentPath,
                actions = {
                    TextButton(onClick = { nav.navigate(Routes.SEARCH) }) { Text("搜索") }
                    TextButton(onClick = { nav.navigate(Routes.TAGS) }) { Text("标签") }
                    TextButton(onClick = { nav.navigate(Routes.TRASH) }) { Text("回收站") }
                    TextButton(onClick = { nav.navigate(Routes.SETTINGS) }) { Text("设置") }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { goCapture() }) { Text("拍照创建") }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (offline) OfflineBanner()
            SyncStatusBar(pendingCount = pending, syncing = false, error = null, onRetry = {
                scope.launch { repo.sync() }
            })

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row {
                    if (currentId != rootId && rootId != null) {
                        TextButton(onClick = { currentId = rootId; currentDepth = 1 }) { Text("← 我的笔记") }
                    }
                    TextButton(
                        onClick = {
                            if (currentDepth >= Limits.MAX_DEPTH) error = "最多支持 ${Limits.MAX_DEPTH} 层目录"
                            else createFolderOpen = true
                        }
                    ) { Text("新建目录") }
                    TextButton(onClick = {
                        scope.launch {
                            val id = currentId ?: return@launch
                            (repo.createNote(id) as? AppResult.Success)?.let { nav.navigate(Routes.editor(it.data.id)) }
                        }
                    }) { Text("新建笔记") }
                    TextButton(onClick = { goImport() }) { Text("批量导入") }
                }
                Box {
                    TextButton(onClick = { sortOpen = true }) { Text("排序") }
                    DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                        listOf("updateTime desc" to "最近更新", "createTime desc" to "最近创建", "title asc" to "标题").forEach { (v, label) ->
                            DropdownMenuItem(text = { Text(label) }, onClick = { orderBy = v; sortOpen = false })
                        }
                    }
                }
            }

            when {
                loading -> LoadingSkeleton()
                error != null -> ErrorState(error!!) { scope.launch { repo.refreshFolders(null) } }
                folders.isEmpty() && notes.isEmpty() -> EmptyState(
                    "这个目录还没有内容", "拍摄第一页纸质材料"
                ) { goCapture() }
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(folders, key = { "f-${it.id}" }) { folder ->
                        FolderRow(
                            folder = folder,
                            onClick = { currentId = folder.id; currentDepth = folder.depth },
                            onMore = { menuFolder = folder }
                        )
                    }
                    items(notes, key = { it.id }) { note ->
                        NoteRow(
                            note = note,
                            thumbUrl = repo.thumbnailFor(note.id),
                            onClick = {
                                when (note.state) {
                                    "DRAFT", "FAILED", "CANCELED" -> nav.navigate(Routes.conversion(note.id))
                                    else -> nav.navigate(Routes.editor(note.id))
                                }
                            },
                            onMore = { menuNote = note }
                        )
                    }
                }
            }
        }
    }

    /* 导出：提交后每秒轮询一次，最长 60 秒；链接 1 小时内有效（API.md 2.9）。 */
    LaunchedEffect(exportTarget) {
        val folder = exportTarget ?: return@LaunchedEffect
        exportRunning = true
        exportJob = null
        exportError = null
        when (val r = repo.startExport(null, folder.id, "MARKDOWN_ZIP")) {
            is AppResult.Success -> {
                var job = r.data
                exportJob = job
                for (i in 0 until 60) {
                    if (job.state == "SUCCEEDED" || job.state == "FAILED") break
                    kotlinx.coroutines.delay(1000)
                    job = (repo.exportStatus(job.exportId) as? AppResult.Success)?.data ?: job
                    exportJob = job
                }
                if (job.state != "SUCCEEDED") {
                    exportError = job.failureCode.ifBlank { "导出未完成，请稍后重试" }
                }
            }
            is AppResult.Failure -> exportError = r.error.userMessage
        }
        exportRunning = false
    }

    /* ---------- 目录操作 ---------- */

    menuFolder?.let { folder ->
        DropdownMenu(expanded = true, onDismissRequest = { menuFolder = null }) {
            DropdownMenuItem(text = { Text("新建子目录") }, onClick = {
                menuFolder = null
                if (folder.depth >= Limits.MAX_DEPTH) error = "最多支持 ${Limits.MAX_DEPTH} 层目录"
                else { currentId = folder.id; currentDepth = folder.depth; createFolderOpen = true }
            })
            DropdownMenuItem(text = { Text("重命名") }, onClick = { menuFolder = null; renameFolderTarget = folder })
            DropdownMenuItem(text = { Text("移动到") }, onClick = { menuFolder = null; moveFolderTarget = folder })
            DropdownMenuItem(text = { Text("导出该目录") }, onClick = { menuFolder = null; exportTarget = folder })
            DropdownMenuItem(text = { Text("删除") }, onClick = { menuFolder = null; deleteFolderTarget = folder })
        }
    }

    renameFolderTarget?.let { folder ->
        TextInputDialog(
            title = "重命名目录",
            label = "目录名称",
            initial = folder.name,
            maxLength = Limits.MAX_FOLDER_NAME,
            onConfirm = { name ->
                scope.launch {
                    when (val r = repo.renameFolder(folder.id, name, folder.revision)) {
                        is AppResult.Success -> { renameFolderTarget = null; error = null; repo.refreshFolders(currentId) }
                        is AppResult.Failure -> error = r.error.userMessage
                    }
                }
            },
            onDismiss = { renameFolderTarget = null }
        )
    }

    moveFolderTarget?.let { folder ->
        FolderPickerDialog(
            repo = repo,
            title = "移动「${folder.name}」到",
            excludeSubtreeOf = folder.id,
            onPick = { target ->
                scope.launch {
                    when (val r = repo.moveFolder(folder.id, target)) {
                        is AppResult.Success -> { moveFolderTarget = null; error = null; repo.refreshFolders(currentId) }
                        is AppResult.Failure -> error = r.error.userMessage
                    }
                }
            },
            onDismiss = { moveFolderTarget = null }
        )
    }

    exportTarget?.let { folder ->
        AlertDialog(
            onDismissRequest = { if (!exportRunning) { exportTarget = null; exportJob = null } },
            title = { Text("导出「${folder.name}」") },
            text = {
                Column {
                    when {
                        exportRunning -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("正在打包为 Markdown ZIP…", style = MaterialTheme.typography.bodySmall)
                        }
                        exportError != null -> Text(
                            "导出失败：$exportError",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                        else -> {
                            Text("导出完成，下载链接 1 小时内有效。", style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                exportJob?.downloadUrl.orEmpty(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            },
            confirmButton = {
                if (!exportRunning && exportError == null && !exportJob?.downloadUrl.isNullOrBlank()) {
                    TextButton(onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(
                            android.content.ClipData.newPlainText("export", exportJob?.downloadUrl.orEmpty())
                        )
                    }) { Text("复制链接") }
                } else {
                    TextButton(onClick = { if (!exportRunning) { exportTarget = null; exportJob = null } }) {
                        Text("关闭")
                    }
                }
            },
            dismissButton = {
                if (!exportRunning && exportError == null && !exportJob?.downloadUrl.isNullOrBlank()) {
                    TextButton(onClick = {
                        runCatching {
                            context.startActivity(
                                android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse(exportJob?.downloadUrl)
                                )
                            )
                        }
                        exportTarget = null
                        exportJob = null
                    }) { Text("打开") }
                }
            }
        )
    }

    deleteFolderTarget?.let { folder ->
        ConfirmDialog(
            title = "删除目录",
            text = "「${folder.name}」及其 ${folder.childCount} 个子目录、${folder.noteCount} 篇笔记将一并移入回收站，30 天内可恢复。",
            onConfirm = {
                scope.launch {
                    when (val r = repo.deleteFolder(folder.id)) {
                        is AppResult.Success -> { deleteFolderTarget = null; repo.refreshFolders(currentId) }
                        is AppResult.Failure -> { deleteFolderTarget = null; error = r.error.userMessage }
                    }
                }
            },
            onDismiss = { deleteFolderTarget = null }
        )
    }

    if (createFolderOpen) {
        TextInputDialog(
            title = "新建目录",
            label = "目录名称",
            initial = "",
            maxLength = Limits.MAX_FOLDER_NAME,
            confirmLabel = "创建",
            onConfirm = { name ->
                scope.launch {
                    when (val r = repo.createFolder(currentId, name)) {
                        is AppResult.Success -> { createFolderOpen = false; error = null; repo.refreshFolders(currentId) }
                        is AppResult.Failure -> error = r.error.userMessage
                    }
                }
            },
            onDismiss = { createFolderOpen = false }
        )
    }

    /* ---------- 笔记操作 ---------- */

    menuNote?.let { note ->
        DropdownMenu(expanded = true, onDismissRequest = { menuNote = null }) {
            DropdownMenuItem(text = { Text("重命名") }, onClick = { menuNote = null; renameNoteTarget = note })
            DropdownMenuItem(text = { Text("移动到") }, onClick = { menuNote = null; moveNoteTarget = note })
            DropdownMenuItem(text = { Text("删除") }, onClick = { menuNote = null; deleteNoteTarget = note })
        }
    }

    renameNoteTarget?.let { note ->
        TextInputDialog(
            title = "重命名笔记",
            label = "标题",
            initial = note.title,
            maxLength = Limits.MAX_TITLE,
            onConfirm = { title ->
                scope.launch {
                    when (val r = repo.saveNote(note.id, title, null, note.revision, "MANUAL_SAVE")) {
                        is AppResult.Success -> {
                            renameNoteTarget = null
                            error = null
                            repo.refreshNotes(note.folderId, orderBy = orderBy)
                        }
                        is AppResult.Failure -> error = r.error.userMessage
                    }
                }
            },
            onDismiss = { renameNoteTarget = null }
        )
    }

    moveNoteTarget?.let { note ->
        FolderPickerDialog(
            repo = repo,
            title = "移动「${note.title.ifBlank { "未命名笔记" }}」到",
            onPick = { target ->
                scope.launch {
                    val dest = target ?: rootId
                    if (dest == null) {
                        error = "根目录尚未加载"
                        return@launch
                    }
                    when (val r = repo.moveNote(note.id, dest)) {
                        is AppResult.Success -> {
                            moveNoteTarget = null
                            error = null
                            repo.refreshNotes(note.folderId, orderBy = orderBy)
                            repo.refreshNotes(dest, orderBy = orderBy)
                            repo.refreshFolders(currentId)
                        }
                        is AppResult.Failure -> error = r.error.userMessage
                    }
                }
            },
            onDismiss = { moveNoteTarget = null }
        )
    }

    deleteNoteTarget?.let { note ->
        ConfirmDialog(
            title = "删除笔记",
            text = "「${note.title.ifBlank { "未命名笔记" }}」将移入回收站，30 天内可恢复。",
            onConfirm = {
                scope.launch {
                    repo.deleteNote(note.id)
                    deleteNoteTarget = null
                    repo.refreshFolders(currentId)
                }
            },
            onDismiss = { deleteNoteTarget = null }
        )
    }
}

@Composable
private fun FolderRow(folder: Folder, onClick: () -> Unit, onMore: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("▸", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.size(6.dp))
        Column(Modifier.weight(1f)) {
            Text(folder.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${folder.noteCount} 篇笔记 · ${folder.childCount} 个子目录", style = MaterialTheme.typography.labelSmall)
        }
        TextButton(onClick = onMore) { Text("更多") }
    }
}

@Composable
private fun NoteRow(note: Note, thumbUrl: String?, onClick: () -> Unit, onMore: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clickable(onClick = onClick)) {
        Row(
            Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            /*
             * 首图缩略图（PRD FR-07）：服务端给的是短效签名地址，只存内存；
             * 无地址（刚创建、纯手写笔记、缓存已过期）时显示占位块，不影响列表可用性。
             */
            Box(
                Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (!thumbUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = thumbUrl,
                        contentDescription = "首图缩略图",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Text("无图", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        note.title.ifBlank { "未命名笔记" },
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    StateChip(note.state, note.pendingSync)
                    TextButton(onClick = onMore) { Text("更多") }
                }
                Text(
                    "${note.charCount} 字 · ${note.pageCount} 张图 · ${TimeFormat.date(note.updatedAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

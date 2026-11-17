package com.example.gleannote.feature.editor

import androidx.compose.foundation.background
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.example.gleannote.core.contract.AppResult
import com.example.gleannote.core.contract.Draft
import com.example.gleannote.core.contract.GleanNoteRepository
import com.example.gleannote.core.contract.Limits
import com.example.gleannote.core.contract.Note
import com.example.gleannote.core.contract.NotePage
import com.example.gleannote.core.contract.Routes
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import com.example.gleannote.core.markdown.MarkdownRenderer
import com.example.gleannote.core.ui.ConfirmDialog
import com.example.gleannote.core.ui.FolderPickerDialog
import com.example.gleannote.core.ui.TagPickerDialog
import com.example.gleannote.core.ui.TextInputDialog
import com.example.gleannote.core.ui.GleanTopBar
import com.example.gleannote.core.ui.SaveStatusIndicator
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * 笔记页：编辑 / 预览同页切换、2 秒防抖自动保存、revision 冲突提示、草稿恢复。
 * 对应 PRD FR-06 / FR-08、GN-CLIENT-004 SCR-07。
 */
@Composable
fun EditorScreen(nav: NavHostController, repo: GleanNoteRepository, noteId: String?) {
    if (noteId == null) {
        Text("缺少笔记参数", Modifier.padding(24.dp))
        return
    }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var note by remember { mutableStateOf<Note?>(null) }
    var title by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var revision by remember { mutableStateOf(1) }
    var preview by remember { mutableStateOf(false) }
    var saveState by remember { mutableStateOf("SAVED") }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var moreOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var conflictNotice by remember { mutableStateOf(false) }
    var renameOpen by remember { mutableStateOf(false) }
    var moveOpen by remember { mutableStateOf(false) }
    var tagPickerOpen by remember { mutableStateOf(false) }
    /* 撤销/重做：按内容快照栈实现，最多保留 50 步（PRD FR-06 工具栏）。 */
    var history by remember { mutableStateOf(listOf("")) }
    var historyPos by remember { mutableStateOf(0) }
    var suppressHistory by remember { mutableStateOf(false) }

    LaunchedEffect(noteId) {
        when (val r = repo.getNote(noteId)) {
            is AppResult.Success -> {
                note = r.data
                revision = r.data.revision
                val draft = repo.draft(noteId)
                if (draft != null && draft.updatedAt > 0) {
                    title = draft.title
                    content = draft.content
                    revision = draft.baseRevision.takeIf { it > 0 } ?: r.data.revision
                    saveState = "UNSAVED"
                } else {
                    title = r.data.title
                    content = r.data.content
                }
            }
            is AppResult.Failure -> error = r.error.userMessage
        }
        loading = false
    }

    /* 撤销栈：内容稳定 800ms 记一次快照；撤销/重做回填时用 suppressHistory 跳过记录。 */
    LaunchedEffect(content) {
        if (loading) return@LaunchedEffect
        if (suppressHistory) { suppressHistory = false; return@LaunchedEffect }
        delay(800)
        if (history.getOrNull(historyPos) != content) {
            val merged = (history.take(historyPos + 1) + content).takeLast(50)
            history = merged
            historyPos = merged.lastIndex
        }
    }

    /* 2 秒防抖：输入停止后先落本地草稿再提交服务端（PRD FR-06、主文档 6.5）。 */
    LaunchedEffect(title, content) {
        if (loading) return@LaunchedEffect
        saveState = "UNSAVED"
        delay(Limits.AUTOSAVE_DEBOUNCE_MS)
        saveState = "SAVING"
        repo.saveDraft(Draft(noteId, title, content, revision, System.currentTimeMillis()))
        when (val r = repo.saveNote(noteId, title, content, revision, "AUTO_SAVE")) {
            is AppResult.Success -> {
                revision = r.data.note.revision
                repo.clearDraft(noteId)
                if (r.data.conflictCopyId != null) {
                    conflictNotice = true
                    saveState = "SAVED"
                } else {
                    saveState = "SAVED"
                }
            }
            is AppResult.Failure -> {
                saveState = if (r.error.code == "NETWORK_ERROR") "PENDING" else "UNSAVED"
                error = r.error.userMessage
            }
        }
    }

    Scaffold(
        topBar = {
            GleanTopBar(
                title = if (preview) "预览" else "编辑",
                onBack = { nav.popBackStack() },
                actions = {
                    SaveStatusIndicator(saveState)
                    TextButton(
                        onClick = {
                            scope.launch {
                                saveState = "SAVING"
                                repo.saveDraft(Draft(noteId, title, content, revision, System.currentTimeMillis()))
                                when (val r = repo.saveNote(noteId, title, content, revision, "MANUAL_SAVE")) {
                                    is AppResult.Success -> {
                                        revision = r.data.note.revision
                                        repo.clearDraft(noteId)
                                        saveState = "SAVED"
                                    }
                                    is AppResult.Failure -> {
                                        saveState = "UNSAVED"
                                        error = r.error.userMessage
                                    }
                                }
                            }
                        }
                    ) { Text("保存") }
                    TextButton(onClick = { preview = !preview }) { Text(if (preview) "编辑" else "预览") }
                    Box {
                        TextButton(onClick = { moreOpen = true }) { Text("更多") }
                        DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                            DropdownMenuItem(text = { Text("重命名") }, onClick = {
                                moreOpen = false; renameOpen = true
                            })
                            DropdownMenuItem(text = { Text("移动到") }, onClick = {
                                moreOpen = false; moveOpen = true
                            })
                            DropdownMenuItem(text = { Text("原文视图") }, onClick = {
                                moreOpen = false; nav.navigate(Routes.original(noteId))
                            })
                            DropdownMenuItem(text = { Text("分享") }, onClick = {
                                moreOpen = false; nav.navigate(Routes.share(noteId))
                            })
                            DropdownMenuItem(text = { Text("导出") }, onClick = {
                                moreOpen = false; nav.navigate(Routes.export(noteId))
                            })
                            DropdownMenuItem(text = { Text("版本历史") }, onClick = {
                                moreOpen = false; nav.navigate(Routes.versions(noteId))
                            })
                            DropdownMenuItem(text = { Text("追加页面") }, onClick = {
                                moreOpen = false; nav.navigate(Routes.append(noteId))
                            })
                            DropdownMenuItem(text = { Text("复制 Markdown") }, onClick = {
                                moreOpen = false
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("markdown", content))
                            })
                            DropdownMenuItem(text = { Text("删除") }, onClick = {
                                moreOpen = false; confirmDelete = true
                            })
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                error != null && note == null -> Column(Modifier.padding(24.dp)) {
                    Text(error!!, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { nav.popBackStack() }) { Text("返回") }
                }
                else -> {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { if (it.length <= Limits.MAX_TITLE) title = it },
                        label = { Text("标题") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "${content.length} / ${Limits.MAX_CHARS} 字符",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (content.length >= Limits.MAX_CHARS) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "第 ${note?.pageCount ?: 0} 张图",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    /* 标签区：展示已有标签，点击「添加标签」打开选择器（PRD FR-13）。 */
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        note?.tags.orEmpty().forEach { tag ->
                            AssistChip(
                                onClick = {
                                    val keep = note?.tags.orEmpty().filterNot { it.id == tag.id }
                                    scope.launch {
                                        repo.setNoteTags(noteId, keep.map { it.id })
                                        (repo.getNote(noteId) as? AppResult.Success)?.let { note = it.data }
                                    }
                                },
                                label = { Text("${tag.name} ✕", style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                        TextButton(onClick = { tagPickerOpen = true }) { Text("添加标签") }
                    }

                    if (preview) {
                        PreviewPane(content)
                    } else {
                        Toolbar(
                            onInsert = { snippet ->
                                content = (content + snippet).take(Limits.MAX_CHARS)
                            },
                            canUndo = historyPos > 0,
                            canRedo = historyPos < history.lastIndex,
                            onUndo = {
                                if (historyPos > 0) {
                                    suppressHistory = true
                                    historyPos -= 1
                                    content = history[historyPos]
                                }
                            },
                            onRedo = {
                                if (historyPos < history.lastIndex) {
                                    suppressHistory = true
                                    historyPos += 1
                                    content = history[historyPos]
                                }
                            }
                        )
                        BasicTextField(
                            value = content,
                            onValueChange = { if (it.length <= Limits.MAX_CHARS) content = it },
                            textStyle = TextStyle(fontSize = 15.sp, lineHeight = 24.sp, fontFamily = FontFamily.Default),
                            modifier = Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState())
                        )
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "删除笔记",
            text = "将移入回收站，30 天内可恢复。",
            onConfirm = {
                confirmDelete = false
                scope.launch {
                    repo.deleteNote(noteId)
                    nav.popBackStack()
                }
            },
            onDismiss = { confirmDelete = false }
        )
    }
    if (conflictNotice) {
        ConfirmDialog(
            title = "检测到编辑冲突",
            text = "其他设备的内容已保存为冲突副本，可在列表中查看。",
            onConfirm = { conflictNotice = false },
            onDismiss = { conflictNotice = false }
        )
    }

    if (renameOpen) {
        TextInputDialog(
            title = "重命名笔记",
            label = "标题",
            initial = title,
            maxLength = Limits.MAX_TITLE,
            onConfirm = { newTitle ->
                title = newTitle
                renameOpen = false
                scope.launch {
                    when (val r = repo.saveNote(noteId, newTitle, content, revision, "MANUAL_SAVE")) {
                        is AppResult.Success -> { revision = r.data.note.revision; saveState = "SAVED" }
                        is AppResult.Failure -> error = r.error.userMessage
                    }
                }
            },
            onDismiss = { renameOpen = false }
        )
    }

    if (moveOpen) {
        FolderPickerDialog(
            repo = repo,
            title = "移动「${title.ifBlank { "未命名笔记" }}」到",
            onPick = { target ->
                scope.launch {
                    val dest = target ?: (repo.refreshFolders(null) as? AppResult.Success)
                        ?.data?.items?.firstOrNull()?.id
                    if (dest == null) {
                        error = "未找到根目录"
                    } else {
                        when (val r = repo.moveNote(noteId, dest)) {
                            is AppResult.Success -> { moveOpen = false; nav.popBackStack() }
                            is AppResult.Failure -> error = r.error.userMessage
                        }
                    }
                }
            },
            onDismiss = { moveOpen = false }
        )
    }

    if (tagPickerOpen) {
        TagPickerDialog(
            repo = repo,
            selected = note?.tags.orEmpty(),
            onConfirm = { picked ->
                scope.launch {
                    when (val r = repo.setNoteTags(noteId, picked.map { it.id })) {
                        is AppResult.Success -> {
                            tagPickerOpen = false
                            (repo.getNote(noteId) as? AppResult.Success)?.let { note = it.data }
                        }
                        is AppResult.Failure -> error = r.error.userMessage
                    }
                }
            },
            onDismiss = { tagPickerOpen = false }
        )
    }
}

/* 双指缩放：1x–5x，缩放为 1 时自动复位位移（PRD FR-08 原文视图）。 */
@Composable
private fun ZoomableImage(url: String, description: String) {
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val state = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 5f)
        offset = if (scale <= 1f) Offset.Zero else offset + panChange
    }
    Box(Modifier.fillMaxWidth().clipToBounds().transformable(state)) {
        AsyncImage(
            model = url,
            contentDescription = description,
            contentScale = ContentScale.FillWidth,
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y)
        )
    }
}

@Composable
private fun Toolbar(
    onInsert: (String) -> Unit,
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit
) {
    val actions = listOf(
        "H1" to "# ", "H2" to "## ", "H3" to "### ",
        "加粗" to "**粗体**", "斜体" to "*斜体*",
        "无序" to "- ", "有序" to "1. ", "待办" to "- [ ] ",
        "引用" to "> ", "代码" to "\n```\n\n```\n", "分割线" to "\n---\n"
    )
    LazyColumn(Modifier.height(48.dp)) {
        item {
            Row(
                Modifier.padding(horizontal = 8.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onUndo, enabled = canUndo) { Text("撤销", style = MaterialTheme.typography.labelSmall) }
                TextButton(onClick = onRedo, enabled = canRedo) { Text("重做", style = MaterialTheme.typography.labelSmall) }
                actions.forEach { (label, snippet) ->
                    TextButton(onClick = { onInsert(snippet) }) {
                        Text(label, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewPane(content: String) {
    val blocks = remember(content) { MarkdownRenderer.parse(content) }
    if (blocks.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("暂无内容", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(blocks) { _, block ->
            when (block.type) {
                "heading" -> Text(
                    block.text,
                    fontSize = when (block.level) {
                        1 -> 24.sp; 2 -> 20.sp; 3 -> 18.sp; else -> 16.sp
                    },
                    fontWeight = FontWeight.Bold
                )
                "code" -> Text(
                    block.text,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
                        .padding(12.dp)
                )
                "quote" -> Row(Modifier.fillMaxWidth()) {
                    Box(Modifier.size(width = 3.dp, height = 20.dp).background(MaterialTheme.colorScheme.outlineVariant))
                    Spacer(Modifier.size(8.dp))
                    Text(block.text, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                "list", "todo" -> Text("· ${block.text}", fontSize = 15.sp)
                "table" -> block.table?.let { TableView(it) }
                else -> Text(block.text, fontSize = 15.sp, lineHeight = 24.sp)
            }
        }
    }
}

/*
 * 表格：列少时铺满可用宽度，列多时给每列保底宽度并允许横向滑动。
 * 表头用 surfaceVariant 底 + Medium 字重，行间只画横线，避免网格过密。
 */
@Composable
private fun TableView(data: MarkdownRenderer.TableData) {
    if (data.headers.isEmpty()) return
    val columns = data.headers.size
    val line = MaterialTheme.colorScheme.outlineVariant
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cellWidth = maxOf(96.dp, maxWidth / columns)
        Column(
            Modifier
                .horizontalScroll(rememberScrollState())
                .clip(MaterialTheme.shapes.small)
                .border(1.dp, line, MaterialTheme.shapes.small)
        ) {
            Row(Modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
                data.headers.forEachIndexed { index, title ->
                    TableCellText(title, cellWidth, data.aligns.getOrNull(index) ?: 0, header = true)
                }
            }
            data.rows.forEach { row ->
                HorizontalDivider(thickness = 1.dp, color = line)
                Row {
                    for (index in 0 until columns) {
                        TableCellText(
                            row.getOrNull(index).orEmpty(),
                            cellWidth,
                            data.aligns.getOrNull(index) ?: 0,
                            header = false
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TableCellText(text: String, width: Dp, align: Int, header: Boolean) {
    Text(
        text = text,
        modifier = Modifier.width(width).padding(horizontal = 10.dp, vertical = 8.dp),
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = if (header) FontWeight.Medium else FontWeight.Normal,
        textAlign = when (align) {
            1 -> TextAlign.Center
            2 -> TextAlign.End
            else -> TextAlign.Start
        }
    )
}

/*
 * 原文视图：页面顺序与页码，原图地址每次进入重新签发（API.md 5.4.4）。
 */
@Composable
fun OriginalPagesScreen(nav: NavHostController, repo: GleanNoteRepository, noteId: String?) {
    if (noteId == null) return
    var pages by remember { mutableStateOf<List<NotePage>>(emptyList()) }
    var originals by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(noteId) {
        when (val r = repo.pages(noteId)) {
            is AppResult.Success -> {
                pages = r.data
                val map = mutableMapOf<String, String>()
                r.data.forEach { page ->
                    (repo.pageOriginal(noteId, page.id) as? AppResult.Success)?.let { map[page.id] = it.data.originalUrl }
                }
                originals = map
            }
            is AppResult.Failure -> error = r.error.userMessage
        }
        loading = false
    }

    Scaffold(topBar = { GleanTopBar("原文", onBack = { nav.popBackStack() }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                error != null -> Text(error!!, Modifier.padding(24.dp), color = MaterialTheme.colorScheme.error)
                pages.isEmpty() -> Text("暂无页面图片", Modifier.padding(24.dp))
                else -> LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(pages, key = { it.id }) { page ->
                        Card {
                            Column(Modifier.padding(8.dp)) {
                                Text("第 ${page.index} / ${pages.size} 页", style = MaterialTheme.typography.labelMedium)
                                Spacer(Modifier.height(8.dp))
                                val url = originals[page.id]
                                if (url != null) {
                                    ZoomableImage(url, "第 ${page.index} 页")
                                } else {
                                    Text("图片加载失败，点击重试", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

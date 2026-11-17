package com.example.gleannote.feature.capture

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.example.gleannote.core.common.moved
import com.example.gleannote.core.contract.AppResult
import com.example.gleannote.core.contract.GleanNoteRepository
import com.example.gleannote.core.contract.Limits
import com.example.gleannote.core.contract.NavTarget
import com.example.gleannote.core.contract.Quota
import com.example.gleannote.core.contract.Routes
import com.example.gleannote.core.contract.UploadRequest
import com.example.gleannote.core.media.ImageProcessor
import com.example.gleannote.core.ui.ConfirmDialog
import com.example.gleannote.core.ui.EmptyState
import com.example.gleannote.core.ui.FolderPickerDialog
import com.example.gleannote.core.ui.GleanTopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import com.example.gleannote.core.ui.extendedColors

/* 待转换图片的会话暂存：跨屏传二进制不适合走导航参数，这里用进程内单例（退出即丢弃）。 */
object CaptureSession {
    var images: List<ImageProcessor.ProcessedImage> = emptyList()

    val atLimit: Boolean get() = images.size >= Limits.MAX_IMAGES_PER_BATCH

    fun clear() {
        images = emptyList()
    }

    fun add(image: ImageProcessor.ProcessedImage): Boolean {
        if (atLimit) return false
        images = images + image
        return true
    }

    fun removeAt(index: Int) {
        if (index in images.indices) images = images.filterIndexed { i, _ -> i != index }
    }

    /* 顺序即页面顺序（PRD FR-03），拖拽排序走纯函数以便单测。 */
    fun move(from: Int, to: Int) {
        images = images.moved(from, to)
    }
}

/*
 * 拍摄页：CameraX 取景 + 连拍 + 相册导入 + 底部缩略图条（可删除、可点开大图检查清晰度）。
 * 对应 PRD FR-03、GN-CLIENT-004 SCR-03。
 *
 * 相册与拍照是两条并列的入口：相册不依赖相机权限，选完即可直接进入确认页。
 */
@Composable
fun CaptureScreen(nav: NavHostController, repo: GleanNoteRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var granted by remember { mutableStateOf(hasCameraPermission(context)) }
    var processing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var images by remember { mutableStateOf(CaptureSession.images) }
    var viewerIndex by remember { mutableStateOf<Int?>(null) }
    var exitConfirm by remember { mutableStateOf(false) }
    var capture by remember { mutableStateOf<ImageCapture?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { ok ->
        granted = ok
        if (!ok) error = "未授予相机权限，可改用相册导入"
    }

    /*
     * 相册导入：与拍照共用同一套压缩与摘要口径（FR-04），结果并入当前会话。
     * 多选上限取 PRD 单次 1–20 张；超出剩余容量的部分截断并明确告知。
     */
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(Limits.MAX_IMAGES_PER_BATCH)
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            processing = true
            error = null
            notice = null
            val remaining = Limits.MAX_IMAGES_PER_BATCH - CaptureSession.images.size
            if (remaining <= 0) {
                error = "已达单次上限 ${Limits.MAX_IMAGES_PER_BATCH} 张，请先转换或删除部分图片"
                processing = false
                return@launch
            }
            val picked = uris.take(remaining)
            var failed = 0
            val added = picked.mapNotNull { uri ->
                runCatching { ImageProcessor.process(context, uri) }
                    .onFailure { failed += 1 }
                    .getOrNull()
            }
            CaptureSession.images = CaptureSession.images + added
            images = CaptureSession.images
            val skipped = uris.size - picked.size
            notice = buildString {
                if (added.isNotEmpty()) append("已从相册添加 ${added.size} 张")
                if (failed > 0) {
                    if (isNotEmpty()) append("；")
                    append("$failed 张无法读取")
                }
                if (skipped > 0) {
                    if (isNotEmpty()) append("；")
                    append("$skipped 张超出上限已忽略")
                }
            }.ifBlank { null }
            processing = false
        }
    }

    fun openGallery() {
        if (CaptureSession.atLimit) {
            error = "已达单次上限 ${Limits.MAX_IMAGES_PER_BATCH} 张，请先转换或删除部分图片"
            return
        }
        error = null
        notice = null
        /* 个别 ROM 缺少系统照片选择器，兜底提示而不是崩溃（FR-03 异常与恢复）。 */
        runCatching {
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }.onFailure {
            error = "当前系统没有可用的相册选择器，请更新系统组件后重试"
        }
    }

    LaunchedEffect(Unit) {
        if (!granted) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    /* 从系统设置返回后自动重检权限（PRD FR-03「返回应用后重新检测」）。 */
    LaunchedEffect(granted) {
        while (!granted) {
            kotlinx.coroutines.delay(1500)
            if (hasCameraPermission(context)) granted = true
        }
    }

    Scaffold(
        topBar = {
            GleanTopBar("拍摄", onBack = {
                if (CaptureSession.images.isNotEmpty()) exitConfirm = true else nav.popBackStack()
            })
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (granted) {
                /* 取景区：占满剩余空间，顶部叠加张数与相册入口。 */
                Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black)) {
                    CameraPreview(
                        onReady = { capture = it },
                        onError = { error = it }
                    )
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(extendedColors().cameraScrim)
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "已选 ${images.size} / ${Limits.MAX_IMAGES_PER_BATCH} 张",
                            color = Color.White,
                            style = MaterialTheme.typography.labelLarge
                        )
                        TextButton(onClick = { openGallery() }) {
                            Text("从相册导入", color = Color.White)
                        }
                    }
                    if (processing) {
                        CircularProgressIndicator(
                            Modifier.align(Alignment.Center).size(36.dp),
                            color = Color.White
                        )
                    }
                }
            } else {
                /*
                 * 未授权：说明卡片 + 相册主入口。这里不再 return，
                 * 否则从相册选完图片后缩略图条与「下一步」都不会出现（本轮修复的缺陷）。
                 */
                Column(
                    Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("相机未授权", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "可以直接从相册选择已有照片继续转换；需要现场拍摄时再开启相机权限，返回本页会自动重新检测。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { openGallery() }, enabled = !processing) {
                            Text("从相册选择")
                        }
                        OutlinedButton(onClick = {
                            val intent = Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", context.packageName, null)
                            )
                            context.startActivity(intent)
                        }) { Text("去设置开启相机") }
                    }
                    if (processing) {
                        Spacer(Modifier.height(16.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("正在处理图片…", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            notice?.let {
                Text(
                    it,
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium
                )
            }

            error?.let {
                Text(
                    it,
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelMedium
                )
            }

            /* 缩略图条：点开大图左右滑动检查，✕ 删除单张。相册与拍照的图片统一在此管理。 */
            if (images.isNotEmpty()) {
                LazyRow(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(images, key = { _, img -> img.localPath }) { index, image ->
                        Box {
                            AsyncImage(
                                model = File(image.localPath),
                                contentDescription = "第 ${index + 1} 张",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { viewerIndex = index }
                            )
                            Box(
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .size(22.dp)
                                    .clip(CircleShape)
                                    .background(extendedColors().cameraScrim)
                                    .clickable {
                                        CaptureSession.removeAt(index)
                                        images = CaptureSession.images
                                        if (images.isEmpty()) viewerIndex = null
                                    },
                                contentAlignment = Alignment.Center
                            ) { Text("✕", color = Color.White, style = MaterialTheme.typography.labelSmall) }
                        }
                    }
                }
            }

            /* 底部三栏：相册 / 快门 / 检查。相册是常驻主入口，不依赖相机权限。 */
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    Modifier
                        .width(84.dp)
                        .clickable(enabled = !processing) { openGallery() },
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        Modifier
                            .size(46.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .border(
                                1.5.dp,
                                if (CaptureSession.atLimit) MaterialTheme.colorScheme.outlineVariant
                                else MaterialTheme.colorScheme.outline,
                                RoundedCornerShape(10.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) { Text("相册", style = MaterialTheme.typography.labelMedium) }
                    Spacer(Modifier.height(4.dp))
                    Text("${images.size}/${Limits.MAX_IMAGES_PER_BATCH}", style = MaterialTheme.typography.labelSmall)
                }

                Box(
                    Modifier
                        .size(68.dp)
                        .clip(CircleShape)
                        .border(3.dp, extendedColors().cameraControl, CircleShape)
                        .clickable(enabled = granted && !processing && !CaptureSession.atLimit) {
                            shoot(context, scope, capture, onBusy = { processing = it },
                                onSaved = {
                                    images = CaptureSession.images
                                    error = null
                                    notice = null
                                },
                                onError = { error = it })
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(
                                if (!granted || CaptureSession.atLimit) extendedColors().cameraControlDisabled
                                else extendedColors().cameraControl
                            )
                    )
                }

                Column(
                    Modifier
                        .width(84.dp)
                        .clickable(enabled = images.isNotEmpty()) { viewerIndex = 0 },
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        Modifier
                            .size(46.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .border(
                                1.5.dp,
                                if (images.isEmpty()) MaterialTheme.colorScheme.outlineVariant
                                else MaterialTheme.colorScheme.outline,
                                RoundedCornerShape(10.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) { Text("检查", style = MaterialTheme.typography.labelMedium) }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (granted) "已选 ${images.size} 张" else "相册模式",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }

            if (CaptureSession.atLimit) {
                Text(
                    "已达单次上限 ${Limits.MAX_IMAGES_PER_BATCH} 张，请先转换或删除部分图片",
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.labelSmall
                )
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { CaptureSession.clear(); images = CaptureSession.images; notice = null },
                    enabled = images.isNotEmpty() && !processing,
                    modifier = Modifier.weight(1f)
                ) { Text("清空") }
                Button(
                    onClick = { nav.navigate(Routes.CONFIRM) },
                    enabled = images.isNotEmpty() && !processing,
                    modifier = Modifier.weight(2f)
                ) { Text("下一步：确认并转换") }
            }
        }
    }

    viewerIndex?.let { start ->
        ImageViewerDialog(
            images = images,
            initialIndex = start.coerceIn(0, (images.size - 1).coerceAtLeast(0)),
            onDelete = { index ->
                CaptureSession.removeAt(index)
                images = CaptureSession.images
                if (images.isEmpty()) viewerIndex = null else viewerIndex = index.coerceAtMost(images.lastIndex)
            },
            /* 重拍 = 删除当前张并回到取景，符合 PRD FR-03。 */
            onRetake = { index ->
                CaptureSession.removeAt(index)
                images = CaptureSession.images
                viewerIndex = null
                error = null
                notice = "已删除当前图片，请重新拍摄"
            },
            onDismiss = { viewerIndex = null }
        )
    }

    if (exitConfirm) {
        ConfirmDialog(
            title = "放弃本次选择",
            text = "放弃本次选择的 ${CaptureSession.images.size} 张图片？",
            onConfirm = {
                exitConfirm = false
                CaptureSession.clear()
                nav.popBackStack()
            },
            onDismiss = { exitConfirm = false }
        )
    }
}

@Composable
private fun CameraPreview(onReady: (ImageCapture) -> Unit, onError: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            /* COMPATIBLE 用 TextureView，FILL_CENTER 才能真正铺满裁切（SurfaceView 会留白）。 */
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
            /* 画面比例与容器不一致时用黑底承接，避免出现白边。 */
            setBackgroundColor(android.graphics.Color.BLACK)
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
    }

    DisposableEffect(Unit) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val provider = future.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture
                )
                onReady(capture)
            } catch (e: Exception) {
                onError("相机启动失败：${e.message ?: "未知错误"}")
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            runCatching {
                ProcessCameraProvider.getInstance(context).get().unbindAll()
            }
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = Modifier.fillMaxSize().clipToBounds()
    )
}

/* 快门：写盘 → 统一压缩 → 入会话；失败给出可读原因。 */
private fun shoot(
    context: Context,
    scope: kotlinx.coroutines.CoroutineScope,
    capture: ImageCapture?,
    onBusy: (Boolean) -> Unit,
    onSaved: () -> Unit,
    onError: (String) -> Unit
) {
    if (capture == null) {
        onError("相机尚未就绪，请稍候重试")
        return
    }
    if (CaptureSession.atLimit) {
        onError("单次最多 ${Limits.MAX_IMAGES_PER_BATCH} 张")
        return
    }
    if (context.cacheDir.usableSpace < 50L * 1024 * 1024) {
        onError("本地存储空间不足，请清理后重试")
        return
    }
    onBusy(true)
    val dir = File(context.cacheDir, "gleannote/camera").apply { mkdirs() }
    val file = File(dir, "shot_${System.currentTimeMillis()}.jpg")
    val options = ImageCapture.OutputFileOptions.Builder(file).build()
    capture.takePicture(
        options,
        ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                scope.launch(Dispatchers.IO) {
                    val image = runCatching { ImageProcessor.processFile(context, file) }.getOrNull()
                    withContext(Dispatchers.Main) {
                        when {
                            image == null -> onError("图片无法读取，请重拍")
                            !CaptureSession.add(image) -> onError("单次最多 ${Limits.MAX_IMAGES_PER_BATCH} 张")
                            else -> onSaved()
                        }
                        onBusy(false)
                    }
                }
            }

            override fun onError(exception: ImageCaptureException) {
                onError("拍照失败：${exception.message ?: "请重试"}")
                onBusy(false)
            }
        }
    )
}

private fun hasCameraPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

/* 大图查看：左右滑动切换、双指缩放、单张删除（PRD FR-03「检查清晰度」）。 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ImageViewerDialog(
    images: List<ImageProcessor.ProcessedImage>,
    initialIndex: Int,
    onDelete: (Int) -> Unit,
    onRetake: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val pagerState = rememberPagerState(initialPage = initialIndex, pageCount = { images.size })
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                ZoomableFileImage(File(images[page].localPath))
            }
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "第 ${pagerState.currentPage + 1} / ${images.size} 页",
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    /* 重拍：删除当前张并回到取景（PRD FR-03）。 */
                    TextButton(onClick = { onRetake(pagerState.currentPage) }) {
                        Text("重拍", color = Color.White)
                    }
                    TextButton(onClick = { onDelete(pagerState.currentPage) }) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = onDismiss) { Text("关闭", color = Color.White) }
                }
            }
        }
    }
}

@Composable
private fun ZoomableFileImage(file: File) {
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    /*
     * 只有双指才消费手势：单指拖动交给 HorizontalPager 翻页，
     * 否则 transformable 会把左右滑动全部吃掉，无法切页（本轮实测问题）。
     */
    val zoomGesture = Modifier.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            do {
                val event = awaitPointerEvent()
                if (event.changes.size >= 2) {
                    val zoomChange = event.calculateZoom()
                    val panChange = event.calculatePan()
                    scale = (scale * zoomChange).coerceIn(1f, 5f)
                    offset = if (scale <= 1f) Offset.Zero else offset + panChange
                    event.changes.forEach { it.consume() }
                }
            } while (event.changes.any { it.pressed })
        }
    }
    Box(Modifier.fillMaxSize().clipToBounds().then(zoomGesture), contentAlignment = Alignment.Center) {
        AsyncImage(
            model = file,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y)
        )
    }
}

/*
 * 待转换确认页：缩略图（长按拖拽排序、单张删除）、目标目录、额度与容量校验。
 * 对应 PRD FR-03「待转换确认页」与 FR-04 上传入口。
 */
@Composable
fun ConfirmScreen(nav: NavHostController, repo: GleanNoteRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var quota by remember { mutableStateOf<Quota?>(null) }
    var uploading by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var images by remember { mutableStateOf(CaptureSession.images) }
    var targetFolderId by remember { mutableStateOf<String?>(NavTarget.folderId) }
    var targetFolderName by remember { mutableStateOf("当前目录") }
    var folderPickerOpen by remember { mutableStateOf(false) }
    var viewerIndex by remember { mutableStateOf<Int?>(null) }
    var processing by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }

    /* 确认页补充图片：漏拍或漏选时可继续从相册追加，顺序接在现有图片之后。 */
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(Limits.MAX_IMAGES_PER_BATCH)
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            processing = true
            error = null
            notice = null
            val remaining = Limits.MAX_IMAGES_PER_BATCH - CaptureSession.images.size
            if (remaining <= 0) {
                error = "已达单次上限 ${Limits.MAX_IMAGES_PER_BATCH} 张"
                processing = false
                return@launch
            }
            val picked = uris.take(remaining)
            var failed = 0
            val added = picked.mapNotNull { uri ->
                runCatching { ImageProcessor.process(context, uri) }
                    .onFailure { failed += 1 }
                    .getOrNull()
            }
            CaptureSession.images = CaptureSession.images + added
            images = CaptureSession.images
            val skipped = uris.size - picked.size
            notice = buildString {
                if (added.isNotEmpty()) append("已添加 ${added.size} 张")
                if (failed > 0) {
                    if (isNotEmpty()) append("；")
                    append("$failed 张无法读取")
                }
                if (skipped > 0) {
                    if (isNotEmpty()) append("；")
                    append("$skipped 张超出上限已忽略")
                }
            }.ifBlank { null }
            processing = false
        }
    }

    LaunchedEffect(Unit) {
        (repo.quota() as? AppResult.Success)?.let { quota = it.data }
        /* 目标目录缺省取当前所在目录；未传时回退根目录。 */
        val id = targetFolderId
            ?: (repo.refreshFolders(null) as? AppResult.Success)?.data?.items?.firstOrNull()?.id
        targetFolderId = id
        if (id != null) {
            (repo.folderPath(id) as? AppResult.Success)?.let { targetFolderName = it.data.displayPath }
        }
    }

    val totalBytes = images.sumOf { it.bytes }
    val quotaShort = quota?.let { it.remaining < images.size } == true
    val storageShort = quota?.let { it.storageUsedBytes + totalBytes > it.storageLimitBytes } == true
    val canSubmit = images.isNotEmpty() && !uploading && !processing && !quotaShort && !storageShort && targetFolderId != null

    Scaffold(topBar = { GleanTopBar("确认转换", onBack = { nav.popBackStack() }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Text("共 ${images.size} 张", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "长按缩略图可拖动排序，顺序即笔记内容顺序。",
                style = MaterialTheme.typography.labelSmall
            )

            quota?.let {
                Spacer(Modifier.height(8.dp))
                Text("今日剩余额度：${it.remaining} / ${it.dailyLimit} 张", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "云端空间：${it.storageUsedBytes / 1024 / 1024} MB / ${it.storageLimitBytes / 1024 / 1024} MB" +
                        "（本次约 ${totalBytes / 1024} KB）",
                    style = MaterialTheme.typography.bodySmall
                )
                when {
                    quotaShort -> Text(
                        "额度不足：本次需 ${images.size} 张，今日仅剩 ${it.remaining} 张，请减少图片",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelMedium
                    )
                    storageShort -> Text(
                        "云端空间不足：请前往回收站或删除笔记释放空间",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("保存到：$targetFolderName", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { folderPickerOpen = true }) { Text("更换") }
            }

            Spacer(Modifier.height(8.dp))
            SortableThumbnails(
                images = images,
                onOpen = { viewerIndex = it },
                onRemove = { index ->
                    CaptureSession.removeAt(index)
                    images = CaptureSession.images
                },
                onMove = { from, to ->
                    CaptureSession.move(from, to)
                    images = CaptureSession.images
                }
            )

            /* 相册补充入口：与拍摄页同一套压缩口径，追加到列表末尾。 */
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = {
                        if (CaptureSession.atLimit) {
                            error = "已达单次上限 ${Limits.MAX_IMAGES_PER_BATCH} 张"
                        } else {
                            runCatching {
                                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            }.onFailure { error = "当前系统没有可用的相册选择器" }
                        }
                    },
                    enabled = !uploading && !processing
                ) { Text("＋ 从相册添加") }
                if (processing) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(6.dp))
                    Text("处理中…", style = MaterialTheme.typography.labelSmall)
                } else {
                    notice?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                }
            }

            if (images.isEmpty()) {
                Text(
                    "还没有图片，可从相册添加后开始转换。",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (progress.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(progress, style = MaterialTheme.typography.labelMedium)
                }
            }
            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }

            Spacer(Modifier.weight(1f))
            Button(
                onClick = {
                    uploading = true
                    error = null
                    scope.launch {
                        try {
                            val folderId = targetFolderId
                            if (folderId == null) {
                                error = "未找到目标目录，请重新登录"
                                return@launch
                            }
                            val snapshot = CaptureSession.images
                            val requests = snapshot.map {
                                UploadRequest(it.fileName, it.contentType, it.bytes, it.sha256, it.localPath)
                            }
                            progress = "申请上传会话…"
                            val sessions = when (val r = repo.uploadImages(requests)) {
                                is AppResult.Success -> r.data
                                is AppResult.Failure -> {
                                    error = r.error.userMessage
                                    return@launch
                                }
                            }
                            /*
                             * 并发上限 2（PRD FR-04）：信号量限制同时在传的张数；
                             * 结果按下标写回数组，保证上传完成顺序不影响页面顺序（API.md 5.3.4）。
                             */
                            val slots = arrayOfNulls<String>(sessions.size)
                            val failures = java.util.Collections.synchronizedList(mutableListOf<String>())
                            val semaphore = Semaphore(2)
                            var done = 0
                            coroutineScope {
                                sessions.mapIndexed { index, session ->
                                    async(Dispatchers.IO) {
                                        semaphore.withPermit {
                                            val image = snapshot.getOrNull(index) ?: return@withPermit
                                            /*
                                             * 单张失败自动重试 1 次（PRD FR-04）：瞬时抖动不应让整批失败。
                                             * 仅对 retryable 的错误重试；uploadId 与幂等键不变，重传安全。
                                             */
                                            var up = repo.uploadBinary(session, image.localPath)
                                            if (up is AppResult.Failure && up.error.retryable) {
                                                kotlinx.coroutines.delay(800)
                                                up = repo.uploadBinary(session, image.localPath)
                                            }
                                            if (up is AppResult.Failure) {
                                                failures += up.error.userMessage
                                                return@withPermit
                                            }
                                            var fin = repo.completeUpload(session.uploadId, image.sha256)
                                            if (fin is AppResult.Failure && fin.error.retryable) {
                                                kotlinx.coroutines.delay(800)
                                                fin = repo.completeUpload(session.uploadId, image.sha256)
                                            }
                                            when (fin) {
                                                is AppResult.Success -> {
                                                    slots[index] = session.uploadId
                                                    done += 1
                                                    progress = "已上传 $done / ${sessions.size}"
                                                }
                                                is AppResult.Failure -> failures += fin.error.userMessage
                                            }
                                        }
                                    }
                                }.awaitAll()
                            }
                            if (failures.isNotEmpty()) {
                                error = failures.first()
                                return@launch
                            }
                            val uploadIds = slots.filterNotNull()
                            progress = "提交转换…"
                            when (val created = repo.createFromPages(folderId, uploadIds, null)) {
                                is AppResult.Success -> {
                                    CaptureSession.clear()
                                    NavTarget.set(null)
                                    ImageProcessor.cleanup(nav.context)
                                    nav.navigate(Routes.conversion(created.data.note.id)) {
                                        popUpTo(Routes.CAPTURE) { inclusive = true }
                                    }
                                }
                                is AppResult.Failure -> error = created.error.userMessage
                            }
                        } finally {
                            uploading = false
                            progress = ""
                        }
                    }
                },
                enabled = canSubmit,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (uploading) "处理中…" else "开始转换") }
        }
    }

    if (folderPickerOpen) {
        FolderPickerDialog(
            repo = repo,
            title = "选择目标目录",
            onPick = { id ->
                folderPickerOpen = false
                scope.launch {
                    val dest = id
                        ?: (repo.refreshFolders(null) as? AppResult.Success)?.data?.items?.firstOrNull()?.id
                    if (dest != null) {
                        targetFolderId = dest
                        (repo.folderPath(dest) as? AppResult.Success)?.let { targetFolderName = it.data.displayPath }
                    }
                }
            },
            onDismiss = { folderPickerOpen = false }
        )
    }

    viewerIndex?.let { start ->
        ImageViewerDialog(
            images = images,
            initialIndex = start.coerceIn(0, (images.size - 1).coerceAtLeast(0)),
            onDelete = { index ->
                CaptureSession.removeAt(index)
                images = CaptureSession.images
                if (images.isEmpty()) viewerIndex = null else viewerIndex = index.coerceAtMost(images.lastIndex)
            },
            onRetake = { index ->
                CaptureSession.removeAt(index)
                images = CaptureSession.images
                viewerIndex = null
                error = "已删除当前图片，可返回上一页重新拍摄"
            },
            onDismiss = { viewerIndex = null }
        )
    }
}

/* 可长按拖拽排序的缩略图条：拖动超过一格宽度即与相邻项交换。 */
@Composable
private fun SortableThumbnails(
    images: List<ImageProcessor.ProcessedImage>,
    onOpen: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onMove: (Int, Int) -> Unit
) {
    var draggingIndex by remember { mutableStateOf(-1) }
    var dragOffset by remember { mutableStateOf(0f) }
    var cellWidth by remember { mutableStateOf(0) }

    LazyRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        itemsIndexed(images, key = { _, img -> img.localPath }) { index, image ->
            Box(
                Modifier
                    .graphicsLayer { translationX = if (index == draggingIndex) dragOffset else 0f }
                    .then(
                        if (cellWidth > 0) Modifier.pointerInput(index, images.size, cellWidth) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { draggingIndex = index; dragOffset = 0f },
                                onDragEnd = { draggingIndex = -1; dragOffset = 0f },
                                onDragCancel = { draggingIndex = -1; dragOffset = 0f },
                                onDrag = { change, amount ->
                                    change.consume()
                                    dragOffset += amount.x
                                    if (dragOffset > cellWidth && index < images.lastIndex) {
                                        onMove(index, index + 1)
                                        draggingIndex = index + 1
                                        dragOffset = 0f
                                    } else if (dragOffset < -cellWidth && index > 0) {
                                        onMove(index, index - 1)
                                        draggingIndex = index - 1
                                        dragOffset = 0f
                                    }
                                }
                            )
                        } else Modifier
                    )
            ) {
                Box(
                    Modifier
                        .onSizeChanged { cellWidth = it.width }
                        .size(88.dp)
                ) {
                    AsyncImage(
                        model = File(image.localPath),
                        contentDescription = "第 ${index + 1} 张",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(88.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { onOpen(index) }
                    )
                    Text(
                        "${index + 1}",
                        Modifier
                            .align(Alignment.BottomStart)
                            .padding(4.dp)
                            .background(extendedColors().cameraScrim, RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp),
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall
                    )
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(extendedColors().cameraScrim)
                            .clickable { onRemove(index) },
                        contentAlignment = Alignment.Center
                    ) { Text("✕", color = Color.White, style = MaterialTheme.typography.labelSmall) }
                }
            }
        }
    }
}

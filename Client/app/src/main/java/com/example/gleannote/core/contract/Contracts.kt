package com.example.gleannote.core.contract

import kotlinx.coroutines.flow.Flow

/* 全客户端共享的领域模型、结果与接口签名。所有模块必须以此文件为准，不得自行重复定义。 */

data class User(
    val id: String = "",
    val email: String = "",
    val nickname: String = "",
    val emailVerified: Boolean = false,
    val createdAt: String = "",
    val updatedAt: String = ""
)

data class AuthSession(
    val user: User = User(),
    val accessToken: String = "",
    val refreshToken: String = "",
    val expiresIn: Int = 900,
    val tokenType: String = "Bearer"
)

data class Quota(
    val quotaDate: String = "",
    val dailyLimit: Int = 50,
    val remaining: Int = 50,
    val resetAt: String = "",
    val conversionRunning: Boolean = false,
    val storageUsedBytes: Long = 0,
    val storageLimitBytes: Long = 209715200L,
    val storageUsedPercent: Int = 0
)

data class Folder(
    val id: String = "",
    val parentId: String? = null,
    val name: String = "",
    val depth: Int = 1,
    val revision: Int = 1,
    val childCount: Int = 0,
    val noteCount: Int = 0,
    val createdAt: String = "",
    val updatedAt: String = ""
)

data class PathItem(val id: String = "", val name: String = "")
data class FolderPath(val items: List<PathItem> = emptyList(), val displayPath: String = "")

data class Tag(val id: String = "", val name: String = "", val noteCount: Int = 0, val createdAt: String = "")

data class Note(
    val id: String = "",
    val folderId: String = "",
    val folderPath: String = "",
    val title: String = "",
    val state: String = "NORMAL",
    val revision: Int = 1,
    val charCount: Int = 0,
    val pageCount: Int = 0,
    val thumbnailUrl: String? = null,
    val tags: List<Tag> = emptyList(),
    val isConflictCopy: Boolean = false,
    val content: String = "",
    val shareEnabled: Boolean = false,
    val createdAt: String = "",
    val updatedAt: String = "",
    val localOnly: Boolean = false,
    val pendingSync: Boolean = false
)

data class NotePage(
    val id: String = "",
    val index: Int = 1,
    val width: Int = 0,
    val height: Int = 0,
    val bytes: Long = 0,
    val thumbUrl: String = "",
    val originalUrl: String = "",
    val expiresAt: String = ""
)

data class ConversionJob(
    val id: String = "",
    val noteId: String = "",
    val kind: String = "CREATE",
    val state: String = "QUEUED",
    val phase: String = "QUEUED",
    val totalPages: Int = 0,
    val processedPages: Int = 0,
    val failureCode: String? = null,
    val failureUserMessage: String? = null,
    val quotaCharged: Int = 0,
    val createdAt: String = "",
    val startedAt: String? = null,
    val finishedAt: String? = null
)

data class TrashItem(
    val batchId: String = "",
    val itemType: String = "NOTE",
    val itemId: String = "",
    val name: String = "",
    val originalPath: String = "",
    val folderCount: Int = 0,
    val noteCount: Int = 0,
    val deletedAt: String = "",
    val purgeAt: String = "",
    val remainingDays: Int = 30
)

data class UploadRequest(val fileName: String, val contentType: String, val bytes: Long, val sha256: String, val localPath: String = "")
data class UploadSession(
    val uploadId: String = "",
    val objectKey: String = "",
    val putUrl: String = "",
    val headers: Map<String, String> = emptyMap(),
    val expiresAt: String = ""
)
data class CompletedUpload(
    val uploadId: String = "",
    val status: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val bytes: Long = 0,
    val thumbUrl: String = ""
)
data class CreateFromPagesResult(val note: Note = Note(), val conversionJob: ConversionJob = ConversionJob(), val remaining: Int = 0, val charged: Int = 0)
data class NotePatchResult(val note: Note = Note(), val conflictCopyId: String? = null)
data class DeleteResult(val deleteBatchId: String = "", val folderCount: Int = 0, val noteCount: Int = 0, val purgeAt: String = "")
data class PageResult<T>(val items: List<T> = emptyList(), val nextPageToken: String = "", val totalSize: Int = 0)

data class SyncChange(
    val seq: Long = 0,
    val resourceType: String = "",
    val resourceId: String = "",
    val op: String = "",
    val revision: Int = 0,
    val updatedAt: String = "",
    val payload: String? = null
)
data class SyncResponse(val changes: List<SyncChange> = emptyList(), val nextCursor: Long = 0, val hasMore: Boolean = false)

data class SearchHit(val noteId: String = "", val title: String = "", val snippet: String = "", val folderPath: String = "", val updatedAt: String = "")
data class VersionMeta(val versionId: String = "", val source: String = "", val charCount: Int = 0, val createdAt: String = "", val expiresAt: String = "")
data class VersionDetail(val versionId: String = "", val content: String = "", val charCount: Int = 0, val createdAt: String = "")
data class ShareLink(val enabled: Boolean = false, val url: String = "", val createdAt: String = "", val updatedAt: String = "")
data class ExportJob(val exportId: String = "", val state: String = "", val downloadUrl: String = "", val expiresAt: String = "", val failureCode: String = "")
data class ImportJob(val importId: String = "", val state: String = "", val total: Int = 0, val succeeded: Int = 0, val failed: Int = 0)

data class PendingOp(
    val id: Long = 0,
    val seq: Long = 0,
    val resourceType: String = "",
    val resourceId: String = "",
    val method: String = "",
    val path: String = "",
    val bodyJson: String = "",
    val idempotencyKey: String = "",
    val createdAt: Long = 0L,
    val state: String = "PENDING",
    val retryCount: Int = 0,
    val lastError: String = ""
)

data class Draft(val noteId: String = "", val title: String = "", val content: String = "", val baseRevision: Int = 0, val updatedAt: Long = 0L)

/* 统一错误：按 code 分支，业务变量只从 metadata 取，不解析 detail / userMessage（API.md 2.8）。 */
data class AppError(
    val code: String = "INTERNAL",
    val status: Int = 0,
    val userMessage: String = "服务异常，请稍后重试",
    val metadata: Map<String, String> = emptyMap(),
    val retryable: Boolean = false,
    val requestId: String = ""
)

sealed class AppResult<out T> {
    data class Success<T>(val data: T) : AppResult<T>()
    data class Failure(val error: AppError) : AppResult<Nothing>()
    val isSuccess: Boolean get() = this is Success
    fun getOrNull(): T? = (this as? Success)?.data
}

object Limits {
    const val MAX_IMAGES_PER_BATCH = 20
    const val MAX_PAGES_PER_NOTE = 50
    const val MAX_CHARS = 200_000
    const val MAX_TITLE = 100
    const val MAX_FOLDER_NAME = 50
    const val MAX_DEPTH = 5
    const val MAX_TAGS_PER_NOTE = 10
    const val MAX_IMAGE_BYTES = 10L * 1024 * 1024
    const val DAILY_QUOTA = 50
    const val STORAGE_LIMIT = 209715200L
    const val POLL_INTERVAL_MS = 2000L
    const val CONVERSION_TIMEOUT_MS = 5 * 60 * 1000L
    const val UPLOAD_SESSION_TTL_MS = 30 * 60 * 1000L
    const val AUTOSAVE_DEBOUNCE_MS = 2000L
    const val IMAGE_LONG_EDGE = 1600
    const val JPEG_QUALITY = 85
    const val PAGE_SIZE = 20
}

object ApiPaths {
    const val BASE = "/v1"
    const val AUTH_SEND_CODE = "/auth:sendCode"
    const val AUTH_REGISTER = "/auth:register"
    const val AUTH_LOGIN = "/auth:login"
    const val AUTH_REFRESH = "/auth:refresh"
    const val AUTH_LOGOUT = "/auth:logout"
    const val AUTH_RESET = "/auth:resetPassword"
    const val ME = "/users/me"
    const val QUOTA = "/users/me/quota"
    const val FOLDERS = "/folders"
    const val NOTES = "/notes"
    const val NOTES_CREATE_FROM_PAGES = "/notes:createFromPages"
    const val NOTES_SEARCH = "/notes:search"
    const val UPLOADS = "/uploads"
    const val TRASH = "/trash"
    const val SYNC_CHANGES = "/sync:changes"
    const val TAGS = "/tags"
    const val EXPORTS = "/exports"
    const val IMPORTS = "/imports"
    const val PUBLIC_SHARE = "/public/shares"
    const val HEALTHZ = "/healthz"
    const val READYZ = "/readyz"
}

/* Routes：单 Activity 导航；深链 gleannote://note/{noteId} 见 GN-CLIENT-004 Q-12。 */
object Routes {
    const val SPLASH = "splash"
    const val LOGIN = "login"
    const val REGISTER = "register"
    const val RESET_PASSWORD = "reset"
    const val LIBRARY = "library"
    const val CAPTURE = "capture"
    const val CONFIRM = "confirm"
    const val CONVERSION = "conversion/{noteId}"
    const val EDITOR = "editor/{noteId}"
    const val ORIGINAL = "original/{noteId}"
    const val TRASH = "trash"
    const val SETTINGS = "settings"
    const val SEARCH = "search"
    const val TAGS = "tags"
    const val VERSIONS = "versions/{noteId}"
    const val SHARE = "share/{noteId}"
    const val EXPORT = "export/{noteId}"
    const val IMPORT = "import"
    const val APPEND = "append/{noteId}"
    fun editor(id: String) = "editor/$id"
    fun conversion(id: String) = "conversion/$id"
    fun original(id: String) = "original/$id"
    fun versions(id: String) = "versions/$id"
    fun share(id: String) = "share/$id"
    fun export(id: String) = "export/$id"
    fun append(id: String) = "append/$id"
}

/* 网络层：OkHttp + Gson 的通用请求口，令牌刷新单飞与 Problem 解析由实现负责。 */
interface ApiClient {
    suspend fun <T> request(
        method: String,
        path: String,
        body: Any? = null,
        query: Map<String, String?> = emptyMap(),
        idempotencyKey: String? = null,
        responseType: java.lang.reflect.Type,
        anonymous: Boolean = false,
        queryMulti: Map<String, List<String>> = emptyMap()
    ): AppResult<T>

    suspend fun putBinary(url: String, bytes: ByteArray, contentType: String): AppResult<Unit>
    suspend fun saveSession(session: AuthSession)
    fun invalidateSession()
    val isLoggedIn: Flow<Boolean>
}

/* 本地存储：Room 为 UI 唯一数据源，短效 URL 一律不入库。 */
interface LocalStore {
    fun observeFolders(parentId: String?): Flow<List<Folder>>
    fun observeNotes(folderId: String): Flow<List<Note>>
    fun observeAllNotes(): Flow<List<Note>>
    fun observeTrash(): Flow<List<TrashItem>>
    fun observeJob(noteId: String): Flow<ConversionJob?>
    fun observePendingCount(): Flow<Int>
    suspend fun upsertFolders(items: List<Folder>)
    /* 按父级整体替换：服务端已删除的目录必须从本地移除。 */
    suspend fun replaceFolders(parentId: String?, items: List<Folder>)
    suspend fun removeFolder(id: String)
    suspend fun upsertNotes(items: List<Note>)
    suspend fun getNote(id: String): Note?
    suspend fun saveNote(note: Note)
    suspend fun removeNote(id: String)
    suspend fun upsertTrash(items: List<TrashItem>)
    /* 整体替换回收站：服务端已不存在（恢复/彻底删除）的条目必须从本地移除。 */
    suspend fun replaceTrash(items: List<TrashItem>)
    suspend fun saveJob(job: ConversionJob)
    suspend fun enqueue(op: PendingOp): Long
    suspend fun pendingOps(): List<PendingOp>
    suspend fun removeOp(id: Long)
    suspend fun cursor(): Long
    suspend fun setCursor(value: Long)
    suspend fun saveDraft(draft: Draft)
    suspend fun draft(noteId: String): Draft?
    suspend fun clearDraft(noteId: String)
    suspend fun clearUserData()
}

/* 业务出口：feature 层只依赖此接口，不直接触碰 ApiClient / Room。 */
interface GleanNoteRepository {
    val isLoggedIn: Flow<Boolean>
    fun folders(parentId: String?): Flow<List<Folder>>
    fun notes(folderId: String): Flow<List<Note>>
    fun allNotes(): Flow<List<Note>>
    fun trash(): Flow<List<TrashItem>>
    fun observeJob(noteId: String): Flow<ConversionJob?>
    fun pendingCount(): Flow<Int>

    suspend fun sendCode(email: String, purpose: String): AppResult<Unit>
    suspend fun register(email: String, code: String, password: String, nickname: String?): AppResult<User>
    suspend fun login(email: String, password: String): AppResult<User>
    suspend fun logout(): AppResult<Unit>
    suspend fun resetPassword(email: String, code: String, newPassword: String): AppResult<Unit>
    suspend fun me(): AppResult<User>
    /*
     * 列表首图缩略图：服务端返回的是短效签名地址，不得落库（主文档 1.5），
     * 仅在内存缓存中供列表渲染，过期或重启后由下一次刷新补齐。
     */
    fun thumbnailFor(noteId: String): String?
    suspend fun updateNickname(nickname: String): AppResult<User>
    /* 注销账号：需邮箱验证码二次确认，成功后清除本机全部数据（PRD FR-01）。 */
    suspend fun deleteAccount(code: String): AppResult<Unit>
    suspend fun quota(): AppResult<Quota>

    suspend fun refreshFolders(parentId: String?, pageToken: String? = null): AppResult<PageResult<Folder>>
    suspend fun createFolder(parentId: String?, name: String): AppResult<Folder>
    suspend fun renameFolder(id: String, name: String, revision: Int): AppResult<Folder>
    suspend fun deleteFolder(id: String): AppResult<DeleteResult>
    suspend fun moveFolder(id: String, targetParentId: String?): AppResult<Folder>
    suspend fun folderPath(id: String): AppResult<FolderPath>

    suspend fun refreshNotes(folderId: String, pageToken: String? = null, orderBy: String? = null): AppResult<PageResult<Note>>
    suspend fun createNote(folderId: String, title: String = "", content: String = ""): AppResult<Note>
    suspend fun getNote(id: String): AppResult<Note>
    suspend fun saveNote(id: String, title: String?, content: String?, revision: Int, saveSource: String): AppResult<NotePatchResult>
    suspend fun deleteNote(id: String): AppResult<DeleteResult>
    suspend fun moveNote(id: String, folderId: String): AppResult<Note>
    suspend fun search(query: String, folderId: String? = null, tagIds: List<String> = emptyList()): AppResult<PageResult<SearchHit>>

    suspend fun uploadImages(items: List<UploadRequest>): AppResult<List<UploadSession>>
    suspend fun uploadBinary(session: UploadSession, localPath: String): AppResult<Unit>
    suspend fun completeUpload(uploadId: String, sha256: String): AppResult<CompletedUpload>
    suspend fun createFromPages(folderId: String, uploadIds: List<String>, title: String?): AppResult<CreateFromPagesResult>
    suspend fun pages(noteId: String): AppResult<List<NotePage>>
    suspend fun pageOriginal(noteId: String, pageId: String): AppResult<NotePage>
    suspend fun fetchJob(noteId: String): AppResult<ConversionJob>
    suspend fun cancelJob(noteId: String): AppResult<ConversionJob>
    suspend fun retryJob(noteId: String): AppResult<ConversionJob>
    suspend fun appendPages(noteId: String, uploadIds: List<String>): AppResult<ConversionJob>

    suspend fun refreshTrash(): AppResult<PageResult<TrashItem>>
    suspend fun undeleteNote(id: String): AppResult<Note>
    suspend fun undeleteFolder(id: String): AppResult<Folder>
    suspend fun expungeNote(id: String): AppResult<Unit>
    suspend fun expungeFolder(id: String): AppResult<Unit>

    suspend fun sync(): AppResult<Unit>
    suspend fun saveDraft(draft: Draft)
    suspend fun draft(noteId: String): Draft?
    suspend fun clearDraft(noteId: String)
    suspend fun clearUserData()

    suspend fun tags(): AppResult<List<Tag>>
    suspend fun createTag(name: String): AppResult<Tag>
    suspend fun renameTag(id: String, name: String): AppResult<Tag>
    suspend fun deleteTag(id: String): AppResult<Unit>
    suspend fun setNoteTags(noteId: String, tagIds: List<String>): AppResult<Unit>
    suspend fun versions(noteId: String): AppResult<List<VersionMeta>>
    suspend fun versionDetail(noteId: String, versionId: String): AppResult<VersionDetail>
    suspend fun restoreVersion(noteId: String, versionId: String): AppResult<Note>
    suspend fun shareLink(noteId: String): AppResult<ShareLink>
    suspend fun enableShare(noteId: String): AppResult<ShareLink>
    suspend fun disableShare(noteId: String): AppResult<Unit>
    suspend fun startExport(noteId: String?, folderId: String?, format: String): AppResult<ExportJob>
    suspend fun exportStatus(id: String): AppResult<ExportJob>
    suspend fun startImport(folderId: String, mode: String, uploadIds: List<String>): AppResult<ImportJob>
    suspend fun importStatus(id: String): AppResult<ImportJob>
}

/* 全局装配点，由 GleanNoteApp 持有；feature 层通过 it 取 repository / 工具。 */
interface AppContainer {
    val repository: GleanNoteRepository
    val api: ApiClient
    val local: LocalStore
    val baseUrl: String
}

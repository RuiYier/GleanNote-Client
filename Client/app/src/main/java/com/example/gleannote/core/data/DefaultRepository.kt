package com.example.gleannote.core.data

import android.content.Context
import com.example.gleannote.core.contract.ApiClient
import com.example.gleannote.core.contract.ApiPaths
import com.example.gleannote.core.contract.AppResult
import com.example.gleannote.core.contract.AuthSession
import com.example.gleannote.core.contract.CompletedUpload
import com.example.gleannote.core.contract.ConversionJob
import com.example.gleannote.core.contract.CreateFromPagesResult
import com.example.gleannote.core.contract.DeleteResult
import com.example.gleannote.core.contract.Draft
import com.example.gleannote.core.contract.ExportJob
import com.example.gleannote.core.contract.Folder
import com.example.gleannote.core.contract.FolderPath
import com.example.gleannote.core.contract.GleanNoteRepository
import com.example.gleannote.core.contract.ImportJob
import com.example.gleannote.core.contract.LocalStore
import com.example.gleannote.core.contract.Note
import com.example.gleannote.core.contract.NotePage
import com.example.gleannote.core.contract.NotePatchResult
import com.example.gleannote.core.contract.PageResult
import com.example.gleannote.core.contract.PendingOp
import com.example.gleannote.core.contract.Quota
import com.example.gleannote.core.contract.SearchHit
import com.example.gleannote.core.contract.ShareLink
import com.example.gleannote.core.contract.SyncResponse
import com.example.gleannote.core.contract.Tag
import com.example.gleannote.core.contract.TrashItem
import com.example.gleannote.core.contract.UploadRequest
import com.example.gleannote.core.contract.UploadSession
import com.example.gleannote.core.contract.User
import com.example.gleannote.core.contract.VersionDetail
import com.example.gleannote.core.contract.VersionMeta
import com.example.gleannote.core.network.delete
import com.example.gleannote.core.network.get
import com.example.gleannote.core.network.newIdempotencyKey
import com.example.gleannote.core.network.patch
import com.example.gleannote.core.network.post
import com.example.gleannote.core.network.put
import com.google.gson.Gson
import kotlinx.coroutines.flow.Flow

/*
 * 业务仓库：唯一数据出口。在线写成功即落库；网络失败入待同步队列（主文档 5.4 / 6.7）。
 * 短效 URL 不落库；令牌持久化由 ApiClient 内部完成。
 */
class DefaultRepository(
    private val api: ApiClient,
    private val local: LocalStore,
    private val context: Context
) : GleanNoteRepository {
    /*
     * 列表首图缩略图缓存：服务端给的是 10 分钟短效签名地址（API.md 2.9），
     * 只放内存，进程结束即失效；不进 Room（主文档 1.5、5.3）。
     */
    private val thumbCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun rememberThumbs(notes: List<Note>) {
        notes.forEach { note ->
            val url = note.thumbnailUrl
            if (!url.isNullOrBlank()) thumbCache[note.id] = url else thumbCache.remove(note.id)
        }
    }

    override fun thumbnailFor(noteId: String): String? = thumbCache[noteId]


    private val gson = Gson()

    override val isLoggedIn: Flow<Boolean> = api.isLoggedIn
    override fun folders(parentId: String?): Flow<List<Folder>> = local.observeFolders(parentId)
    override fun notes(folderId: String): Flow<List<Note>> = local.observeNotes(folderId)
    override fun allNotes(): Flow<List<Note>> = local.observeAllNotes()
    override fun trash(): Flow<List<TrashItem>> = local.observeTrash()
    override fun observeJob(noteId: String): Flow<ConversionJob?> = local.observeJob(noteId)
    override fun pendingCount(): Flow<Int> = local.observePendingCount()

    /* ---------------- 账号与认证 ---------------- */

    override suspend fun sendCode(email: String, purpose: String): AppResult<Unit> =
        api.post(ApiPaths.AUTH_SEND_CODE, mapOf("email" to email, "purpose" to purpose), anonymous = true)

    override suspend fun register(email: String, code: String, password: String, nickname: String?): AppResult<User> {
        val body = buildMap<String, Any?> {
            put("email", email)
            put("code", code)
            put("password", password)
            if (!nickname.isNullOrBlank()) put("nickname", nickname)
            put("agreements", mapOf("userAgreement" to true, "privacyPolicy" to true))
        }
        return when (val r = api.post<AuthSession>(ApiPaths.AUTH_REGISTER, body, newIdempotencyKey(), anonymous = true)) {
            is AppResult.Success -> {
                api.saveSession(r.data)
                local.clearUserData()
                AppResult.Success(r.data.user)
            }
            is AppResult.Failure -> r
        }
    }

    override suspend fun login(email: String, password: String): AppResult<User> {
        val body = mapOf("email" to email, "password" to password)
        return when (val r = api.post<AuthSession>(ApiPaths.AUTH_LOGIN, body, anonymous = true)) {
            is AppResult.Success -> {
                api.saveSession(r.data)
                local.clearUserData()
                AppResult.Success(r.data.user)
            }
            is AppResult.Failure -> r
        }
    }

    /* 退出登录以本地清理为准：即使服务端吊销失败也不阻断用户离开。 */
    override suspend fun logout(): AppResult<Unit> {
        runCatching { api.post<Unit>(ApiPaths.AUTH_LOGOUT, emptyMap<String, Any?>()) }
        api.invalidateSession()
        local.clearUserData()
        return AppResult.Success(Unit)
    }

    override suspend fun resetPassword(email: String, code: String, newPassword: String): AppResult<Unit> =
        api.post(
            ApiPaths.AUTH_RESET,
            mapOf("email" to email, "code" to code, "newPassword" to newPassword),
            newIdempotencyKey(),
            anonymous = true
        )

    override suspend fun me(): AppResult<User> = api.get(ApiPaths.ME)

    override suspend fun updateNickname(nickname: String): AppResult<User> =
        api.patch(ApiPaths.ME, mapOf("nickname" to nickname))

    /* 注销是物理删除，成功后必须清空本机会话与缓存，避免残留他人数据（PRD FR-01、5.7）。 */
    override suspend fun deleteAccount(code: String): AppResult<Unit> =
        when (val r = api.delete<Unit>(ApiPaths.ME, body = mapOf("code" to code, "confirm" to true))) {
            is AppResult.Success -> {
                local.clearUserData()
                api.invalidateSession()
                AppResult.Success(Unit)
            }
            is AppResult.Failure -> r
        }

    override suspend fun quota(): AppResult<Quota> = when (val r = api.get<QuotaDto>(ApiPaths.QUOTA)) {
        is AppResult.Success -> AppResult.Success(r.data.toModel())
        is AppResult.Failure -> r
    }

    /* ---------------- 目录 ---------------- */

    override suspend fun refreshFolders(parentId: String?, pageToken: String?): AppResult<PageResult<Folder>> {
        val query = mapOf("parentId" to parentId, "pageToken" to pageToken, "pageSize" to "20")
        return when (val r = api.get<FolderPageDto>(ApiPaths.FOLDERS, query)) {
            is AppResult.Success -> {
                local.replaceFolders(parentId, r.data.items)
                AppResult.Success(PageResult(r.data.items, r.data.nextPageToken, r.data.totalSize))
            }
            is AppResult.Failure -> r
        }
    }

    override suspend fun createFolder(parentId: String?, name: String): AppResult<Folder> {
        val body = mapOf("name" to name, "parentId" to parentId)
        return when (val r = api.post<Folder>(ApiPaths.FOLDERS, body, newIdempotencyKey())) {
            is AppResult.Success -> {
                local.upsertFolders(listOf(r.data))
                AppResult.Success(r.data)
            }
            is AppResult.Failure -> r
        }
    }

    override suspend fun renameFolder(id: String, name: String, revision: Int): AppResult<Folder> =
        api.patch("${ApiPaths.FOLDERS}/$id", mapOf("name" to name, "revision" to revision))

    override suspend fun deleteFolder(id: String): AppResult<DeleteResult> {
        val result = api.delete<DeleteResult>("${ApiPaths.FOLDERS}/$id")
        if (result is AppResult.Success) {
            /* 服务端已整棵子树软删除，本地必须同步移除，否则列表会残留（本轮实测缺陷）。 */
            local.removeFolder(id)
            refreshTrash()
        }
        return result
    }

    override suspend fun moveFolder(id: String, targetParentId: String?): AppResult<Folder> =
        api.post("${ApiPaths.FOLDERS}/$id:move", mapOf("targetParentId" to targetParentId))

    override suspend fun folderPath(id: String): AppResult<FolderPath> =
        api.get("${ApiPaths.FOLDERS}/$id/path")

    /* ---------------- 笔记 ---------------- */

    override suspend fun refreshNotes(folderId: String, pageToken: String?, orderBy: String?): AppResult<PageResult<Note>> {
        val query = mapOf(
            "folderId" to folderId,
            "pageToken" to pageToken,
            "orderBy" to orderBy,
            "pageSize" to "20"
        )
        return when (val r = api.get<NotePageDto>(ApiPaths.NOTES, query)) {
            is AppResult.Success -> {
                rememberThumbs(r.data.items)
                local.upsertNotes(r.data.items)
                AppResult.Success(PageResult(r.data.items, r.data.nextPageToken, r.data.totalSize))
            }
            is AppResult.Failure -> r
        }
    }

    override suspend fun createNote(folderId: String, title: String, content: String): AppResult<Note> {
        val body = mapOf("folderId" to folderId, "title" to title, "content" to content)
        val key = newIdempotencyKey()
        return when (val r = api.post<Note>(ApiPaths.NOTES, body, key)) {
            is AppResult.Success -> {
                local.saveNote(r.data)
                AppResult.Success(r.data)
            }
            is AppResult.Failure -> {
                enqueueIfOffline("POST", ApiPaths.NOTES, body, key, "NOTE", "")
                r
            }
        }
    }

    override suspend fun getNote(id: String): AppResult<Note> =
        when (val r = api.get<Note>("${ApiPaths.NOTES}/$id")) {
            is AppResult.Success -> {
                local.saveNote(r.data)
                AppResult.Success(r.data)
            }
            is AppResult.Failure -> r
        }

    override suspend fun saveNote(
        id: String,
        title: String?,
        content: String?,
        revision: Int,
        saveSource: String
    ): AppResult<NotePatchResult> {
        val body = buildMap<String, Any?> {
            title?.let { put("title", it) }
            content?.let { put("content", it) }
            put("revision", revision)
            put("saveSource", saveSource)
        }
        val key = newIdempotencyKey()
        return when (val r = api.patch<NotePatchDto>("${ApiPaths.NOTES}/$id", body, key)) {
            is AppResult.Success -> {
                local.saveNote(r.data.note)
                AppResult.Success(NotePatchResult(r.data.note, r.data.conflictCopyId))
            }
            is AppResult.Failure -> {
                enqueueIfOffline("PATCH", "${ApiPaths.NOTES}/$id", body, key, "NOTE", id)
                r
            }
        }
    }

    override suspend fun deleteNote(id: String): AppResult<DeleteResult> =
        when (val r = api.delete<DeleteResult>("${ApiPaths.NOTES}/$id")) {
            is AppResult.Success -> {
                local.removeNote(id)
                refreshTrash()
                AppResult.Success(r.data)
            }
            is AppResult.Failure -> r
        }

    override suspend fun moveNote(id: String, folderId: String): AppResult<Note> =
        api.post("${ApiPaths.NOTES}/$id:move", mapOf("folderId" to folderId))

    override suspend fun search(query: String, folderId: String?, tagIds: List<String>): AppResult<PageResult<SearchHit>> {
        val q = mapOf("query" to query, "folderId" to folderId, "pageSize" to "20")
        val multi = if (tagIds.isEmpty()) emptyMap() else mapOf("tagIds" to tagIds)
        return when (val r = api.get<SearchPageDto>(ApiPaths.NOTES_SEARCH, q, queryMulti = multi)) {
            is AppResult.Success -> AppResult.Success(PageResult(r.data.items, r.data.nextPageToken, r.data.totalSize))
            is AppResult.Failure -> r
        }
    }

    /* ---------------- 图片与转换 ---------------- */

    override suspend fun uploadImages(items: List<UploadRequest>): AppResult<List<UploadSession>> =
        when (val r = api.post<UploadSessionListDto>(ApiPaths.UPLOADS, mapOf("items" to items))) {
            is AppResult.Success -> AppResult.Success(r.data.items)
            is AppResult.Failure -> r
        }

    override suspend fun uploadBinary(session: UploadSession, localPath: String): AppResult<Unit> {
        val bytes = runCatching { java.io.File(localPath).readBytes() }.getOrNull()
            ?: return AppResult.Failure(com.example.gleannote.core.contract.AppError("FILE_MISSING", 0, "图片文件不存在，请重新选择"))
        val contentType = session.headers["Content-Type"] ?: "image/jpeg"
        return api.putBinary(session.putUrl, bytes, contentType)
    }

    override suspend fun completeUpload(uploadId: String, sha256: String): AppResult<CompletedUpload> =
        api.post("${ApiPaths.UPLOADS}/$uploadId:complete", mapOf("sha256" to sha256))

    override suspend fun createFromPages(folderId: String, uploadIds: List<String>, title: String?): AppResult<CreateFromPagesResult> {
        val body = buildMap<String, Any?> {
            put("folderId", folderId)
            put("uploadIds", uploadIds)
            if (!title.isNullOrBlank()) put("title", title)
        }
        return when (val r = api.post<CreateFromPagesDto>(ApiPaths.NOTES_CREATE_FROM_PAGES, body, newIdempotencyKey())) {
            is AppResult.Success -> {
                local.saveNote(r.data.note)
                local.saveJob(r.data.conversionJob)
                AppResult.Success(CreateFromPagesResult(r.data.note, r.data.conversionJob, r.data.quota.remaining, r.data.quota.charged))
            }
            is AppResult.Failure -> r
        }
    }

    override suspend fun pages(noteId: String): AppResult<List<NotePage>> =
        when (val r = api.get<PageListDto>("${ApiPaths.NOTES}/$noteId/pages")) {
            is AppResult.Success -> AppResult.Success(r.data.items)
            is AppResult.Failure -> r
        }

    override suspend fun pageOriginal(noteId: String, pageId: String): AppResult<NotePage> =
        api.get("${ApiPaths.NOTES}/$noteId/pages/$pageId")

    override suspend fun fetchJob(noteId: String): AppResult<ConversionJob> =
        when (val r = api.get<ConversionJob>("${ApiPaths.NOTES}/$noteId/conversionJob")) {
            is AppResult.Success -> {
                local.saveJob(r.data)
                AppResult.Success(r.data)
            }
            is AppResult.Failure -> r
        }

    override suspend fun cancelJob(noteId: String): AppResult<ConversionJob> =
        api.post("${ApiPaths.NOTES}/$noteId/conversionJob:cancel")

    override suspend fun retryJob(noteId: String): AppResult<ConversionJob> =
        api.post("${ApiPaths.NOTES}/$noteId/conversionJob:retry", null, newIdempotencyKey())

    override suspend fun appendPages(noteId: String, uploadIds: List<String>): AppResult<ConversionJob> =
        when (val r = api.post<AppendDto>("${ApiPaths.NOTES}/$noteId:appendPages", mapOf("uploadIds" to uploadIds), newIdempotencyKey())) {
            is AppResult.Success -> {
                local.saveJob(r.data.conversionJob)
                AppResult.Success(r.data.conversionJob)
            }
            is AppResult.Failure -> r
        }

    /* ---------------- 回收站 ---------------- */

    override suspend fun refreshTrash(): AppResult<PageResult<TrashItem>> =
        when (val r = api.get<TrashPageDto>(ApiPaths.TRASH, mapOf("pageSize" to "20"))) {
            is AppResult.Success -> {
                local.replaceTrash(r.data.items)
                AppResult.Success(PageResult(r.data.items, r.data.nextPageToken, r.data.totalSize))
            }
            is AppResult.Failure -> r
        }

    /*
     * 恢复与彻底删除会同时改变回收站与列表，而 UI 只读本地表（主文档 5.4），
     * 因此成功后必须重新拉取；否则界面会停留在旧状态（本轮实测缺陷）。
     */
    private suspend fun refreshAfterTrashChange(folderId: String? = null) {
        refreshTrash()
        refreshFolders(null)
        if (!folderId.isNullOrBlank()) refreshNotes(folderId)
    }

    override suspend fun undeleteNote(id: String): AppResult<Note> {
        val result = api.post<Note>("${ApiPaths.NOTES}/$id:undelete")
        if (result is AppResult.Success) refreshAfterTrashChange(result.data.folderId)
        return result
    }

    override suspend fun undeleteFolder(id: String): AppResult<Folder> {
        val result = api.post<Folder>("${ApiPaths.FOLDERS}/$id:undelete")
        if (result is AppResult.Success) refreshAfterTrashChange(result.data.parentId)
        return result
    }

    override suspend fun expungeNote(id: String): AppResult<Unit> {
        val result = api.post<Unit>("${ApiPaths.NOTES}/$id:expunge")
        if (result is AppResult.Success) {
            local.removeNote(id)
            refreshAfterTrashChange()
        }
        return result
    }

    override suspend fun expungeFolder(id: String): AppResult<Unit> {
        val result = api.post<Unit>("${ApiPaths.FOLDERS}/$id:expunge")
        if (result is AppResult.Success) {
            local.removeFolder(id)
            refreshAfterTrashChange()
        }
        return result
    }

    /* ---------------- 同步 ---------------- */

    override suspend fun sync(): AppResult<Unit> {
        var cursor = local.cursor()
        var hasMore = true
        while (hasMore) {
            val query = mapOf("since" to cursor.toString(), "pageSize" to "200")
            when (val r = api.get<SyncResponse>(ApiPaths.SYNC_CHANGES, query)) {
                is AppResult.Success -> {
                    r.data.changes.forEach { change ->
                        when {
                            change.op == "DELETE" -> local.removeNote(change.resourceId)
                            change.resourceType == "NOTE" -> {
                                (api.get<Note>("${ApiPaths.NOTES}/${change.resourceId}") as? AppResult.Success)
                                    ?.let { local.saveNote(it.data) }
                            }
                            change.resourceType == "FOLDER" -> {
                                (api.get<Folder>("${ApiPaths.FOLDERS}/${change.resourceId}") as? AppResult.Success)
                                    ?.let { local.upsertFolders(listOf(it.data)) }
                            }
                        }
                    }
                    /* 先落库再推进游标（API.md 5.10 规则 1）。 */
                    local.setCursor(r.data.nextCursor)
                    cursor = r.data.nextCursor
                    hasMore = r.data.hasMore
                }
                is AppResult.Failure -> {
                    if (r.error.code == "SYNC_CURSOR_EXPIRED") {
                        local.setCursor(0)
                        cursor = 0
                        continue
                    }
                    return r
                }
            }
        }
        replayPending()
        return AppResult.Success(Unit)
    }

    private suspend fun replayPending() {
        local.pendingOps().forEach { op ->
            val body = if (op.bodyJson.isBlank()) null else gson.fromJson(op.bodyJson, Any::class.java)
            val result: AppResult<Any?> = when (op.method.uppercase()) {
                "POST" -> api.post(op.path, body, op.idempotencyKey)
                "PATCH" -> api.patch(op.path, body, op.idempotencyKey)
                "DELETE" -> api.delete(op.path)
                else -> api.get(op.path)
            }
            if (result is AppResult.Success) local.removeOp(op.id) else return
        }
    }

    private suspend fun enqueueIfOffline(
        method: String,
        path: String,
        body: Any?,
        key: String,
        resourceType: String,
        resourceId: String
    ) {
        local.enqueue(
            PendingOp(
                seq = System.currentTimeMillis(),
                resourceType = resourceType,
                resourceId = resourceId,
                method = method,
                path = path,
                bodyJson = gson.toJson(body),
                idempotencyKey = key,
                createdAt = System.currentTimeMillis()
            )
        )
    }

    override suspend fun saveDraft(draft: Draft) = local.saveDraft(draft)
    override suspend fun draft(noteId: String): Draft? = local.draft(noteId)
    override suspend fun clearDraft(noteId: String) = local.clearDraft(noteId)
    override suspend fun clearUserData() = local.clearUserData()

    /* ---------------- P1 ---------------- */

    override suspend fun tags(): AppResult<List<Tag>> =
        when (val r = api.get<TagListDto>(ApiPaths.TAGS)) {
            is AppResult.Success -> AppResult.Success(r.data.items)
            is AppResult.Failure -> r
        }

    override suspend fun createTag(name: String): AppResult<Tag> =
        api.post(ApiPaths.TAGS, mapOf("name" to name), newIdempotencyKey())

    override suspend fun renameTag(id: String, name: String): AppResult<Tag> =
        api.patch("${ApiPaths.TAGS}/$id", mapOf("name" to name))

    override suspend fun deleteTag(id: String): AppResult<Unit> =
        api.delete("${ApiPaths.TAGS}/$id")

    override suspend fun setNoteTags(noteId: String, tagIds: List<String>): AppResult<Unit> =
        api.put("${ApiPaths.NOTES}/$noteId/tags", mapOf("tagIds" to tagIds))

    override suspend fun versions(noteId: String): AppResult<List<VersionMeta>> =
        when (val r = api.get<VersionListDto>("${ApiPaths.NOTES}/$noteId/versions")) {
            is AppResult.Success -> AppResult.Success(r.data.items)
            is AppResult.Failure -> r
        }

    override suspend fun versionDetail(noteId: String, versionId: String): AppResult<VersionDetail> =
        api.get("${ApiPaths.NOTES}/$noteId/versions/$versionId")

    override suspend fun restoreVersion(noteId: String, versionId: String): AppResult<Note> =
        api.post("${ApiPaths.NOTES}/$noteId/versions/$versionId:restore", null, newIdempotencyKey())

    override suspend fun shareLink(noteId: String): AppResult<ShareLink> =
        api.get("${ApiPaths.NOTES}/$noteId/shareLink")

    override suspend fun enableShare(noteId: String): AppResult<ShareLink> =
        api.post("${ApiPaths.NOTES}/$noteId/shareLink", null, newIdempotencyKey())

    override suspend fun disableShare(noteId: String): AppResult<Unit> =
        api.delete("${ApiPaths.NOTES}/$noteId/shareLink")

    override suspend fun startExport(noteId: String?, folderId: String?, format: String): AppResult<ExportJob> {
        val body = buildMap<String, Any?> {
            if (noteId != null) put("noteId", noteId)
            if (folderId != null) put("folderId", folderId)
            put("format", format)
        }
        return api.post(ApiPaths.EXPORTS, body, newIdempotencyKey())
    }

    override suspend fun exportStatus(id: String): AppResult<ExportJob> =
        api.get("${ApiPaths.EXPORTS}/$id")

    override suspend fun startImport(folderId: String, mode: String, uploadIds: List<String>): AppResult<ImportJob> =
        api.post(
            ApiPaths.IMPORTS,
            mapOf("folderId" to folderId, "mode" to mode, "uploadIds" to uploadIds),
            newIdempotencyKey()
        )

    override suspend fun importStatus(id: String): AppResult<ImportJob> =
        api.get("${ApiPaths.IMPORTS}/$id")

    /* ---------------- DTO：服务端响应结构与契约模型不同构的部分 ---------------- */

    data class FolderPageDto(
        val items: List<Folder> = emptyList(),
        val nextPageToken: String = "",
        val totalSize: Int = 0
    )

    data class NotePageDto(
        val items: List<Note> = emptyList(),
        val nextPageToken: String = "",
        val totalSize: Int = 0
    )

    data class TrashPageDto(
        val items: List<TrashItem> = emptyList(),
        val nextPageToken: String = "",
        val totalSize: Int = 0
    )

    data class SearchPageDto(
        val items: List<SearchHit> = emptyList(),
        val nextPageToken: String = "",
        val totalSize: Int = 0
    )

    data class QuotaDto(
        val quotaDate: String = "",
        val dailyLimit: Int = 50,
        val remaining: Int = 50,
        val resetAt: String = "",
        val conversionRunning: Boolean = false,
        val storage: StorageDto? = null
    ) {
        fun toModel() = Quota(
            quotaDate = quotaDate,
            dailyLimit = dailyLimit,
            remaining = remaining,
            resetAt = resetAt,
            conversionRunning = conversionRunning,
            storageUsedBytes = storage?.usedBytes ?: 0,
            storageLimitBytes = storage?.limitBytes ?: 0,
            storageUsedPercent = storage?.usedPercent ?: 0
        )
    }

    data class StorageDto(val usedBytes: Long = 0, val limitBytes: Long = 0, val usedPercent: Int = 0)

    data class NotePatchDto(val note: Note = Note(), val conflictCopyId: String? = null)

    data class CreateFromPagesDto(
        val note: Note = Note(),
        val conversionJob: ConversionJob = ConversionJob(),
        val quota: ChargeDto = ChargeDto()
    )

    data class ChargeDto(val remaining: Int = 0, val charged: Int = 0)

    data class AppendDto(val conversionJob: ConversionJob = ConversionJob())

    data class UploadSessionListDto(val items: List<UploadSession> = emptyList())
    data class PageListDto(val items: List<NotePage> = emptyList())
    data class TagListDto(val items: List<Tag> = emptyList())
    data class VersionListDto(val items: List<VersionMeta> = emptyList())
}

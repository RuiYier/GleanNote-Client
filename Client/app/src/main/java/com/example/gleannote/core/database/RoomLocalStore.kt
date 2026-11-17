package com.example.gleannote.core.database

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import com.example.gleannote.core.contract.ConversionJob
import com.example.gleannote.core.contract.Draft
import com.example.gleannote.core.contract.Folder
import com.example.gleannote.core.contract.LocalStore
import com.example.gleannote.core.contract.Note
import com.example.gleannote.core.contract.PendingOp
import com.example.gleannote.core.contract.Tag
import com.example.gleannote.core.contract.TrashItem
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/* 本地表结构。短效 URL（thumbUrl / originalUrl / downloadUrl）一律不建列（主文档 1.5、5.3）。 */

@Entity(tableName = "folders")
data class FolderEntity(
    @PrimaryKey val id: String,
    val parentId: String?,
    val name: String,
    val depth: Int,
    val revision: Int,
    val childCount: Int,
    val noteCount: Int,
    val createdAt: String,
    val updatedAt: String
)

@Entity(tableName = "notes")
data class NoteEntity(
    @PrimaryKey val id: String,
    val folderId: String,
    val folderPath: String,
    val title: String,
    val state: String,
    val revision: Int,
    val charCount: Int,
    val pageCount: Int,
    val tagsJson: String,
    val isConflictCopy: Boolean,
    val content: String,
    val shareEnabled: Boolean,
    val createdAt: String,
    val updatedAt: String,
    val localOnly: Boolean,
    val pendingSync: Boolean
)

@Entity(tableName = "note_pages")
data class PageEntity(
    @PrimaryKey val id: String,
    val noteId: String,
    val pageIndex: Int,
    val width: Int,
    val height: Int,
    val bytes: Long,
    val createdAt: String
)

@Entity(tableName = "trash_items")
data class TrashEntity(
    @PrimaryKey val batchId: String,
    val itemType: String,
    val itemId: String,
    val name: String,
    val originalPath: String,
    val folderCount: Int,
    val noteCount: Int,
    val deletedAt: String,
    val purgeAt: String,
    val remainingDays: Int
)

@Entity(tableName = "conversion_jobs")
data class JobEntity(
    @PrimaryKey val id: String,
    val noteId: String,
    val kind: String,
    val state: String,
    val phase: String,
    val totalPages: Int,
    val processedPages: Int,
    val failureCode: String?,
    val failureUserMessage: String?,
    val quotaCharged: Int,
    val createdAt: String,
    val startedAt: String?,
    val finishedAt: String?
)

@Entity(tableName = "pending_operations")
data class PendingOpEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val seq: Long,
    val resourceType: String,
    val resourceId: String,
    val method: String,
    val path: String,
    val bodyJson: String,
    val idempotencyKey: String,
    val createdAt: Long,
    val state: String,
    val retryCount: Int,
    val lastError: String
)

@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val id: Int = 1,
    val cursor: Long,
    val lastSyncAt: Long,
    val lastError: String
)

@Entity(tableName = "editor_drafts")
data class DraftEntity(
    @PrimaryKey val noteId: String,
    val title: String,
    val content: String,
    val baseRevision: Int,
    val updatedAt: Long
)

@Entity(tableName = "quota_cache")
data class QuotaEntity(
    @PrimaryKey val id: Int = 1,
    val quotaDate: String,
    val dailyLimit: Int,
    val remaining: Int,
    val resetAt: String,
    val conversionRunning: Boolean,
    val usedBytes: Long,
    val limitBytes: Long,
    val usedPercent: Int
)

@Dao
interface FolderDao {
    @Query("SELECT * FROM folders WHERE (:parentId IS NULL AND parentId IS NULL) OR parentId = :parentId ORDER BY name ASC")
    fun observe(parentId: String?): Flow<List<FolderEntity>>

    @Upsert
    suspend fun upsertAll(items: List<FolderEntity>)

    @Query("DELETE FROM folders")
    suspend fun clear()

    @Query("DELETE FROM folders WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM folders WHERE (:parentId IS NULL AND parentId IS NULL) OR parentId = :parentId")
    suspend fun deleteByParent(parentId: String?)

    @Query("DELETE FROM folders WHERE ((:parentId IS NULL AND parentId IS NULL) OR parentId = :parentId) AND id NOT IN (:keepIds)")
    suspend fun pruneByParent(parentId: String?, keepIds: List<String>)

    /* 与响应保持一致：先裁剪再写入，避免别处已删除的目录残留在本地（本轮实测缺陷）。 */
    @androidx.room.Transaction
    suspend fun replaceByParent(parentId: String?, items: List<FolderEntity>) {
        if (items.isEmpty()) {
            deleteByParent(parentId)
        } else {
            pruneByParent(parentId, items.map { it.id })
            upsertAll(items)
        }
    }
}

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes WHERE folderId = :folderId AND state != 'DELETED' ORDER BY updatedAt DESC")
    fun observeByFolder(folderId: String): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE state != 'DELETED' ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): NoteEntity?

    @Upsert
    suspend fun upsert(note: NoteEntity)

    @Upsert
    suspend fun upsertAll(items: List<NoteEntity>)

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM notes")
    suspend fun clear()
}

@Dao
interface PageDao {
    @Upsert
    suspend fun upsertAll(items: List<PageEntity>)

    @Query("SELECT * FROM note_pages WHERE noteId = :noteId ORDER BY pageIndex ASC")
    suspend fun byNote(noteId: String): List<PageEntity>

    @Query("DELETE FROM note_pages")
    suspend fun clear()
}

@Dao
interface TrashDao {
    @Query("SELECT * FROM trash_items ORDER BY deletedAt DESC")
    fun observeAll(): Flow<List<TrashEntity>>

    @Upsert
    suspend fun upsertAll(items: List<TrashEntity>)

    @Query("DELETE FROM trash_items")
    suspend fun clear()

    /* 先清空再写入，保证与响应完全一致（恢复/彻底删除后不会残留旧行）。 */
    @androidx.room.Transaction
    suspend fun replaceAll(items: List<TrashEntity>) {
        clear()
        upsertAll(items)
    }
}

@Dao
interface JobDao {
    @Query("SELECT * FROM conversion_jobs WHERE noteId = :noteId ORDER BY createdAt DESC LIMIT 1")
    fun observeByNote(noteId: String): Flow<JobEntity?>

    @Query("SELECT * FROM conversion_jobs WHERE noteId = :noteId ORDER BY createdAt DESC LIMIT 1")
    suspend fun getByNote(noteId: String): JobEntity?

    @Upsert
    suspend fun upsert(job: JobEntity)

    @Query("DELETE FROM conversion_jobs")
    suspend fun clear()
}

@Dao
interface PendingOpDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(op: PendingOpEntity): Long

    @Query("SELECT * FROM pending_operations WHERE state = 'PENDING' ORDER BY seq ASC")
    suspend fun pending(): List<PendingOpEntity>

    @Query("DELETE FROM pending_operations WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM pending_operations WHERE state = 'PENDING'")
    fun countFlow(): Flow<Int>

    @Query("DELETE FROM pending_operations")
    suspend fun clear()
}

@Dao
interface SyncStateDao {
    @Query("SELECT * FROM sync_state WHERE id = 1")
    suspend fun get(): SyncStateEntity?

    @Upsert
    suspend fun upsert(state: SyncStateEntity)

    @Query("DELETE FROM sync_state")
    suspend fun clear()
}

@Dao
interface DraftDao {
    @Query("SELECT * FROM editor_drafts WHERE noteId = :noteId")
    suspend fun get(noteId: String): DraftEntity?

    @Upsert
    suspend fun upsert(draft: DraftEntity)

    @Query("DELETE FROM editor_drafts WHERE noteId = :noteId")
    suspend fun delete(noteId: String)

    @Query("DELETE FROM editor_drafts")
    suspend fun clear()
}

@Dao
interface QuotaDao {
    @Query("SELECT * FROM quota_cache WHERE id = 1")
    suspend fun get(): QuotaEntity?

    @Upsert
    suspend fun upsert(quota: QuotaEntity)

    @Query("DELETE FROM quota_cache")
    suspend fun clear()
}

@Database(
    entities = [
        FolderEntity::class, NoteEntity::class, PageEntity::class, TrashEntity::class,
        JobEntity::class, PendingOpEntity::class, SyncStateEntity::class, DraftEntity::class, QuotaEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class GleanNoteDatabase : RoomDatabase() {
    abstract fun folders(): FolderDao
    abstract fun notes(): NoteDao
    abstract fun pages(): PageDao
    abstract fun trash(): TrashDao
    abstract fun jobs(): JobDao
    abstract fun pendingOps(): PendingOpDao
    abstract fun syncState(): SyncStateDao
    abstract fun drafts(): DraftDao
    abstract fun quota(): QuotaDao

    companion object {
        @Volatile
        private var instance: GleanNoteDatabase? = null

        fun get(context: Context): GleanNoteDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, GleanNoteDatabase::class.java, "gleannote.db")
                .build()
                .also { instance = it }
        }
    }
}

/* LocalStore 实现：UI 只观察 Flow；网络响应先落库再由 Flow 驱动界面（主文档 3.2 / 5.1）。 */
class RoomLocalStore(private val context: Context) : LocalStore {

    private val db by lazy { GleanNoteDatabase.get(context) }
    private val gson = Gson()
    private val tagListType = object : TypeToken<List<Tag>>() {}.type

    override fun observeFolders(parentId: String?): Flow<List<Folder>> =
        db.folders().observe(parentId).map { list -> list.map { it.toModel() } }

    override fun observeNotes(folderId: String): Flow<List<Note>> =
        db.notes().observeByFolder(folderId).map { list -> list.map { it.toModel() } }

    override fun observeAllNotes(): Flow<List<Note>> =
        db.notes().observeAll().map { list -> list.map { it.toModel() } }

    override fun observeTrash(): Flow<List<TrashItem>> =
        db.trash().observeAll().map { list -> list.map { it.toModel() } }

    override fun observeJob(noteId: String): Flow<ConversionJob?> =
        db.jobs().observeByNote(noteId).map { it?.toModel() }

    override fun observePendingCount(): Flow<Int> = db.pendingOps().countFlow()

    override suspend fun upsertFolders(items: List<Folder>) =
        db.folders().upsertAll(items.map { it.toEntity() })

    override suspend fun upsertNotes(items: List<Note>) =
        db.notes().upsertAll(items.map { it.toEntity() })

    override suspend fun getNote(id: String): Note? = db.notes().getById(id)?.toModel()

    override suspend fun saveNote(note: Note) = db.notes().upsert(note.toEntity())

    override suspend fun removeNote(id: String) = db.notes().deleteById(id)

    override suspend fun replaceFolders(parentId: String?, items: List<Folder>) =
        db.folders().replaceByParent(parentId, items.map { it.toEntity() })

    override suspend fun removeFolder(id: String) = db.folders().deleteById(id)

    override suspend fun upsertTrash(items: List<TrashItem>) =
        db.trash().upsertAll(items.map { it.toEntity() })

    override suspend fun replaceTrash(items: List<TrashItem>) =
        db.trash().replaceAll(items.map { it.toEntity() })

    override suspend fun saveJob(job: ConversionJob) = db.jobs().upsert(job.toEntity())

    override suspend fun enqueue(op: PendingOp): Long = db.pendingOps().insert(
        PendingOpEntity(
            seq = op.seq.takeIf { it > 0 } ?: System.currentTimeMillis(),
            resourceType = op.resourceType,
            resourceId = op.resourceId,
            method = op.method,
            path = op.path,
            bodyJson = op.bodyJson,
            idempotencyKey = op.idempotencyKey,
            createdAt = op.createdAt.takeIf { it > 0 } ?: System.currentTimeMillis(),
            state = op.state,
            retryCount = op.retryCount,
            lastError = op.lastError
        )
    )

    override suspend fun pendingOps(): List<PendingOp> =
        db.pendingOps().pending().map { it.toModel() }

    override suspend fun removeOp(id: Long) = db.pendingOps().delete(id)

    override suspend fun cursor(): Long = db.syncState().get()?.cursor ?: 0L

    override suspend fun setCursor(value: Long) =
        db.syncState().upsert(SyncStateEntity(1, value, System.currentTimeMillis(), ""))

    override suspend fun saveDraft(draft: Draft) = db.drafts().upsert(
        DraftEntity(draft.noteId, draft.title, draft.content, draft.baseRevision, draft.updatedAt)
    )

    override suspend fun draft(noteId: String): Draft? = db.drafts().get(noteId)?.let {
        Draft(it.noteId, it.title, it.content, it.baseRevision, it.updatedAt)
    }

    override suspend fun clearDraft(noteId: String) = db.drafts().delete(noteId)

    override suspend fun clearUserData() {
        db.notes().clear()
        db.folders().clear()
        db.pages().clear()
        db.trash().clear()
        db.jobs().clear()
        db.pendingOps().clear()
        db.syncState().clear()
        db.drafts().clear()
        db.quota().clear()
    }

    /* 以下为映射工具：保持实体与契约模型解耦，避免 UI 直接依赖 Room 类型。 */
    private fun FolderEntity.toModel() = Folder(
        id, parentId, name, depth, revision, childCount, noteCount, createdAt, updatedAt
    )

    private fun Folder.toEntity() = FolderEntity(
        id, parentId, name, depth, revision, childCount, noteCount, createdAt, updatedAt
    )

    private fun NoteEntity.toModel() = Note(
        id = id, folderId = folderId, folderPath = folderPath, title = title, state = state,
        revision = revision, charCount = charCount, pageCount = pageCount,
        tags = runCatching { gson.fromJson<List<Tag>>(tagsJson, tagListType) }.getOrNull() ?: emptyList(),
        isConflictCopy = isConflictCopy, content = content, shareEnabled = shareEnabled,
        createdAt = createdAt, updatedAt = updatedAt, localOnly = localOnly, pendingSync = pendingSync
    )

    private fun Note.toEntity() = NoteEntity(
        id = id, folderId = folderId, folderPath = folderPath, title = title, state = state,
        revision = revision, charCount = charCount, pageCount = pageCount,
        tagsJson = gson.toJson(tags), isConflictCopy = isConflictCopy, content = content,
        shareEnabled = shareEnabled, createdAt = createdAt, updatedAt = updatedAt,
        localOnly = localOnly, pendingSync = pendingSync
    )

    private fun TrashEntity.toModel() = TrashItem(
        batchId, itemType, itemId, name, originalPath, folderCount, noteCount, deletedAt, purgeAt, remainingDays
    )

    private fun TrashItem.toEntity() = TrashEntity(
        batchId, itemType, itemId, name, originalPath, folderCount, noteCount, deletedAt, purgeAt, remainingDays
    )

    private fun JobEntity.toModel() = ConversionJob(
        id, noteId, kind, state, phase, totalPages, processedPages, failureCode,
        failureUserMessage, quotaCharged, createdAt, startedAt, finishedAt
    )

    private fun ConversionJob.toEntity() = JobEntity(
        id, noteId, kind, state, phase, totalPages, processedPages, failureCode,
        failureUserMessage, quotaCharged, createdAt, startedAt, finishedAt
    )

    private fun PendingOpEntity.toModel() = PendingOp(
        id, seq, resourceType, resourceId, method, path, bodyJson, idempotencyKey, createdAt, state, retryCount, lastError
    )
}

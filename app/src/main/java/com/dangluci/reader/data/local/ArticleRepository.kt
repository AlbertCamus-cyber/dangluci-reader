package com.dangluci.reader.data.local

import android.content.ContentValues
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

data class Folder(val id: Long, val name: String, val articleCount: Int)

data class ArticleSummary(
    val id: Long, val title: String, val preview: String, val openedAt: Long, val progressParagraph: Int,
    /** 搜索结果里显示所在文件夹 */
    val folderName: String? = null,
)
data class Article(
    val id: Long, val title: String, val content: String, val progressParagraph: Int, val progressOffset: Int,
    /** 临时阅读：不进文章列表，退出阅读页即删除 */
    val temporary: Boolean = false,
    val folderId: Long? = null,
    /** epub 章节序号；0 = 普通文章 */
    val position: Int = 0,
)

/** 章节导航：下一章 */
data class ChapterRef(val id: Long, val title: String)

class ArticleRepository(private val helper: UserDb) {
    /** 任何文章/文件夹变动都 +1；页面据此重新查询自己那一层 */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()
    private fun bump() { _version.value++ }

    /** 某一层的文章：folderId = null 是首页（未归类） */
    suspend fun list(folderId: Long?): List<ArticleSummary> = withContext(Dispatchers.IO) {
        val (where, args) = if (folderId == null) "folder_id IS NULL" to emptyArray<String>() else "folder_id = ?" to arrayOf(folderId.toString())
        helper.readableDatabase.rawQuery(
            // 书的章节（position > 0）按章节顺序排在前面，其余按最近打开
            """SELECT id, title, substr(content, 1, 120), opened_at, progress_paragraph FROM articles
               WHERE temporary = 0 AND $where ORDER BY position = 0, position, opened_at DESC""", args
        ).use { c ->
            buildList { while (c.moveToNext()) add(ArticleSummary(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3), c.getInt(4))) }
        }
    }

    /** 搜标题和正文，跨所有文件夹 */
    suspend fun search(q: String): List<ArticleSummary> = withContext(Dispatchers.IO) {
        val like = "%" + q.trim().replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%"
        helper.readableDatabase.rawQuery(
            """SELECT a.id, a.title, substr(a.content, 1, 120), a.opened_at, a.progress_paragraph, f.name
               FROM articles a LEFT JOIN folders f ON f.id = a.folder_id
               WHERE a.temporary = 0 AND (a.title LIKE ? ESCAPE '!' OR a.content LIKE ? ESCAPE '!')
               ORDER BY a.title LIKE ? ESCAPE '!' DESC, a.opened_at DESC LIMIT 100""", arrayOf(like, like, like)
        ).use { c ->
            buildList { while (c.moveToNext()) add(ArticleSummary(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3), c.getInt(4), c.getString(5))) }
        }
    }

    suspend fun rename(id: Long, title: String) = withContext(Dispatchers.IO) {
        if (title.isBlank()) return@withContext
        helper.writableDatabase.update("articles", ContentValues().apply { put("title", title.trim()) }, "id = ?", arrayOf(id.toString()))
        bump()
    }

    /** 同一文件夹里的下一章（只对 epub 拆出来的章节有效） */
    suspend fun nextChapter(a: Article): ChapterRef? = withContext(Dispatchers.IO) {
        if (a.position <= 0 || a.folderId == null) return@withContext null
        helper.readableDatabase.rawQuery(
            "SELECT id, title FROM articles WHERE folder_id = ? AND position > ? AND temporary = 0 ORDER BY position LIMIT 1",
            arrayOf(a.folderId.toString(), a.position.toString())
        ).use { if (it.moveToFirst()) ChapterRef(it.getLong(0), it.getString(1)) else null }
    }

    suspend fun folders(): List<Folder> = withContext(Dispatchers.IO) {
        helper.readableDatabase.rawQuery(
            """SELECT f.id, f.name, (SELECT COUNT(*) FROM articles a WHERE a.folder_id = f.id AND a.temporary = 0)
               FROM folders f ORDER BY f.created_at DESC""", null
        ).use { c -> buildList { while (c.moveToNext()) add(Folder(c.getLong(0), c.getString(1), c.getInt(2))) } }
    }

    suspend fun folderName(id: Long): String? = withContext(Dispatchers.IO) {
        helper.readableDatabase.rawQuery("SELECT name FROM folders WHERE id = ?", arrayOf(id.toString()))
            .use { if (it.moveToFirst()) it.getString(0) else null }
    }

    suspend fun createFolder(name: String): Long = withContext(Dispatchers.IO) {
        helper.writableDatabase.insertOrThrow("folders", null, ContentValues().apply {
            put("name", name.trim().ifBlank { "新文件夹" }); put("created_at", System.currentTimeMillis())
        }).also { bump() }
    }

    suspend fun renameFolder(id: Long, name: String) = withContext(Dispatchers.IO) {
        if (name.isBlank()) return@withContext
        helper.writableDatabase.update("folders", ContentValues().apply { put("name", name.trim()) }, "id = ?", arrayOf(id.toString()))
        bump()
    }

    suspend fun articleIdsIn(folderId: Long): List<Long> = withContext(Dispatchers.IO) {
        helper.readableDatabase.rawQuery("SELECT id FROM articles WHERE folder_id = ?", arrayOf(folderId.toString()))
            .use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }
    }

    /** 删文件夹本身；还留在里面的文章退回首页（要连文章一起删，先由 AppContainer 逐篇删掉） */
    suspend fun deleteFolder(id: Long) = withContext(Dispatchers.IO) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("UPDATE articles SET folder_id = NULL WHERE folder_id = ?", arrayOf(id))
            db.delete("folders", "id = ?", arrayOf(id.toString()))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        bump()
    }

    suspend fun moveToFolder(articleId: Long, folderId: Long?) = withContext(Dispatchers.IO) {
        helper.writableDatabase.update("articles", ContentValues().apply {
            if (folderId == null) putNull("folder_id") else put("folder_id", folderId)
        }, "id = ?", arrayOf(articleId.toString()))
        bump()
    }

    suspend fun add(title: String, content: String, temporary: Boolean = false, folderId: Long? = null, position: Int = 0): Long = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val id = helper.writableDatabase.insertOrThrow("articles", null, ContentValues().apply {
            put("title", title.ifBlank { content.lineSequence().firstOrNull { it.isNotBlank() }?.take(40) ?: "未命名" })
            put("content", content)
            put("created_at", now); put("opened_at", now)
            put("temporary", if (temporary) 1 else 0)
            folderId?.let { put("folder_id", it) }
            put("position", position)
        })
        bump(); id
    }

    suspend fun get(id: Long): Article? = withContext(Dispatchers.IO) {
        helper.writableDatabase.execSQL("UPDATE articles SET opened_at = ? WHERE id = ?", arrayOf(System.currentTimeMillis(), id))
        helper.readableDatabase.rawQuery(
            "SELECT id, title, progress_paragraph, progress_offset, temporary, folder_id, position FROM articles WHERE id = ?", arrayOf(id.toString())
        ).use { c ->
            if (c.moveToFirst()) Article(
                c.getLong(0), c.getString(1), "", c.getInt(2), c.getInt(3), c.getInt(4) == 1,
                folderId = if (c.isNull(5)) null else c.getLong(5), position = c.getInt(6),
            ) else null
        }
            ?.let { it.copy(content = readContent(helper, id)) }
    }

    suspend fun saveProgress(id: Long, paragraph: Int, offset: Int) = withContext(Dispatchers.IO) {
        helper.writableDatabase.execSQL(
            "UPDATE articles SET progress_paragraph = ?, progress_offset = ? WHERE id = ?", arrayOf(paragraph, offset, id)
        )
    }

    suspend fun isTemporary(id: Long): Boolean = withContext(Dispatchers.IO) {
        helper.readableDatabase.rawQuery("SELECT temporary FROM articles WHERE id = ?", arrayOf(id.toString()))
            .use { it.moveToFirst() && it.getInt(0) == 1 }
    }

    suspend fun temporaryIds(): List<Long> = withContext(Dispatchers.IO) {
        helper.readableDatabase.rawQuery("SELECT id FROM articles WHERE temporary = 1", null)
            .use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }
    }

    suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        helper.writableDatabase.delete("articles", "id = ?", arrayOf(id.toString())); bump()
    }
}

/** 正文分段读：单行超过 CursorWindow（约 2MB）会读失败，整本 epub 很容易超过 */
internal fun readContent(helper: UserDb, id: Long): String {
    val db = helper.readableDatabase
    val len = db.rawQuery("SELECT length(content) FROM articles WHERE id = ?", arrayOf(id.toString()))
        .use { if (it.moveToFirst()) it.getInt(0) else 0 }
    val chunk = 200_000
    val sb = StringBuilder(len)
    var start = 1
    while (start <= len) {
        db.rawQuery("SELECT substr(content, ?, ?) FROM articles WHERE id = ?", arrayOf(start.toString(), chunk.toString(), id.toString()))
            .use { if (it.moveToFirst()) sb.append(it.getString(0)) }
        start += chunk
    }
    return sb.toString()
}

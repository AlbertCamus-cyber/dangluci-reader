package com.dangluci.reader.data.local

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** 一条生词：锚定在遇到它的那篇文章、那一段、那个位置 */
data class VocabEntry(
    val id: Long = 0,
    /** 词头（原形） */
    val word: String,
    /** 原文里实际出现的样子，如 took */
    val surface: String,
    val phonetic: String,
    val brief: String,
    /** 当初遇到它的原句 */
    val context: String?,
    val articleId: Long?,
    /** null 表示文章已被删除 */
    val articleTitle: String? = null,
    /** -1 表示位置未知（v1 迁移来的），回跳时按原句定位 */
    val paragraph: Int = -1,
    val charStart: Int = -1,
    val charEnd: Int = -1,
    val addedAt: Long,
    /** 复现统计：在你所有文章里共出现几次、分布在几篇 */
    val seenTimes: Int = 0,
    val seenArticles: Int = 0,
    /** 这个词在几篇文章下被加进过生词本（含本条） */
    val addedInArticles: Int = 1,
    /** 来自临时阅读（原文不保存，不能回跳） */
    val fromTemp: Boolean = false,
)

/** 生词本按文章分组；组按最近加词时间倒序，组内也按加入时间倒序 */
data class VocabGroup(val articleId: Long?, val title: String, val entries: List<VocabEntry>)

/** 生词本。不做 SRS；以后要加复习，另起表关联 vocab_entries.id 即可。 */
class VocabRepository(private val helper: UserDb) {
    companion object {
        /** 临时阅读分组的键（文章 id 都是正数） */
        const val TEMP_GROUP = -2L
    }

    private val _groups = MutableStateFlow<List<VocabGroup>>(emptyList())
    val groups: StateFlow<List<VocabGroup>> = _groups.asStateFlow()

    private val select = """SELECT v.id, v.word, v.surface, v.phonetic, v.brief, v.context, v.article_id, a.title,
        v.paragraph, v.char_start, v.char_end, v.added_at,
        COALESCE(o.total, 0), COALESCE(o.arts, 0),
        (SELECT COUNT(*) FROM vocab_entries v2 WHERE v2.word = v.word),
        v.from_temp
        FROM vocab_entries v
        LEFT JOIN articles a ON a.id = v.article_id
        LEFT JOIN (SELECT headword, SUM(count) AS total, COUNT(*) AS arts FROM occurrences GROUP BY headword) o
          ON o.headword = v.word"""

    private fun Cursor.entry() = VocabEntry(
        id = getLong(0), word = getString(1), surface = getString(2), phonetic = getString(3), brief = getString(4),
        context = getString(5), articleId = if (isNull(6)) null else getLong(6), articleTitle = getString(7),
        paragraph = getInt(8), charStart = getInt(9), charEnd = getInt(10), addedAt = getLong(11),
        seenTimes = getInt(12), seenArticles = getInt(13), addedInArticles = getInt(14),
        fromTemp = getInt(15) == 1,
    )

    suspend fun refresh() = withContext(Dispatchers.IO) {
        val all = helper.readableDatabase.rawQuery("$select ORDER BY v.added_at DESC", null).use { c ->
            buildList { while (c.moveToNext()) add(c.entry()) }
        }
        // 分组键：现存文章用文章 id；临时阅读来的词归一组（TEMP_GROUP）；原文已删的归一组（null）
        val grouped = LinkedHashMap<Long?, MutableList<VocabEntry>>()
        for (e in all) {
            val key = when {
                e.articleTitle != null && !e.fromTemp -> e.articleId
                e.fromTemp -> TEMP_GROUP
                else -> null
            }
            grouped.getOrPut(key) { mutableListOf() } += e
        }
        _groups.value = grouped.map { (id, list) ->
            val title = when (id) {
                TEMP_GROUP -> "临时阅读"
                null -> "已删除的文章"
                else -> list.first().articleTitle!!
            }
            VocabGroup(id, title, list)
        }
    }

    suspend fun get(id: Long): VocabEntry? = withContext(Dispatchers.IO) {
        helper.readableDatabase.rawQuery("$select WHERE v.id = ?", arrayOf(id.toString())).use { if (it.moveToFirst()) it.entry() else null }
    }

    /** 临时阅读时看「临时阅读」组里有没有，其余按文章看 */
    suspend fun contains(word: String, articleId: Long, temp: Boolean): Boolean = withContext(Dispatchers.IO) {
        val (sql, args) = if (temp) "SELECT 1 FROM vocab_entries WHERE word = ? AND from_temp = 1" to arrayOf(word)
        else "SELECT 1 FROM vocab_entries WHERE word = ? AND article_id = ? AND from_temp = 0" to arrayOf(word, articleId.toString())
        helper.readableDatabase.rawQuery(sql, args).use { it.moveToFirst() }
    }

    /** 之前在临时阅读里加过这个词吗 */
    suspend fun addedInTemp(word: String): Boolean = withContext(Dispatchers.IO) {
        helper.readableDatabase.rawQuery("SELECT 1 FROM vocab_entries WHERE word = ? AND from_temp = 1", arrayOf(word))
            .use { it.moveToFirst() }
    }

    /** 同一篇文章里同一个词再加一次：更新原句、位置和时间 */
    suspend fun add(e: VocabEntry) = withContext(Dispatchers.IO) {
        // 「临时阅读」组里一个词只留一条：再加时换成最新的原句，排到最前
        if (e.fromTemp) helper.writableDatabase.delete("vocab_entries", "word = ? AND from_temp = 1", arrayOf(e.word))
        helper.writableDatabase.insertWithOnConflict("vocab_entries", null, ContentValues().apply {
            put("word", e.word); put("surface", e.surface); put("phonetic", e.phonetic); put("brief", e.brief)
            put("context", e.context); e.articleId?.let { put("article_id", it) }
            put("paragraph", e.paragraph); put("char_start", e.charStart); put("char_end", e.charEnd)
            put("added_at", e.addedAt)
            put("from_temp", if (e.fromTemp) 1 else 0)
        }, SQLiteDatabase.CONFLICT_REPLACE)
        refresh()
    }

    /** 旧版本可能在「临时阅读」组里留下同一个词的多条，只留最新那条 */
    suspend fun dedupeTemp() = withContext(Dispatchers.IO) {
        helper.writableDatabase.execSQL(
            """DELETE FROM vocab_entries WHERE from_temp = 1 AND id NOT IN
               (SELECT id FROM vocab_entries v WHERE from_temp = 1 AND added_at = (SELECT MAX(added_at) FROM vocab_entries WHERE from_temp = 1 AND word = v.word) GROUP BY word)"""
        )
        refresh()
    }

    suspend fun remove(id: Long) = withContext(Dispatchers.IO) {
        helper.writableDatabase.delete("vocab_entries", "id = ?", arrayOf(id.toString())); refresh()
    }
}

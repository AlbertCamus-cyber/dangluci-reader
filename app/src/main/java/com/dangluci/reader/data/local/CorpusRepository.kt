package com.dangluci.reader.data.local

import android.content.ContentValues
import com.dangluci.reader.data.dict.Dictionary
import com.dangluci.reader.domain.Lemmatizer
import com.dangluci.reader.domain.Tokenizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 某个词在某篇文章里的出现情况 */
data class Occurrence(
    val articleId: Long,
    val title: String,
    val count: Int,
    val paragraph: Int,
    val start: Int,
    val end: Int,
)

/**
 * 个人语料复现统计：跨文章记录每个词（按词头/原形）出现几次、在哪些文章里。
 * 导入时建索引；只存本地 SQLite。
 */
class CorpusRepository(private val helper: UserDb, private val dict: Dictionary) {
    private val lock = Mutex()

    private class Hit(val count: Int, val p: Int, val s: Int, val e: Int)

    suspend fun indexArticle(articleId: Long, content: String) = lock.withLock {
        withContext(Dispatchers.Default) {
            val paragraphs = Tokenizer.paragraphs(content)
            // 先按规范化词形聚合，再映射到词头合并
            val byForm = LinkedHashMap<String, Hit>()
            paragraphs.forEachIndexed { pi, text ->
                for (w in Tokenizer.words(text)) {
                    val n = Lemmatizer.normalize(w.text)
                    if (n.length < 2) continue
                    val h = byForm[n]
                    byForm[n] = if (h == null) Hit(1, pi, w.start, w.end) else Hit(h.count + 1, h.p, h.s, h.e)
                }
            }
            val heads = dict.headwords(byForm.keys)
            val byHead = LinkedHashMap<String, Hit>()
            for ((form, hit) in byForm) {
                val head = heads[form] ?: form
                val cur = byHead[head]
                byHead[head] = when {
                    cur == null -> hit
                    hit.p < cur.p || (hit.p == cur.p && hit.s < cur.s) -> Hit(cur.count + hit.count, hit.p, hit.s, hit.e)
                    else -> Hit(cur.count + hit.count, cur.p, cur.s, cur.e)
                }
            }
            withContext(Dispatchers.IO) {
                val db = helper.writableDatabase
                db.beginTransaction()
                try {
                    db.delete("occurrences", "article_id = ?", arrayOf(articleId.toString()))
                    val stmt = db.compileStatement(
                        "INSERT INTO occurrences(headword, article_id, count, first_paragraph, first_start, first_end) VALUES(?,?,?,?,?,?)"
                    )
                    for ((head, h) in byHead) {
                        stmt.clearBindings()
                        stmt.bindString(1, head); stmt.bindLong(2, articleId); stmt.bindLong(3, h.count.toLong())
                        stmt.bindLong(4, h.p.toLong()); stmt.bindLong(5, h.s.toLong()); stmt.bindLong(6, h.e.toLong())
                        stmt.executeInsert()
                    }
                    db.update("articles", ContentValues().apply { put("indexed", 1) }, "id = ?", arrayOf(articleId.toString()))
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
        }
    }

    /** 补建还没索引的文章（如从 v1 升级上来的旧文章） */
    suspend fun indexPending() {
        val pending = withContext(Dispatchers.IO) {
            helper.readableDatabase.rawQuery("SELECT id FROM articles WHERE indexed = 0", null).use { c ->
                buildList { while (c.moveToNext()) add(c.getLong(0)) }
            }
        }
        for (id in pending) indexArticle(id, withContext(Dispatchers.IO) { readContent(helper, id) })
    }

    suspend fun articleCount(headword: String): Int = withContext(Dispatchers.IO) {
        helper.readableDatabase.rawQuery(
            // 只算保存下来的文章；正在临时阅读的那篇不算
            """SELECT COUNT(*) FROM occurrences o JOIN articles a ON a.id = o.article_id
               WHERE o.headword = ? AND a.temporary = 0""", arrayOf(headword)
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    /** 出现过这个词的文章，最近打开的在前 */
    suspend fun articlesFor(headword: String): List<Occurrence> = withContext(Dispatchers.IO) {
        helper.readableDatabase.rawQuery(
            """SELECT o.article_id, a.title, o.count, o.first_paragraph, o.first_start, o.first_end
               FROM occurrences o JOIN articles a ON a.id = o.article_id
               WHERE o.headword = ? ORDER BY a.opened_at DESC""",
            arrayOf(headword)
        ).use { c ->
            buildList { while (c.moveToNext()) add(Occurrence(c.getLong(0), c.getString(1), c.getInt(2), c.getInt(3), c.getInt(4), c.getInt(5))) }
        }
    }

    suspend fun removeArticle(articleId: Long) = withContext(Dispatchers.IO) {
        helper.writableDatabase.delete("occurrences", "article_id = ?", arrayOf(articleId.toString()))
    }
}

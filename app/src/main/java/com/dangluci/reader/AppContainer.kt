package com.dangluci.reader

import android.content.Context
import android.net.Uri
import com.dangluci.reader.data.Backup
import com.dangluci.reader.data.importer.Epub
import com.dangluci.reader.data.Settings
import com.dangluci.reader.data.dict.Dictionary
import com.dangluci.reader.data.dict.EcdictDictionary
import com.dangluci.reader.data.local.ArticleRepository
import com.dangluci.reader.data.local.CorpusRepository
import com.dangluci.reader.data.local.UserDb
import com.dangluci.reader.data.local.VocabRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 手写的依赖容器。以后要换词典实现（如 FreeDict、多词典叠加）或加功能，只改这里。
 * 跨仓库的操作（导入要建索引、删文章要清索引）也放这里，页面只调这一层。
 */
class AppContainer(context: Context) {
    private val userDb = UserDb(context)
    val backup = Backup(context.applicationContext, userDb)
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings = Settings(context)
    val dictionary: Dictionary = EcdictDictionary(context)
    val articles = ArticleRepository(userDb)
    val vocab = VocabRepository(userDb)
    val corpus = CorpusRepository(userDb, dictionary)

    init {
        appScope.launch {
            // 上次临时阅读没正常退出（被系统杀掉等）留下的残留，先清掉
            runCatching { articles.temporaryIds().forEach { discard(it) } }
            runCatching { vocab.dedupeTemp() }
            runCatching { corpus.indexPending() }
        }
    }

    suspend fun importArticle(title: String, content: String, temporary: Boolean = false, folderId: Long? = null): Long {
        val id = articles.add(title, content, temporary, folderId)
        corpus.indexArticle(id, content)
        return id
    }

    /** epub：新建一个以书名命名的文件夹，每章一篇（按章节顺序），返回第一章 id */
    suspend fun importBook(book: Epub.Book, fallbackTitle: String): Long {
        val folder = articles.createFolder(book.title.ifBlank { fallbackTitle }.ifBlank { "未命名的书" })
        var first = -1L
        book.chapters.forEachIndexed { i, ch ->
            val id = articles.add(ch.title, ch.text, folderId = folder, position = i + 1)
            corpus.indexArticle(id, ch.text)
            if (first < 0) first = id
        }
        return first
    }

    /** 离开阅读页时调用：临时文章连同它的复现统计一起删掉；生词保留在「临时阅读」组 */
    fun discardIfTemporary(id: Long) {
        appScope.launch { if (articles.isTemporary(id)) discard(id) }
    }

    private suspend fun discard(id: Long) {
        corpus.removeArticle(id)
        articles.delete(id)
        vocab.refresh()
    }

    /** withArticles = true：里面的文章连同复现统计一起删（生词保留在「已删除的文章」组）；否则文章退回首页 */
    suspend fun deleteFolder(folderId: Long, withArticles: Boolean) {
        if (withArticles) articles.articleIdsIn(folderId).forEach { deleteArticle(it) }
        articles.deleteFolder(folderId)
    }

    suspend fun deleteArticle(id: Long) {
        corpus.removeArticle(id)
        articles.delete(id)
        vocab.refresh()
    }
}

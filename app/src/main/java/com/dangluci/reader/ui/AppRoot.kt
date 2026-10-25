package com.dangluci.reader.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.dangluci.reader.AppContainer
import com.dangluci.reader.ui.importer.ImportScreen
import com.dangluci.reader.ui.library.LibraryScreen
import com.dangluci.reader.ui.reader.ReaderScreen
import com.dangluci.reader.ui.reader.ReaderTarget
import com.dangluci.reader.ui.vocab.VocabScreen

/**
 * 极简导航：一个栈。页面少，先不引 navigation-compose；以后页面多了再换。
 * 路由用字符串以便 rememberSaveable 保存。
 *   reader/{id}                    接着上次进度读
 *   reader/{id}/at/{p}/{s}/{e}     跳到某段某词
 *   reader/{id}/vocab/{entryId}    跳回某条生词当初的位置
 */
object Routes {
    const val LIBRARY = "library"
    const val IMPORT = "import"
    const val TEMP = "import/temp"
    fun folder(id: Long) = "folder/$id"
    fun importInto(folderId: Long) = "import/f/$folderId"
    fun parseId(route: String, prefix: String) = route.removePrefix(prefix).toLongOrNull()?.takeIf { route.startsWith(prefix) }
    const val VOCAB = "vocab"
    fun reader(id: Long) = "reader/$id"
    fun readerAt(id: Long, p: Int, s: Int, e: Int) = "reader/$id/at/$p/$s/$e"
    fun readerVocab(id: Long, entryId: Long) = "reader/$id/vocab/$entryId"

    fun parseReader(route: String): Pair<Long, ReaderTarget>? {
        val parts = route.split('/')
        if (parts.firstOrNull() != "reader") return null
        val id = parts.getOrNull(1)?.toLongOrNull() ?: return null
        val target = when (parts.getOrNull(2)) {
            "at" -> ReaderTarget.At(parts[3].toInt(), parts[4].toInt(), parts[5].toInt())
            "vocab" -> ReaderTarget.Vocab(parts[3].toLong())
            else -> ReaderTarget.Resume
        }
        return id to target
    }
}

@Composable
fun AppRoot(container: AppContainer, incomingUri: Uri?, onIncomingConsumed: () -> Unit) {
    var stack by rememberSaveable { mutableStateOf(listOf(Routes.LIBRARY)) }
    val current = stack.last()
    fun push(r: String) { stack = stack + r }
    fun pop() {
        if (stack.size <= 1) return
        val leaving = stack.last()
        stack = stack.dropLast(1)
        // 离开临时文章的阅读页（且栈里没有它的其他阅读页）就删掉它
        Routes.parseReader(leaving)?.first?.let { id ->
            if (stack.none { Routes.parseReader(it)?.first == id }) container.discardIfTemporary(id)
        }
    }
    fun replaceTop(r: String) { stack = stack.dropLast(1) + r }

    var pendingUri by remember { mutableStateOf<Uri?>(null) }
    LaunchedEffect(incomingUri) {
        if (incomingUri != null) {
            pendingUri = incomingUri
            onIncomingConsumed()
            stack = listOf(Routes.LIBRARY, Routes.IMPORT)
        }
    }

    BackHandler(enabled = stack.size > 1) { pop() }

    val reader = Routes.parseReader(current)
    val folderId = Routes.parseId(current, "folder/")
    val importFolder = Routes.parseId(current, "import/f/")
    when {
        current == Routes.LIBRARY || folderId != null -> LibraryScreen(
            repo = container.articles,
            folderId = folderId,
            onOpen = { push(Routes.reader(it)) },
            onOpenFolder = { push(Routes.folder(it)) },
            onImport = { push(if (folderId != null) Routes.importInto(folderId) else Routes.IMPORT) },
            onTempRead = { push(Routes.TEMP) },
            onVocab = { push(Routes.VOCAB) },
            onDelete = { container.deleteArticle(it) },
            onDeleteFolder = { id, withArticles -> container.deleteFolder(id, withArticles) },
            onExportBackup = { container.backup.export(it) },
            onRestoreBackup = { container.backup.restore(it) },
            onBack = ::pop,
        )
        current == Routes.IMPORT || importFolder != null -> ImportScreen(
            initialUri = if (importFolder == null) pendingUri else null,
            folders = container.articles,
            initialFolderId = importFolder,
            onImport = { t, c, f -> container.importArticle(t, c, folderId = f) },
            onImportBook = { b, name -> container.importBook(b, name) },
            onBack = { pendingUri = null; pop() },
            onSaved = { pendingUri = null; replaceTop(Routes.reader(it)) },
        )
        current == Routes.TEMP -> ImportScreen(
            initialUri = null,
            temporary = true,
            onImport = { _, c, _ -> container.importArticle("临时阅读", c, temporary = true) },
            onBack = ::pop,
            onSaved = { replaceTop(Routes.reader(it)) },
        )
        current == Routes.VOCAB -> VocabScreen(
            repo = container.vocab,
            corpus = container.corpus,
            onBack = ::pop,
            onJumpToEntry = { articleId, entryId -> push(Routes.readerVocab(articleId, entryId)) },
            onOpenOccurrence = { o -> push(Routes.readerAt(o.articleId, o.paragraph, o.start, o.end)) },
        )
        reader != null -> ReaderScreen(
            articleId = reader.first,
            target = reader.second,
            routeKey = current,
            container = container,
            onBack = ::pop,
            onVocab = { push(Routes.VOCAB) },
            onOpenArticle = { replaceTop(Routes.reader(it)) },
        )
    }
}

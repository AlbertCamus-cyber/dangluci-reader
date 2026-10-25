package com.dangluci.reader.ui.importer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dangluci.reader.data.importer.Epub
import com.dangluci.reader.data.local.ArticleRepository
import com.dangluci.reader.data.local.Folder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 导入：粘贴文本 / 选 txt 或 epub。不做 URL 抓取、不做 RSS。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(
    initialUri: Uri?,
    /** true：临时阅读，不保存，退出阅读页即删除 */
    temporary: Boolean = false,
    /** 用来列出可选文件夹；临时阅读不用 */
    folders: ArticleRepository? = null,
    initialFolderId: Long? = null,
    onImport: suspend (title: String, content: String, folderId: Long?) -> Long,
    /** epub 整本：建文件夹、按章节导入，返回第一章 id。临时阅读不用 */
    onImportBook: (suspend (book: Epub.Book, fallbackTitle: String) -> Long)? = null,
    onBack: () -> Unit,
    onSaved: (Long) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    /** 从文件读进来的正文：不塞进输入框（整本书放进输入框会很卡），只显示摘要 */
    var fileText by remember { mutableStateOf<String?>(null) }
    var fileName by remember { mutableStateOf("") }
    /** 读进来的是 epub */
    var book by remember { mutableStateOf<Epub.Book?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var folderList by remember { mutableStateOf<List<Folder>>(emptyList()) }
    var target by remember { mutableStateOf(initialFolderId) }
    var folderMenu by remember { mutableStateOf(false) }
    LaunchedEffect(folders) { folderList = folders?.folders().orEmpty() }

    fun load(uri: Uri) = scope.launch {
        loading = true
        runCatching { readFile(context, uri) }
            .onSuccess { r ->
                fileName = r.name; error = null
                book = r.book
                fileText = r.book?.text ?: r.text
                if (title.isBlank()) title = r.book?.title?.ifBlank { r.name } ?: r.name
            }
            .onFailure { error = "读取失败：${it.message}" }
        loading = false
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(::load) }
    LaunchedEffect(initialUri) { initialUri?.let(::load) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (temporary) "临时阅读" else "导入文章") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(16.dp).imePadding()) {
            if (temporary) {
                Text(
                    "不保存到文章列表，退出阅读就没了。读的时候照样能查词、加生词本，生词会留在「临时阅读」分组里。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                Spacer(Modifier.height(8.dp))
            }
            OutlinedButton(
                onClick = { picker.launch(arrayOf("text/plain", "application/epub+zip", "application/octet-stream")) },
                enabled = !loading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (loading) "正在读取…" else "选择 txt / epub 文件")
            }
            val asBook = book != null && !temporary && onImportBook != null
            if (!temporary && folderList.isNotEmpty() && !asBook) {
                Spacer(Modifier.height(8.dp))
                Box {
                    OutlinedButton(onClick = { folderMenu = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("放到：" + (folderList.firstOrNull { it.id == target }?.let { "📁 ${it.name}" } ?: "首页（未归类）"))
                    }
                    DropdownMenu(expanded = folderMenu, onDismissRequest = { folderMenu = false }) {
                        DropdownMenuItem(text = { Text("首页（未归类）") }, onClick = { target = null; folderMenu = false })
                        folderList.forEach { f ->
                            DropdownMenuItem(text = { Text("📁 ${f.name}") }, onClick = { target = f.id; folderMenu = false })
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            // 临时阅读用完即删，不用标题
            if (!temporary) {
                OutlinedTextField(
                    title, { title = it },
                    label = { Text(if (asBook) "书名（文件夹名）" else "标题（可留空）") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
            }
            val ft = fileText
            if (ft != null) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("已读取：$fileName", style = MaterialTheme.typography.titleSmall)
                        val b = book
                        Text(
                            if (b != null) "约 ${b.wordCount} 个词 · ${b.chapters.size} 章"
                            else "约 ${wordCount(ft)} 个词 · ${ft.lineSequence().count { it.isNotBlank() }} 段",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                        )
                        if (asBook) Text(
                            "会新建一个文件夹，每章一篇，按章节顺序排列，读完一章可以直接进下一章。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary,
                        )
                        Text(ft.take(200).trim() + "…", style = MaterialTheme.typography.bodySmall, maxLines = 4)
                        TextButton(onClick = { fileText = null; fileName = ""; book = null }) { Text("不要这个文件，改为粘贴") }
                    }
                }
                Spacer(Modifier.weight(1f))
            } else {
                OutlinedTextField(
                    content, { content = it },
                    label = { Text("粘贴英文文章") },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    saving = true
                    scope.launch {
                        runCatching {
                            val b = book
                            if (asBook && b != null) onImportBook!!(b.copy(title = title.trim()), fileName)
                            else onImport(title.trim(), (fileText ?: content).trim(), target)
                        }
                            .onSuccess(onSaved)
                            .onFailure { error = "保存失败：${it.message}"; saving = false }
                    }
                },
                enabled = (fileText ?: content).isNotBlank() && !saving && !loading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(when {
                    saving -> "正在准备…"
                    temporary -> "开始临时阅读"
                    else -> "保存并开始阅读"
                })
            }
        }
    }
}

private fun wordCount(s: String) = Regex("[A-Za-z]+").findAll(s).count()

private class Loaded(val name: String, val text: String, val book: Epub.Book? = null)

/** txt 直接读；epub（看扩展名或 zip 文件头）抽正文 */
private suspend fun readFile(context: Context, uri: Uri): Loaded = withContext(Dispatchers.IO) {
    val display = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    } ?: uri.lastPathSegment.orEmpty()
    val name = display.substringAfterLast('/').substringBeforeLast('.')
    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("无法打开文件")
    val isZip = bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
    if (display.endsWith(".epub", true) || isZip) {
        require(bytes.size <= 100 * 1024 * 1024) { "文件超过 100MB" }
        val book = Epub.read(bytes)
        require(book.chapters.sumOf { it.text.length } <= 8_000_000) { "这本书太大了" }
        Loaded(name, "", book)
    } else {
        require(bytes.size <= 5 * 1024 * 1024) { "文件超过 5MB" }
        Loaded(name, String(bytes, Charsets.UTF_8).removePrefix("\uFEFF"))
    }
}

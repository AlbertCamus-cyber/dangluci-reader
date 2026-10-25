package com.dangluci.reader.ui.library

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dangluci.reader.data.local.ArticleRepository
import com.dangluci.reader.data.local.ArticleSummary
import com.dangluci.reader.data.local.Folder
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * 文章列表。folderId = null 是首页：上面是文件夹，下面是未归类文章；
 * 进到某个文件夹后只列它里面的文章，右下角导入会直接放进这个文件夹。
 * 文件夹只有一层。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    repo: ArticleRepository,
    folderId: Long?,
    onOpen: (Long) -> Unit,
    onOpenFolder: (Long) -> Unit,
    onImport: () -> Unit,
    onTempRead: () -> Unit,
    onVocab: () -> Unit,
    onDelete: suspend (Long) -> Unit,
    onDeleteFolder: suspend (folderId: Long, withArticles: Boolean) -> Unit,
    onBack: () -> Unit,
    onExportBackup: suspend (Uri) -> Unit = {},
    onRestoreBackup: suspend (Uri) -> Unit = {},
) {
    val version by repo.version.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var query by remember { mutableStateOf<String?>(null) }   // null = 不在搜索
    var results by remember { mutableStateOf<List<ArticleSummary>>(emptyList()) }
    LaunchedEffect(query, version) {
        val q = query?.trim().orEmpty()
        if (q.isEmpty()) { results = emptyList(); return@LaunchedEffect }
        delay(250); results = repo.search(q)
    }
    BackHandler(enabled = query != null) { query = null }
    var renaming by remember { mutableStateOf<ArticleSummary?>(null) }
    var topMenu by remember { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf<Uri?>(null) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        uri?.let { scope.launch {
            runCatching { onExportBackup(it) }
                .onSuccess { snackbar.showSnackbar("备份已导出") }
                .onFailure { e -> snackbar.showSnackbar("导出失败：${e.message}") }
        } }
    }
    val restorer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> confirmRestore = uri }
    var articles by remember { mutableStateOf<List<ArticleSummary>>(emptyList()) }
    var folders by remember { mutableStateOf<List<Folder>>(emptyList()) }
    var folderName by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(version, folderId) {
        articles = repo.list(folderId)
        folders = repo.folders()
        folderName = folderId?.let { repo.folderName(it) }
    }

    var confirmDelete by remember { mutableStateOf<ArticleSummary?>(null) }
    var moving by remember { mutableStateOf<ArticleSummary?>(null) }
    // 新建（id = null）或重命名（id = 文件夹）
    var naming by remember { mutableStateOf<Pair<Long?, String>?>(null) }
    var confirmDeleteFolder by remember { mutableStateOf<Folder?>(null) }

    val inFolder = folderId != null
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            val q = query
            if (q != null) TopAppBar(
                title = {
                    TextField(
                        q, { query = it }, singleLine = true, placeholder = { Text("搜标题或正文") },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surface,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
                navigationIcon = { IconButton(onClick = { query = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "退出搜索") } },
                actions = { if (q.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, "清空") } },
            ) else TopAppBar(
                title = { Text(if (inFolder) folderName ?: "" else "挡路词阅读器", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    if (inFolder) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                },
                actions = {
                    IconButton(onClick = { query = "" }) { Icon(Icons.Default.Search, "搜索") }
                    TextButton(onClick = onVocab) { Icon(Icons.Default.Star, null); Spacer(Modifier.width(4.dp)); Text("生词本") }
                    if (!inFolder) Box {
                        IconButton(onClick = { topMenu = true }) { Icon(Icons.Default.MoreVert, "更多") }
                        DropdownMenu(expanded = topMenu, onDismissRequest = { topMenu = false }) {
                            DropdownMenuItem(text = { Text("新建文件夹") }, onClick = { topMenu = false; naming = null to "" })
                            DropdownMenuItem(text = { Text("导出备份") }, onClick = {
                                topMenu = false; exporter.launch("挡路词备份-${LocalDate.now()}.db")
                            })
                            DropdownMenuItem(text = { Text("从备份恢复") }, onClick = { topMenu = false; restorer.launch(arrayOf("*/*")) })
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End) {
                if (!inFolder) {
                    // 临时阅读：读完就走，不进文章列表
                    SmallFloatingActionButton(
                        onClick = onTempRead,
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ) { Text("临时阅读", Modifier.padding(horizontal = 12.dp)) }
                    Spacer(Modifier.height(12.dp))
                }
                ExtendedFloatingActionButton(
                    onClick = onImport,
                    icon = { Icon(Icons.Default.Add, null) },
                    text = { Text(if (inFolder) "导入到此文件夹" else "导入文章") },
                )
            }
        },
    ) { pad ->
        val showFolders = !inFolder && folders.isNotEmpty()
        val searching = query
        if (searching != null) {
            // LazyColumn 的内容在测量阶段才执行，这里只能用捕获的值，不能再读 query（退出搜索时已变成 null）
            LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(12.dp, 8.dp, 12.dp, 150.dp)) {
                if (searching.isNotBlank() && results.isEmpty()) item {
                    Text("没找到", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.outline)
                }
                items(results, key = { it.id }) { a ->
                    ArticleCard(
                        a = a, canMove = folders.isNotEmpty(),
                        onClick = { onOpen(a.id) }, onMove = { moving = a },
                        onRename = { renaming = a }, onDelete = { confirmDelete = a },
                        showFolder = true,
                    )
                }
            }
        } else if (articles.isEmpty() && !showFolders) {
            Box(Modifier.fillMaxSize().padding(pad).padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    if (inFolder) "这个文件夹还是空的。点右下角导入，或在首页把文章移进来。"
                    else "还没有文章。点右下角，粘贴英文或导入 txt / epub。",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(12.dp, 8.dp, 12.dp, 150.dp)) {
                if (showFolders) {
                    items(folders, key = { "f-${it.id}" }) { f ->
                        FolderCard(
                            folder = f,
                            onClick = { onOpenFolder(f.id) },
                            onRename = { naming = f.id to f.name },
                            onDelete = { confirmDeleteFolder = f },
                        )
                    }
                    if (articles.isNotEmpty()) item(key = "sep") {
                        Text("未归类", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 2.dp))
                    }
                }
                items(articles, key = { it.id }) { a ->
                    ArticleCard(
                        a = a,
                        canMove = inFolder || folders.isNotEmpty(),
                        onClick = { onOpen(a.id) },
                        onMove = { moving = a },
                        onRename = { renaming = a },
                        onDelete = { confirmDelete = a },
                    )
                }
            }
        }
    }

    renaming?.let { a ->
        var name by remember(a.id) { mutableStateOf(a.title) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("修改标题") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = { scope.launch { repo.rename(a.id, name) }; renaming = null }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("取消") } },
        )
    }

    confirmRestore?.let { uri ->
        AlertDialog(
            onDismissRequest = { confirmRestore = null },
            title = { Text("从备份恢复？") },
            text = { Text("现在 App 里的文章、文件夹和生词本会被备份里的内容整个替换，替换后 App 会自动重启。建议先导出一份当前的备份。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmRestore = null
                        scope.launch {
                            runCatching { onRestoreBackup(uri) }.onFailure { e -> snackbar.showSnackbar("恢复失败：${e.message}") }
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("替换并重启") }
            },
            dismissButton = { TextButton(onClick = { confirmRestore = null }) { Text("取消") } },
        )
    }

    confirmDelete?.let { a ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("删除这篇文章？") },
            text = { Text("生词本里的词和原句会保留，但不能再跳回原文。") },
            confirmButton = { TextButton(onClick = { scope.launch { onDelete(a.id) }; confirmDelete = null }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("取消") } },
        )
    }

    naming?.let { (id, initial) ->
        var name by remember(id) { mutableStateOf(initial) }
        AlertDialog(
            onDismissRequest = { naming = null },
            title = { Text(if (id == null) "新建文件夹" else "重命名文件夹") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, placeholder = { Text("文件夹名") }) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    scope.launch { if (id == null) repo.createFolder(name) else repo.renameFolder(id, name) }
                    naming = null
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { naming = null }) { Text("取消") } },
        )
    }

    confirmDeleteFolder?.let { f ->
        AlertDialog(
            onDismissRequest = { confirmDeleteFolder = null },
            title = { Text("删除文件夹「${f.name}」？") },
            text = {
                if (f.articleCount == 0) Text("这是个空文件夹。")
                else Column {
                    Text("里面有 ${f.articleCount} 篇文章，要怎么处理？")
                    Spacer(Modifier.height(4.dp))
                    Text("连文章一起删：生词本里的词和原句还在，但不能再跳回原文。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
            },
            confirmButton = {
                if (f.articleCount == 0) {
                    TextButton(onClick = { scope.launch { onDeleteFolder(f.id, false) }; confirmDeleteFolder = null }) { Text("删除") }
                } else Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = { scope.launch { onDeleteFolder(f.id, false) }; confirmDeleteFolder = null }) { Text("只删文件夹，文章移回首页") }
                    TextButton(
                        onClick = { scope.launch { onDeleteFolder(f.id, true) }; confirmDeleteFolder = null },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) { Text("连同 ${f.articleCount} 篇文章一起删除") }
                    TextButton(onClick = { confirmDeleteFolder = null }) { Text("取消") }
                }
            },
            dismissButton = if (f.articleCount == 0) ({ TextButton(onClick = { confirmDeleteFolder = null }) { Text("取消") } }) else null,
        )
    }

    moving?.let { a ->
        AlertDialog(
            onDismissRequest = { moving = null },
            title = { Text("移动到") },
            text = {
                Column {
                    if (inFolder || query != null) {
                        TextButton(onClick = { scope.launch { repo.moveToFolder(a.id, null) }; moving = null }) { Text("首页（未归类）") }
                    }
                    folders.filter { it.id != folderId || query != null }.forEach { f ->
                        TextButton(onClick = { scope.launch { repo.moveToFolder(a.id, f.id) }; moving = null }) { Text("📁 ${f.name}") }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { moving = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun FolderCard(folder: Folder, onClick: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    ElevatedCard(Modifier.fillMaxWidth().padding(vertical = 6.dp).clickable(onClick = onClick)) {
        Row(Modifier.padding(start = 16.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("📁", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.width(12.dp))
            Text(folder.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${folder.articleCount} 篇", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "更多") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("重命名") }, onClick = { menu = false; onRename() })
                    DropdownMenuItem(text = { Text("删除文件夹") }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ArticleCard(
    a: ArticleSummary, canMove: Boolean, onClick: () -> Unit, onMove: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit,
    showFolder: Boolean = false,
) {
    var menu by remember { mutableStateOf(false) }
    // 点击阅读；长按弹出「移动到文件夹 / 删除」，和右侧 ⋮ 是同一个菜单
    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp).combinedClickable(onClick = onClick, onLongClick = { menu = true })) {
        Row(Modifier.padding(start = 16.dp, top = 16.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(a.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (showFolder) Text(
                    a.folderName?.let { "📁 $it" } ?: "首页",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline,
                )
                Spacer(Modifier.height(4.dp))
                Text(a.preview.replace('\n', ' '), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (a.progressParagraph > 0) {
                    Text("读到第 ${a.progressParagraph + 1} 段", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "更多") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("修改标题") }, onClick = { menu = false; onRename() })
                    if (canMove) DropdownMenuItem(text = { Text("移动到文件夹") }, onClick = { menu = false; onMove() })
                    DropdownMenuItem(text = { Text("删除") }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

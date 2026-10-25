package com.dangluci.reader.ui.vocab

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dangluci.reader.data.local.CorpusRepository
import com.dangluci.reader.data.local.Occurrence
import com.dangluci.reader.data.local.VocabEntry
import com.dangluci.reader.data.local.VocabRepository
import kotlinx.coroutines.launch

/**
 * 语境锚定的生词本：按文章分组，每条保留原句。
 * 点一条 → 详情：回到原文那个位置继续读；看这个词出现过的文章列表（复现统计）。
 * 没有复习算法。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VocabScreen(
    repo: VocabRepository,
    corpus: CorpusRepository,
    onBack: () -> Unit,
    onJumpToEntry: (articleId: Long, entryId: Long) -> Unit,
    onOpenOccurrence: (Occurrence) -> Unit,
) {
    val allGroups by repo.groups.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf<String?>(null) }   // null = 不在搜索
    BackHandler(enabled = query != null) { query = null }
    // 搜词头、释义、原句
    val groups = remember(allGroups, query) {
        val q = query?.trim()?.lowercase().orEmpty()
        if (q.isEmpty()) allGroups
        else allGroups.mapNotNull { g ->
            g.entries.filter { e ->
                e.word.contains(q) || e.surface.lowercase().contains(q) || e.brief.contains(q) || e.context?.lowercase()?.contains(q) == true
            }.takeIf { it.isNotEmpty() }?.let { g.copy(entries = it) }
        }
    }
    val scope = rememberCoroutineScope()
    var opened by remember { mutableStateOf<VocabEntry?>(null) }
    // 折叠的分组（按文章 id；已删除文章组用 -1）
    var collapsed by rememberSaveable { mutableStateOf(setOf<Long>()) }
    fun keyOf(id: Long?) = id ?: -1L
    LaunchedEffect(Unit) { repo.refresh() }
    val total = groups.sumOf { it.entries.size }

    Scaffold(
        topBar = {
            val q = query
            if (q != null) TopAppBar(
                title = {
                    TextField(
                        q, { query = it }, singleLine = true, placeholder = { Text("搜单词、释义或原句") },
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
                title = { Text("生词本 · $total") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                actions = {
                    IconButton(onClick = { query = "" }) { Icon(Icons.Default.Search, "搜索") }
                    if (groups.size > 1) {
                        val allCollapsed = groups.all { keyOf(it.articleId) in collapsed }
                        TextButton(onClick = {
                            collapsed = if (allCollapsed) emptySet() else groups.map { keyOf(it.articleId) }.toSet()
                        }) { Text(if (allCollapsed) "全部展开" else "全部折叠") }
                    }
                },
            )
        },
    ) { pad ->
        if (groups.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                Text(if (query.isNullOrBlank()) "阅读时点词，再点“加入生词本”。" else "没找到")
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(bottom = 24.dp)) {
                groups.forEach { g ->
                    val k = keyOf(g.articleId)
                    val isCollapsed = k in collapsed
                    item(key = "h-$k") {
                        Row(
                            Modifier.fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable { collapsed = if (isCollapsed) collapsed - k else collapsed + k }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                if (isCollapsed) Icons.AutoMirrored.Filled.KeyboardArrowRight else Icons.Default.KeyboardArrowDown,
                                if (isCollapsed) "展开" else "折叠",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                g.title, Modifier.weight(1f),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            Text("${g.entries.size} 词", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                        }
                    }
                    if (isCollapsed) return@forEach
                    items(g.entries, key = { it.id }) { v ->
                        Row(
                            Modifier.fillMaxWidth().clickable { opened = v }.padding(start = 16.dp, top = 10.dp, bottom = 10.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.Bottom) {
                                    Text(v.word, style = MaterialTheme.typography.titleMedium)
                                    if (v.phonetic.isNotBlank()) {
                                        Spacer(Modifier.width(8.dp))
                                        Text("/${v.phonetic}/", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                                    }
                                }
                                Text(v.brief, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                seenLine(v)?.let {
                                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary, maxLines = 1)
                                }
                                v.context?.takeIf { it.isNotBlank() }?.let {
                                    Text(contextWithMark(it, v.surface), style = MaterialTheme.typography.bodySmall,
                                        fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.outline,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            IconButton(onClick = { scope.launch { repo.remove(v.id) } }) { Icon(Icons.Default.Delete, "删除") }
                        }
                        HorizontalDivider(Modifier.padding(start = 16.dp))
                    }
                }
            }
        }
    }

    opened?.let { entry ->
        EntrySheet(
            entry = entry,
            corpus = corpus,
            onDismiss = { opened = null },
            onJump = { entry.articleId?.let { onJumpToEntry(it, entry.id) }; opened = null },
            onOpenOccurrence = { onOpenOccurrence(it); opened = null },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EntrySheet(
    entry: VocabEntry,
    corpus: CorpusRepository,
    onDismiss: () -> Unit,
    onJump: () -> Unit,
    onOpenOccurrence: (Occurrence) -> Unit,
) {
    var occurrences by remember(entry.id) { mutableStateOf<List<Occurrence>?>(null) }
    LaunchedEffect(entry.id) { occurrences = corpus.articlesFor(entry.word) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(entry.word, style = MaterialTheme.typography.headlineSmall)
                if (entry.phonetic.isNotBlank()) {
                    Spacer(Modifier.width(10.dp))
                    Text("/${entry.phonetic}/", color = MaterialTheme.colorScheme.outline)
                }
            }
            Text(entry.brief, style = MaterialTheme.typography.bodyLarge)
            entry.context?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(8.dp))
                Text(contextWithMark(it, entry.surface), style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic)
            }
            Spacer(Modifier.height(12.dp))
            Button(onClick = onJump, enabled = entry.articleTitle != null, modifier = Modifier.fillMaxWidth()) {
                Text(when {
                    entry.articleTitle != null -> "回到原文继续读"
                    entry.fromTemp -> "临时阅读的原文不保存，无法跳回"
                    else -> "原文章已删除"
                })
            }

            Spacer(Modifier.height(16.dp))
            val list = occurrences
            Text(
                if (list == null) "出现过的文章" else "出现过的文章 · ${list.size} 篇",
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(4.dp))
            when {
                list == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                list.isEmpty() -> Text("现有文章里没有找到它", color = MaterialTheme.colorScheme.outline)
                else -> LazyColumn(Modifier.heightIn(max = 280.dp)) {
                    items(list, key = { it.articleId }) { o ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onOpenOccurrence(o) }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(o.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${o.count} 次", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
            }
        }
    }
}

/** 复现一行小字：在几篇文章里见过几次；同一个词在别的文章下也加过时补一句 */
private fun seenLine(v: VocabEntry): String? {
    if (v.seenTimes <= 0) return null
    val base = "已见 ${v.seenTimes} 次 · ${v.seenArticles} 篇文章"
    val others = v.addedInArticles - 1
    return if (others > 0) "$base · 另有 $others 篇也加过" else base
}

/** 原句里把当初点的那个词加粗 */
private fun contextWithMark(context: String, surface: String): AnnotatedString {
    val i = Regex("\\b${Regex.escape(surface)}\\b", RegexOption.IGNORE_CASE).find(context)?.range
    return if (i == null) AnnotatedString(context) else buildAnnotatedString {
        append(context.substring(0, i.first))
        withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Normal)) { append(context.substring(i.first, i.last + 1)) }
        append(context.substring(i.last + 1))
    }
}

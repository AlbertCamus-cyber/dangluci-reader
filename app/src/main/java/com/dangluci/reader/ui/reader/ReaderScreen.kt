package com.dangluci.reader.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dangluci.reader.AppContainer
import com.dangluci.reader.data.Settings
import kotlinx.coroutines.FlowPreview
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.debounce

@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
fun ReaderScreen(
    articleId: Long,
    target: ReaderTarget,
    routeKey: String,
    container: AppContainer,
    onBack: () -> Unit,
    onVocab: () -> Unit,
    /** epub 下一章 */
    onOpenArticle: (Long) -> Unit = {},
) {
    val vm: ReaderViewModel = viewModel(
        key = routeKey,
        factory = viewModelFactory { initializer { ReaderViewModel(articleId, target, container) } },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val article = state.article
    val listState = rememberLazyListState()
    val fontSize by container.settings.fontSize.collectAsStateWithLifecycle()
    val theme by container.settings.theme.collectAsStateWithLifecycle()
    var showSettings by remember { mutableStateOf(false) }
    val found = state.lookup as? LookupState.Found
    val activeMark = (if (state.phraseMode) found?.phrase?.mark else null) ?: state.selection?.mark ?: state.anchor

    // 首次进入：回跳锚点优先，否则恢复上次进度
    var restored by remember { mutableStateOf(false) }
    LaunchedEffect(state.initialScroll) {
        val target = state.initialScroll
        if (target != null && !restored) {
            if (state.paragraphs.isNotEmpty()) listState.scrollToItem(target.first, target.second)
            restored = true
        }
    }
    // 滚动停下后保存进度
    LaunchedEffect(restored) {
        if (!restored) return@LaunchedEffect
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .debounce(600)
            .collect { (i, o) -> vm.saveProgress(i, o) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (article?.temporary == true) "临时阅读" else article?.title ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                actions = {
                    TextButton(onClick = { showSettings = true }) { Text("Aa") }
                    IconButton(onClick = onVocab) { Icon(Icons.Default.Star, "生词本") }
                },
            )
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 240.dp),
            ) {
                itemsIndexed(state.paragraphs) { index, text ->
                    val mark = activeMark?.takeIf { it.paragraph == index && it.end > it.start }
                    TappableParagraph(
                        text = text,
                        highlight = mark?.let { it.start until it.end },
                        fontSize = fontSize,
                        onTapOffset = { vm.onTap(index, it) },
                    )
                }
                state.nextChapter?.let { next ->
                    item {
                        Spacer(Modifier.height(24.dp))
                        OutlinedButton(onClick = { onOpenArticle(next.id) }, modifier = Modifier.fillMaxWidth()) {
                            Text("下一章：${next.title}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = state.selection != null,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically { it },
                exit = slideOutVertically { it },
            ) {
                WordCard(
                    word = state.selection?.surface.orEmpty(),
                    lookup = state.lookup,
                    phraseMode = state.phraseMode,
                    onTogglePhrase = vm::togglePhrase,
                    onAdd = vm::addToVocab,
                    onClose = vm::dismiss,
                )
            }
        }
    }

    if (showSettings) {
        ReadingSettingsDialog(
            fontSize = fontSize, theme = theme,
            onFontSize = container.settings::setFontSize,
            onTheme = container.settings::setTheme,
            onDismiss = { showSettings = false },
        )
    }
}

/** 字号 + 主题 */
@Composable
private fun ReadingSettingsDialog(
    fontSize: Float, theme: Settings.Theme,
    onFontSize: (Float) -> Unit, onTheme: (Settings.Theme) -> Unit, onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("阅读设置") },
        text = {
            Column {
                Text("字号 ${fontSize.toInt()}", style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = fontSize, onValueChange = { onFontSize(it.roundToInt().toFloat()) },
                    valueRange = Settings.MIN_FONT..Settings.MAX_FONT,
                    steps = (Settings.MAX_FONT - Settings.MIN_FONT).toInt() - 1,
                )
                Text("The quick brown fox jumps over the lazy dog.", fontSize = fontSize.sp, lineHeight = (fontSize * 1.58f).sp)
                Spacer(Modifier.height(16.dp))
                Text("主题", style = MaterialTheme.typography.labelLarge)
                Row {
                    Settings.Theme.entries.forEach { t ->
                        FilterChip(selected = t == theme, onClick = { onTheme(t) }, label = { Text(t.label) })
                        Spacer(Modifier.width(8.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("好") } },
    )
}

/** 一段正文：点哪就把字符偏移交出去，由 Tokenizer 判断点中的词 */
@Composable
private fun TappableParagraph(text: String, highlight: IntRange?, fontSize: Float, onTapOffset: (Int) -> Unit) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val hl = MaterialTheme.colorScheme.secondary.copy(alpha = 0.25f)
    val annotated = remember(text, highlight, hl) {
        buildAnnotatedString {
            if (highlight == null) append(text) else {
                append(text.substring(0, highlight.first))
                withStyle(SpanStyle(background = hl)) { append(text.substring(highlight.first, highlight.last + 1)) }
                append(text.substring(highlight.last + 1))
            }
        }
    }
    Text(
        text = annotated,
        style = MaterialTheme.typography.bodyLarge.copy(fontSize = fontSize.sp, lineHeight = (fontSize * 1.58f).sp),
        onTextLayout = { layout = it },
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .pointerInput(text) {
                detectTapGestures { pos -> layout?.let { onTapOffset(it.getOffsetForPosition(pos)) } }
            },
    )
}

/** 底部查词卡片：音标 + 最常用 1–2 个释义；词组、更多释义各占一行小字，点了才展开 */
@Composable
private fun WordCard(
    word: String, lookup: LookupState?, phraseMode: Boolean,
    onTogglePhrase: () -> Unit, onAdd: () -> Unit, onClose: () -> Unit,
) {
    val found = lookup as? LookupState.Found
    val ph = found?.phrase?.takeIf { phraseMode }
    val shown = ph?.entry ?: found?.entry
    var expanded by remember(shown?.headword) { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp),
        shape = MaterialTheme.shapes.large,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState())) {
                when {
                    lookup == null || lookup == LookupState.Loading -> Text(word, style = MaterialTheme.typography.titleLarge)
                    lookup == LookupState.NotFound -> {
                        Text(word, style = MaterialTheme.typography.titleLarge)
                        Text("本地词典没有收录这个词", color = MaterialTheme.colorScheme.outline)
                    }
                    found != null && shown != null -> {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(shown.headword, style = MaterialTheme.typography.titleLarge)
                            if (shown.phonetic.isNotBlank()) {
                                Spacer(Modifier.width(10.dp))
                                Text("/${shown.phonetic}/", color = MaterialTheme.colorScheme.outline)
                            }
                        }
                        shown.formNote?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary) }
                        Spacer(Modifier.height(6.dp))
                        if (expanded && shown.more.isNotEmpty()) shown.more.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                        else shown.senses.forEach { Text(it, style = MaterialTheme.typography.bodyLarge) }
                        if (shown.more.isNotEmpty()) {
                            Text(
                                if (expanded) "收起" else "更多释义",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable { expanded = !expanded }.padding(vertical = 4.dp),
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        // 复现信息只对单词显示，一行小字
                        if (ph == null) Small(seenLine(found))
                        found.phrase?.let { p ->
                            val line = if (phraseMode) "单词 ${found.entry.headword}：${found.entry.senses.firstOrNull().orEmpty()}"
                            else "词组 ${p.entry.headword}：${p.entry.senses.firstOrNull().orEmpty()}"
                            Text(
                                "$line  ›",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.secondary,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth().clickable(onClick = onTogglePhrase).padding(vertical = 4.dp),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onClose) { Text("关闭") }
                if (found != null) {
                    Spacer(Modifier.width(8.dp))
                    val inVocab = ph?.inVocab ?: found.inVocab
                    if (inVocab) OutlinedButton(onClick = {}, enabled = false) { Text("已在生词本") }
                    else Button(onClick = onAdd) { Text("加入生词本") }
                }
            }
        }
    }
}

@Composable
private fun Small(text: String) = Text(
    text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, maxLines = 1,
)

/** 卡片上那一行小字 */
private fun seenLine(f: LookupState.Found): String = when {
    f.temp && f.seenInArticles >= 1 -> "你保存的文章里有 ${f.seenInArticles} 篇出现过这个词"
    !f.temp && f.seenInArticles >= 2 -> "这个词你已经在 ${f.seenInArticles} 篇文章里见过"
    f.addedInTemp -> "之前在临时阅读里加过这个词"
    f.temp -> "你保存的文章里还没出现过这个词"
    else -> "第一次在你的文章里见到这个词"
}

package com.dangluci.reader.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dangluci.reader.AppContainer
import com.dangluci.reader.data.dict.DictEntry
import com.dangluci.reader.data.local.Article
import com.dangluci.reader.data.local.ChapterRef
import com.dangluci.reader.data.local.VocabEntry
import com.dangluci.reader.domain.Lemmatizer
import com.dangluci.reader.domain.Tokenizer
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 打开文章时从哪里开始读 */
sealed interface ReaderTarget {
    /** 接着上次的进度 */
    data object Resume : ReaderTarget
    /** 跳到某段某个词（复现列表里点文章） */
    data class At(val paragraph: Int, val start: Int, val end: Int) : ReaderTarget
    /** 跳到某条生词当初的位置 */
    data class Vocab(val entryId: Long) : ReaderTarget
}

/** 段落内的一个字符区间：点中的词，或回跳时高亮的锚点 */
data class Mark(val paragraph: Int, val start: Int, val end: Int)

data class Selection(val mark: Mark, val surface: String, val context: String)

/** 点中的词落在一个词组里 */
data class PhraseHit(val entry: DictEntry, val mark: Mark, val surface: String, val inVocab: Boolean)

sealed interface LookupState {
    data object Loading : LookupState
    data class Found(
        val entry: DictEntry,
        val inVocab: Boolean,
        /** 保存的文章里有几篇出现过（不含临时阅读） */
        val seenInArticles: Int,
        /** 当前是不是临时阅读 */
        val temp: Boolean = false,
        /** 以前在临时阅读里加过这个词 */
        val addedInTemp: Boolean = false,
        /** 点中的词所在的词组（give up），没有为 null */
        val phrase: PhraseHit? = null,
    ) : LookupState
    data object NotFound : LookupState
}

data class ReaderUiState(
    val article: Article? = null,
    val paragraphs: List<String> = emptyList(),
    /** 首次进入要滚到的位置；null 表示还在加载 */
    val initialScroll: Pair<Int, Int>? = null,
    /** 回跳过来的锚点高亮，点任何词后消失 */
    val anchor: Mark? = null,
    val selection: Selection? = null,
    val lookup: LookupState? = null,
    /** 卡片当前显示词组而不是单词 */
    val phraseMode: Boolean = false,
    /** epub 章节：读到底可以进下一章 */
    val nextChapter: ChapterRef? = null,
)

class ReaderViewModel(
    private val articleId: Long,
    private val target: ReaderTarget,
    private val c: AppContainer,
) : ViewModel() {
    private val _state = MutableStateFlow(ReaderUiState())
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()
    private var lookupJob: Job? = null

    init {
        viewModelScope.launch {
            val a = c.articles.get(articleId)
            val paragraphs = a?.content?.let(Tokenizer::paragraphs).orEmpty()
            val anchor = when (target) {
                ReaderTarget.Resume -> null
                is ReaderTarget.At -> Mark(target.paragraph, target.start, target.end).takeIf { valid(it, paragraphs) }
                is ReaderTarget.Vocab -> c.vocab.get(target.entryId)?.let { locate(it, paragraphs) }
            }
            val scroll = when {
                anchor != null -> anchor.paragraph to 0
                a != null && paragraphs.isNotEmpty() -> a.progressParagraph.coerceIn(0, paragraphs.lastIndex) to a.progressOffset
                else -> 0 to 0
            }
            _state.value = ReaderUiState(
                article = a, paragraphs = paragraphs, initialScroll = scroll, anchor = anchor,
                nextChapter = a?.let { c.articles.nextChapter(it) },
            )
        }
    }

    private fun valid(m: Mark, ps: List<String>) =
        m.paragraph in ps.indices && m.start >= 0 && m.end <= ps[m.paragraph].length && m.start < m.end

    /** 生词的位置：有记录且仍然对得上就用；否则按原句、再按词形在文章里找 */
    private fun locate(e: VocabEntry, ps: List<String>): Mark? {
        if (e.paragraph >= 0) {
            val m = Mark(e.paragraph, e.charStart, e.charEnd)
            if (valid(m, ps) && ps[m.paragraph].substring(m.start, m.end).equals(e.surface, ignoreCase = true)) return m
        }
        val forms = setOf(e.surface.lowercase(), e.word)
        val pIndex = e.context?.takeIf { it.isNotBlank() }?.let { ctx -> ps.indexOfFirst { it.contains(ctx) } } ?: -1
        val searchIn = if (pIndex >= 0) listOf(pIndex) else ps.indices.toList()
        for (pi in searchIn) {
            Tokenizer.words(ps[pi]).firstOrNull { Lemmatizer.normalize(it.text) in forms }?.let { return Mark(pi, it.start, it.end) }
        }
        return if (pIndex >= 0) Mark(pIndex, 0, 0) else null
    }

    fun onTap(paragraph: Int, offset: Int) {
        val text = _state.value.paragraphs.getOrNull(paragraph) ?: return
        val span = Tokenizer.wordAt(text, offset)
        if (span == null) { dismiss(); return }
        val sel = Selection(Mark(paragraph, span.start, span.end), span.text, Tokenizer.sentenceAround(text, span.start))
        _state.value = _state.value.copy(selection = sel, lookup = LookupState.Loading, anchor = null, phraseMode = false)
        lookupJob?.cancel()
        val temp = _state.value.article?.temporary == true
        lookupJob = viewModelScope.launch {
            val entry = c.dictionary.lookup(span.text)
            _state.value = _state.value.copy(
                lookup = if (entry == null) LookupState.NotFound
                else LookupState.Found(
                    entry = entry,
                    inVocab = c.vocab.contains(entry.headword, articleId, temp),
                    seenInArticles = c.corpus.articleCount(entry.headword),
                    temp = temp,
                    addedInTemp = c.vocab.addedInTemp(entry.headword),
                    phrase = findPhrase(paragraph, text, span, temp),
                )
            )
        }
    }

    /** 包含点中词的 2–4 词窗口里，最长的那个词典里有的词组；不跨标点 */
    private suspend fun findPhrase(paragraph: Int, text: String, span: com.dangluci.reader.domain.WordSpan, temp: Boolean): PhraseHit? {
        val ws = Tokenizer.words(text)
        val idx = ws.indexOfFirst { it.start == span.start }
        if (idx < 0) return null
        for (len in 4 downTo 2) {
            for (s in (idx - len + 1)..idx) {
                if (s < 0 || s + len > ws.size) continue
                val win = ws.subList(s, s + len)
                if ((0 until len - 1).any { text.substring(win[it].end, win[it + 1].start).isNotBlank() }) continue
                val e = c.dictionary.phrase(win.map { Lemmatizer.normalize(it.text) }) ?: continue
                val mark = Mark(paragraph, win.first().start, win.last().end)
                return PhraseHit(e, mark, text.substring(mark.start, mark.end), c.vocab.contains(e.headword, articleId, temp))
            }
        }
        return null
    }

    /** 卡片在单词和词组之间切换 */
    fun togglePhrase() {
        val f = _state.value.lookup as? LookupState.Found ?: return
        if (f.phrase == null) return
        _state.value = _state.value.copy(phraseMode = !_state.value.phraseMode)
    }

    fun dismiss() {
        lookupJob?.cancel()
        _state.value = _state.value.copy(selection = null, lookup = null, anchor = null, phraseMode = false)
    }

    fun addToVocab() {
        val s = _state.value
        val found = s.lookup as? LookupState.Found ?: return
        val sel = s.selection ?: return
        val ph = found.phrase?.takeIf { s.phraseMode }
        val entry = ph?.entry ?: found.entry
        val mark = ph?.mark ?: sel.mark
        viewModelScope.launch {
            c.vocab.add(
                VocabEntry(
                    word = entry.headword,
                    surface = ph?.surface ?: sel.surface,
                    phonetic = entry.phonetic,
                    brief = entry.senses.joinToString("；"),
                    context = sel.context,
                    articleId = articleId,
                    paragraph = mark.paragraph,
                    charStart = mark.start,
                    charEnd = mark.end,
                    addedAt = System.currentTimeMillis(),
                    fromTemp = s.article?.temporary == true,
                )
            )
            _state.value = _state.value.copy(
                lookup = if (ph != null) found.copy(phrase = ph.copy(inVocab = true))
                else found.copy(inVocab = true, addedInTemp = found.addedInTemp || found.temp)
            )
        }
    }

    fun saveProgress(paragraph: Int, offset: Int) {
        viewModelScope.launch { c.articles.saveProgress(articleId, paragraph, offset) }
    }
}

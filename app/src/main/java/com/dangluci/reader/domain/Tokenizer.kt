package com.dangluci.reader.domain

/** 文本中一个可点的英文单词及其位置 */
data class WordSpan(val start: Int, val end: Int, val text: String)

object Tokenizer {
    private val WORD = Regex("[A-Za-z]+(?:['’\\-][A-Za-z]+)*")

    fun words(text: String): List<WordSpan> =
        WORD.findAll(text).map { WordSpan(it.range.first, it.range.last + 1, it.value) }.toList()

    fun wordAt(text: String, offset: Int): WordSpan? =
        WORD.findAll(text).firstOrNull { offset >= it.range.first && offset <= it.range.last }
            ?.let { WordSpan(it.range.first, it.range.last + 1, it.value) }

    /** 取包含该位置的句子，存进生词本当语境 */
    fun sentenceAround(text: String, offset: Int): String {
        val enders = ".!?。！？\n"
        var s = offset; while (s > 0 && text[s - 1] !in enders) s--
        var e = offset; while (e < text.length && text[e] !in enders) e++
        if (e < text.length) e++
        return text.substring(s, e).trim().take(300)
    }

    /** 文章切成段落，阅读页按段渲染、按段记进度 */
    fun paragraphs(text: String): List<String> =
        text.replace("\r\n", "\n").split(Regex("\n\\s*\n|\n")).map { it.trim() }.filter { it.isNotEmpty() }
}

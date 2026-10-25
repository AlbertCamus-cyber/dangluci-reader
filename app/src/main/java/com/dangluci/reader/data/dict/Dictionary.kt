package com.dangluci.reader.data.dict

/** 一次查词的结果：只带“最常用 1–2 个释义”，不展开全部。 */
data class DictEntry(
    /** 词典里的词头（可能是还原后的原形）。生词本、复现统计都以它为键。 */
    val headword: String,
    val phonetic: String,
    /** 每行一个词性，如 "vt. 放弃，抛弃" */
    val senses: List<String>,
    /** 点到的是变形时给出提示，如 "ran → run的过去式" */
    val formNote: String? = null,
    /** 「更多释义」展开后的完整释义（每行一个词性）；没有更多时为空 */
    val more: List<String> = emptyList(),
)

/** 词典抽象。离线实现在 [EcdictDictionary]；后面可以加别的实现再组合。 */
interface Dictionary {
    suspend fun lookup(rawWord: String): DictEntry?

    /** 词组：传入规范化后的几个词（如 gave, up），会试各词的原形（give up）。查不到返回 null */
    suspend fun phrase(words: List<String>): DictEntry?

    /**
     * 批量把原文里的词映射到词头（与 [lookup] 的 headword 规则一致），用于建复现索引。
     * key 是规范化后的词，查不到的词映射到它自己。
     */
    suspend fun headwords(normalizedWords: Collection<String>): Map<String, String>
}

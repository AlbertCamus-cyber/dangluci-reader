package com.dangluci.reader.domain

/** 很粗的英文词形还原：只在词典查不到时兜底用。 */
object Lemmatizer {
    fun normalize(raw: String): String =
        raw.trim().replace('’', '\'').replace('‘', '\'')
            .trim('\'', '-')
            .removeSuffix("'s")
            .lowercase()

    fun candidates(w: String): List<String> {
        val out = LinkedHashSet<String>()
        fun add(s: String) { if (s.length >= 2 && s != w) out += s }
        if (w.endsWith("ies")) add(w.dropLast(3) + "y")
        if (w.endsWith("es")) add(w.dropLast(2))
        if (w.endsWith("s")) add(w.dropLast(1))
        if (w.endsWith("ied")) add(w.dropLast(3) + "y")
        if (w.endsWith("ed")) { add(w.dropLast(2)); add(w.dropLast(1)); undouble(w.dropLast(2))?.let(::add) }
        if (w.endsWith("ing")) { add(w.dropLast(3)); add(w.dropLast(3) + "e"); undouble(w.dropLast(3))?.let(::add) }
        if (w.endsWith("er")) { add(w.dropLast(2)); add(w.dropLast(1)) }
        if (w.endsWith("est")) { add(w.dropLast(3)); add(w.dropLast(2)) }
        if (w.endsWith("ly")) add(w.dropLast(2))
        if (w.contains('-')) w.split('-').lastOrNull()?.let(::add)
        return out.toList()
    }

    private fun undouble(stem: String): String? =
        if (stem.length >= 3 && stem.last() == stem[stem.length - 2]) stem.dropLast(1) else null
}

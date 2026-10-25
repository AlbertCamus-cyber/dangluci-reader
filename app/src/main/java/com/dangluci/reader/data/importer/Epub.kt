package com.dangluci.reader.data.importer

import android.text.Html
import java.net.URLDecoder
import java.util.zip.ZipInputStream

/**
 * 极简 EPUB 读取：只取正文文字，按书脊（spine）顺序分章。
 * 太短的部分（封面、版权页等）并到下一章。
 * 不解析图片、样式、目录；DRM 加密的书读不了。
 */
object Epub {
    data class Chapter(val title: String, val text: String)
    data class Book(val title: String, val chapters: List<Chapter>) {
        /** 整本拼成一篇（临时阅读用） */
        val text: String get() = chapters.joinToString("\n\n") { it.text }
        val wordCount: Int get() = chapters.sumOf { words(it.text) }
    }

    private const val MIN_CHAPTER_WORDS = 150
    private fun words(s: String) = Regex("[A-Za-z]+").findAll(s).count()

    private val textExt = Regex("""\.(xml|opf|x?html?|ncx)$""", RegexOption.IGNORE_CASE)
    private val attr = Regex("""([\w:-]+)\s*=\s*(?:"([^"]*)"|'([^']*)')""")

    fun read(bytes: ByteArray): Book {
        val files = HashMap<String, ByteArray>()
        ZipInputStream(bytes.inputStream()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (!e.isDirectory && textExt.containsMatchIn(e.name)) files[e.name] = z.readBytes()
            }
        }
        require(files.isNotEmpty()) { "不是有效的 EPUB 文件" }
        if (files.keys.any { it.equals("META-INF/encryption.xml", true) } &&
            String(files.entries.first { it.key.equals("META-INF/encryption.xml", true) }.value).contains("EncryptedData")
        ) error("这本书有 DRM 加密，读不了")

        val container = files["META-INF/container.xml"]?.let { String(it) }
        val opfPath = container?.let { Regex("""full-path\s*=\s*"([^"]+)"""").find(it)?.groupValues?.get(1) }
            ?: files.keys.firstOrNull { it.endsWith(".opf", true) }
            ?: error("找不到 EPUB 目录文件")
        val opf = String(files[opfPath] ?: error("找不到 $opfPath"))
        val base = opfPath.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }

        val title = Regex("""<dc:title[^>]*>(.*?)</dc:title>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            .find(opf)?.groupValues?.get(1)?.let { plain(it).trim() }.orEmpty()

        val manifest = HashMap<String, String>()
        Regex("""<(?:\w+:)?item\b[^>]*>""", RegexOption.IGNORE_CASE).findAll(opf).forEach { m ->
            val a = attrs(m.value)
            val id = a["id"]; val href = a["href"]
            if (id != null && href != null) manifest[id] = resolve(base, href)
        }
        val spine = Regex("""<(?:\w+:)?itemref\b[^>]*>""", RegexOption.IGNORE_CASE).findAll(opf)
            .mapNotNull { attrs(it.value)["idref"]?.let(manifest::get) }.toList()
        val order = spine.ifEmpty { files.keys.filter { Regex("""\.x?html?$""", RegexOption.IGNORE_CASE).containsMatchIn(it) }.sorted() }

        val raw = order.mapNotNull { files[it] }.map { String(it, Charsets.UTF_8) }
            .map { html -> Chapter(heading(html).orEmpty(), chapterText(html)) }
            .filter { it.text.isNotBlank() }
        require(raw.isNotEmpty()) { "没在这本书里读到文字" }

        // 短的部分攒着，并进下一个够长的章节；最后剩下的并进最后一章
        val chapters = ArrayList<Chapter>()
        var pending = ""
        for (ch in raw) {
            val text = if (pending.isEmpty()) ch.text else pending + "\n\n" + ch.text
            if (words(ch.text) < MIN_CHAPTER_WORDS) { pending = text; continue }
            pending = ""
            chapters += Chapter(ch.title.ifBlank { firstLine(ch.text) }, text)
        }
        if (pending.isNotEmpty()) {
            if (chapters.isEmpty()) chapters += Chapter(firstLine(pending), pending)
            else chapters[chapters.lastIndex] = chapters.last().let { it.copy(text = it.text + "\n\n" + pending) }
        }
        return Book(title, chapters.mapIndexed { i, c -> c.copy(title = c.title.ifBlank { "第 ${i + 1} 部分" }.take(60)) })
    }

    private fun attrs(tag: String) = attr.findAll(tag).associate { m ->
        m.groupValues[1].substringAfter(':').lowercase() to (m.groupValues[2].ifEmpty { m.groupValues[3] })
    }

    private fun resolve(base: String, href: String): String {
        val raw = runCatching { URLDecoder.decode(href.substringBefore('#').replace("+", "%2B"), "UTF-8") }.getOrDefault(href)
        val parts = ArrayList<String>()
        for (p in (base + raw).split('/')) when (p) {
            "", "." -> {}
            ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
            else -> parts += p
        }
        return parts.joinToString("/")
    }

    private val dropBlocks = Regex("""<(head|script|style|svg)\b.*?</\1\s*>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

    private fun plain(html: String): String =
        @Suppress("DEPRECATION")
        Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString()

    private fun firstLine(text: String) = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.take(40).orEmpty()

    /** 章节标题：第一个 h1–h3 */
    private fun heading(xhtml: String): String? =
        Regex("""<h([1-3])\b[^>]*>(.*?)</h\1\s*>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            .find(xhtml.replace(dropBlocks, ""))?.groupValues?.get(2)
            ?.let { plain(it).replace(Regex("\\s+"), " ").trim() }?.takeIf { it.isNotEmpty() }

    /** 一章 XHTML → 纯文本，段落之间空一行 */
    private fun chapterText(xhtml: String): String {
        val body = Regex("""<body\b[^>]*>(.*)</body>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            .find(xhtml)?.groupValues?.get(1) ?: xhtml
        val cleaned = body.replace(dropBlocks, "").replace(Regex("""<img\b[^>]*>""", RegexOption.IGNORE_CASE), "")
        return plain(cleaned)
            .replace('\uFFFC', ' ').replace('\u00A0', ' ')
            .lines().map { it.trim() }
            .joinToString("\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }
}

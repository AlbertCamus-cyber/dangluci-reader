package com.dangluci.reader.data.dict

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.dangluci.reader.domain.Lemmatizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 基于 ECDICT（MIT 许可）精简词库的本地查词。全部离线。
 * 词库随 APK 放在 assets/ecdict_brief.db，首次查词时拷到 app 私有目录再只读打开。
 */
class EcdictDictionary(private val context: Context) : Dictionary {
    private val mutex = Mutex()
    private var db: SQLiteDatabase? = null

    private suspend fun database(): SQLiteDatabase = mutex.withLock {
        db ?: withContext(Dispatchers.IO) {
            val file = File(context.noBackupFilesDir, DB_FILE)
            // 清掉旧版本词库
            context.noBackupFilesDir.listFiles { f -> f.name.startsWith("ecdict_brief") && f.name != DB_FILE }?.forEach { it.delete() }
            if (!file.exists()) {
                val tmp = File(file.path + ".tmp")
                context.assets.open(ASSET).use { input -> tmp.outputStream().use { input.copyTo(it) } }
                tmp.renameTo(file)
            }
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
        }.also { db = it }
    }

    override suspend fun lookup(rawWord: String): DictEntry? = withContext(Dispatchers.IO) {
        val word = Lemmatizer.normalize(rawWord)
        if (word.isEmpty()) return@withContext null
        resolve(database(), word)?.let { (row, note) -> row.toEntry(note) }
    }

    override suspend fun phrase(words: List<String>): DictEntry? = withContext(Dispatchers.IO) {
        if (words.size < 2) return@withContext null
        val db = database()
        // 每个词：本身 + 原形（gave → give），组合起来逐个试
        val forms = words.map { w ->
            val set = linkedSetOf(w)
            query(db, w)?.lemma?.takeIf { it.isNotEmpty() }?.let(set::add)
            Lemmatizer.candidates(w).firstOrNull { query(db, it) != null }?.let(set::add)
            set.toList()
        }
        var combos = listOf("")
        for (f in forms) combos = combos.flatMap { prefix -> f.map { if (prefix.isEmpty()) it else "$prefix $it" } }
        combos.firstNotNullOfOrNull { query(db, it) }?.toEntry(null)
    }

    override suspend fun headwords(normalizedWords: Collection<String>): Map<String, String> =
        withContext(Dispatchers.IO) {
            val db = database()
            normalizedWords.associateWith { w -> resolve(db, w)?.first?.word ?: w }
        }

    /** 词 → (词典行, 变形提示)。lookup 和 headwords 共用，保证两边词头一致。 */
    private fun resolve(db: SQLiteDatabase, word: String): Pair<Row, String?>? {
        val direct = query(db, word)
        if (direct != null) {
            // 变形词（ran / took）在 ECDICT 里常只写“xx的过去式”，此时用原形的释义
            if (direct.lemma.isNotEmpty() && direct.isOnlyFormHint()) {
                query(db, direct.lemma)?.let { return it to "$word → ${direct.brief.lines().first()}" }
            }
            return direct to null
        }
        for (candidate in Lemmatizer.candidates(word)) {
            query(db, candidate)?.let { return it to "$word → $candidate" }
        }
        return null
    }

    private data class Row(val word: String, val phonetic: String, val brief: String, val lemma: String, val full: String) {
        fun isOnlyFormHint() = brief.lines().all { it.contains("的") && !it.contains(". ") }
        fun toEntry(formNote: String?) = DictEntry(
            headword = word,
            phonetic = phonetic,
            senses = brief.lines().filter { it.isNotBlank() }.take(2),
            formNote = formNote,
            more = full.lines().filter { it.isNotBlank() },
        )
    }

    private fun query(db: SQLiteDatabase, word: String): Row? =
        db.rawQuery("SELECT word, phonetic, brief, lemma, full FROM dict WHERE word = ?", arrayOf(word)).use { c ->
            if (c.moveToFirst()) Row(c.getString(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getString(3) ?: "", c.getString(4) ?: "") else null
        }

    companion object {
        private const val ASSET = "ecdict_brief.db"
        /** 词库更新时改文件名版本号，强制重新拷贝 */
        private const val DB_FILE = "ecdict_brief_v2.db"
    }
}

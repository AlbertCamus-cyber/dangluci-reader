package com.dangluci.reader.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 用户数据（文章、阅读进度、生词本、复现统计），与只读词库分开存放。
 * 加表/加字段：VERSION + 1，在 onUpgrade 里按版本迁移。
 *
 * v1: articles, vocab(word 主键)
 * v2: articles.indexed；occurrences（复现统计）；vocab → vocab_entries（按文章锚定原句和位置）
 * v4: folders（用户文件夹，一层）；articles.folder_id（NULL = 首页未归类）
 * v3: articles.temporary（临时阅读，退出即删）；vocab_entries.from_temp（来自临时阅读的生词）
 * v5: articles.position（epub 章节序号，1 起；0 = 普通文章）
 */
class UserDb(context: Context) : SQLiteOpenHelper(context, "user.db", null, VERSION) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE articles(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL,
                content TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                opened_at INTEGER NOT NULL,
                progress_paragraph INTEGER NOT NULL DEFAULT 0,
                progress_offset INTEGER NOT NULL DEFAULT 0,
                indexed INTEGER NOT NULL DEFAULT 0,
                temporary INTEGER NOT NULL DEFAULT 0
            )"""
        )
        createOccurrences(db)
        createVocabEntries(db)
        db.execSQL("ALTER TABLE vocab_entries ADD COLUMN from_temp INTEGER NOT NULL DEFAULT 0")
        createFolders(db)
        addPosition(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE articles ADD COLUMN indexed INTEGER NOT NULL DEFAULT 0")
            createOccurrences(db)
            createVocabEntries(db)
            // 旧生词搬过来；位置未知记 -1，回跳时按原句在文章里定位
            db.execSQL(
                """INSERT OR IGNORE INTO vocab_entries(word, surface, phonetic, brief, context, article_id, added_at)
                   SELECT word, word, phonetic, brief, context, article_id, added_at FROM vocab"""
            )
            db.execSQL("DROP TABLE vocab")
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE articles ADD COLUMN temporary INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE vocab_entries ADD COLUMN from_temp INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 4) createFolders(db)
        if (oldVersion < 5) addPosition(db)
    }

    private fun addPosition(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE articles ADD COLUMN position INTEGER NOT NULL DEFAULT 0")
    }

    private fun createFolders(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE folders(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, created_at INTEGER NOT NULL)")
        db.execSQL("ALTER TABLE articles ADD COLUMN folder_id INTEGER")
        db.execSQL("CREATE INDEX idx_articles_folder ON articles(folder_id)")
    }

    private fun createOccurrences(db: SQLiteDatabase) {
        // 每个词头在每篇文章出现几次，以及第一次出现的位置（段落 + 段内字符区间）
        db.execSQL(
            """CREATE TABLE occurrences(
                headword TEXT NOT NULL,
                article_id INTEGER NOT NULL,
                count INTEGER NOT NULL,
                first_paragraph INTEGER NOT NULL,
                first_start INTEGER NOT NULL,
                first_end INTEGER NOT NULL,
                PRIMARY KEY(headword, article_id)
            ) WITHOUT ROWID"""
        )
        db.execSQL("CREATE INDEX idx_occ_article ON occurrences(article_id)")
    }

    private fun createVocabEntries(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE vocab_entries(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                word TEXT NOT NULL,
                surface TEXT NOT NULL,
                phonetic TEXT NOT NULL,
                brief TEXT NOT NULL,
                context TEXT,
                article_id INTEGER,
                paragraph INTEGER NOT NULL DEFAULT -1,
                char_start INTEGER NOT NULL DEFAULT -1,
                char_end INTEGER NOT NULL DEFAULT -1,
                added_at INTEGER NOT NULL,
                UNIQUE(word, article_id)
            )"""
        )
        db.execSQL("CREATE INDEX idx_vocab_entries_added ON vocab_entries(added_at DESC)")
    }

    companion object { const val VERSION = 5 }
}

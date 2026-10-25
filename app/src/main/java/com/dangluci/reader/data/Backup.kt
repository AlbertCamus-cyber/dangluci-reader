package com.dangluci.reader.data

import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import com.dangluci.reader.MainActivity
import com.dangluci.reader.data.local.UserDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 备份：把用户数据库（文章、文件夹、生词本、复现统计、阅读进度）整个导出成一个文件，
 * 恢复时整个替换。全程本地文件，不联网。词库不在备份里（随 App 自带）。
 */
class Backup(private val context: Context, private val userDb: UserDb) {
    private val dbFile get() = context.getDatabasePath("user.db")

    suspend fun export(uri: Uri) = withContext(Dispatchers.IO) {
        // 先把 WAL 里的改动写回主文件，再整文件拷贝
        userDb.writableDatabase.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
        context.contentResolver.openOutputStream(uri, "w")?.use { out -> dbFile.inputStream().use { it.copyTo(out) } }
            ?: error("无法写入")
    }

    /** 校验通过后替换数据库并重启 App；校验失败抛异常，原数据不动 */
    suspend fun restore(uri: Uri) = withContext(Dispatchers.IO) {
        val tmp = File(context.cacheDir, "restore.db")
        context.contentResolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            ?: error("无法打开文件")
        try {
            val head = tmp.inputStream().use { s -> ByteArray(16).also { s.read(it) } }
            require(String(head, Charsets.US_ASCII).startsWith("SQLite format 3")) { "不是挡路词阅读器的备份文件" }
            SQLiteDatabase.openDatabase(tmp.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                val tables = db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table'", null)
                    .use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }
                require("articles" in tables && ("vocab_entries" in tables || "vocab" in tables)) { "不是挡路词阅读器的备份文件" }
                require(db.version <= UserDb.VERSION) { "这个备份来自更新版本的 App，请先升级" }
            }
        } catch (e: Exception) {
            tmp.delete(); throw e
        }
        userDb.close()
        listOf("-wal", "-shm", "-journal").forEach { File(dbFile.path + it).delete() }
        tmp.copyTo(dbFile, overwrite = true)
        tmp.delete()
        restart()
    }

    private fun restart() {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)
        Runtime.getRuntime().exit(0)
    }
}

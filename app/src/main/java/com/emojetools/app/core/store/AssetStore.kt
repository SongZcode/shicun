package com.emojetools.app.core.store

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import com.emojetools.app.core.Asset
import com.emojetools.app.core.AssetKind
import com.emojetools.app.core.download.DownloadedFile
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

data class StoredAsset(val key: String, val postId: String, val assetId: String, val kind: AssetKind,
    val file: File, val sha1: String, val bytes: Long, val previewFile: File? = null, val uri: Uri? = null)
data class PublishedAsset(val key: String, val uri: String, val sha1: String, val bytes: Long, val pending: Boolean)

class AssetStore(private val context: Context, databaseName: String = "assets.db", storageRoot: File? = null) : SQLiteOpenHelper(context.applicationContext, databaseName, null, 2) {
    val root = storageRoot ?: File(context.cacheDir, "materials")
    private val previewRoot = storageRoot ?: File(context.getExternalFilesDir(null) ?: context.filesDir, "materials")
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE assets (asset_key TEXT PRIMARY KEY, post_id TEXT NOT NULL, asset_id TEXT NOT NULL, kind TEXT NOT NULL, path TEXT NOT NULL, sha1 TEXT NOT NULL, bytes INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE tasks (task_id INTEGER PRIMARY KEY, document TEXT NOT NULL)")
        createPublications(db)
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Unreleased development schemas are disposable; published system media is untouched.
        listOf("assets", "tasks", "published_assets").forEach { db.execSQL("DROP TABLE IF EXISTS $it") }
        onCreate(db)
    }
    private fun createPublications(db: SQLiteDatabase) = db.execSQL("CREATE TABLE published_assets (asset_key TEXT PRIMARY KEY, uri TEXT NOT NULL, sha1 TEXT NOT NULL, bytes INTEGER NOT NULL, pending INTEGER NOT NULL)")
    fun publication(key: String): PublishedAsset? = readableDatabase.rawQuery(
        "SELECT asset_key, uri, sha1, bytes, pending FROM published_assets WHERE asset_key = ?", arrayOf(key)).use { cursor ->
        if (!cursor.moveToFirst()) null else PublishedAsset(cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getLong(3), cursor.getInt(4) != 0)
    }
    fun recordPublication(entry: PublishedAsset) {
        check(writableDatabase.insertWithOnConflict("published_assets", null, ContentValues().apply {
            put("asset_key", entry.key); put("uri", entry.uri); put("sha1", entry.sha1); put("bytes", entry.bytes); put("pending", if (entry.pending) 1 else 0)
        }, SQLiteDatabase.CONFLICT_REPLACE) != -1L)
    }
    fun forgetPublication(key: String) { writableDatabase.delete("published_assets", "asset_key = ?", arrayOf(key)) }
    fun directory(postId: String, title: String): File {
        require(postId.matches(Regex("[0-9]+|bilibili:[0-9]+:[0-9]+")))
        val safeTitle = title.codePoints().limit(20).toArray().let { String(it, 0, it.size) }
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().ifEmpty { "post" }
        val platform = if (postId.startsWith("bilibili:")) "bilibili" else "douyin"
        val suffix = if (platform == "douyin") postId.take(8) else postId.replace(':', '_')
        return File(root, "$platform/${safeTitle}_$suffix")
    }
    fun key(postId: String, assetId: String) = "$postId:$assetId"
    fun collectionDirectory(key: String): File {
        require(key.matches(Regex("favorites-[a-f0-9]{64}")))
        return File(root, "douyin/favorites/${key.removePrefix("favorites-")}")
    }
    fun previewDirectory(postId: String): File {
        require(postId.matches(Regex("[0-9]+|bilibili:[0-9]+:[0-9]+")))
        val platform = if (postId.startsWith("bilibili:")) "bilibili" else "douyin"
        return File(previewRoot, ".previews/$platform/${postId.replace(':', '_')}")
    }
    fun previewFile(postId: String): File? = if (!postId.matches(Regex("[0-9]+|bilibili:[0-9]+:[0-9]+"))) null else previewDirectory(postId).listFiles()?.firstOrNull {
        it.isFile && it.length() > 0 && it.nameWithoutExtension == "cover" && it.extension in setOf("jpg", "png", "webp", "gif", "heic", "avif")
    }
    fun record(postId: String, asset: Asset, downloaded: DownloadedFile): StoredAsset {
        val entry = StoredAsset(key(postId, asset.id), postId, asset.id, asset.kind, downloaded.file, downloaded.sha1, downloaded.bytes, previewFile(postId))
        writableDatabase.insertWithOnConflict("assets", null, ContentValues().apply {
            put("asset_key", entry.key); put("post_id", postId); put("asset_id", asset.id); put("kind", asset.kind.name)
            put("path", entry.file.absolutePath); put("sha1", entry.sha1); put("bytes", entry.bytes)
        }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) }
        return entry
    }
    private fun attach(entry: StoredAsset): StoredAsset {
        val published = publication(entry.key)?.takeIf { !it.pending && it.sha1 == entry.sha1 && it.bytes == entry.bytes }
        return entry.copy(uri = published?.let { Uri.parse(it.uri) })
    }
    fun all(): List<StoredAsset> = readableDatabase.rawQuery("SELECT * FROM assets ORDER BY rowid DESC", null).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(attach(StoredAsset(cursor.getString(0), cursor.getString(1), cursor.getString(2),
                AssetKind.valueOf(cursor.getString(3)), File(cursor.getString(4)), cursor.getString(5), cursor.getLong(6), previewFile(cursor.getString(1)))))
        }
    }
    fun verified(postId: String, assetId: String): StoredAsset? {
        val entry = find(postId, assetId) ?: return null
        fun valid(open: () -> InputStream?): Boolean = try {
            open()?.use { input ->
                val digest = MessageDigest.getInstance("SHA-1")
                val buffer = ByteArray(64 * 1024)
                var bytes = 0L
                while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n); bytes += n }
                val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                actual == entry.sha1 && bytes == entry.bytes
            } == true
        } catch (_: IOException) { false } catch (_: SecurityException) { false }
        if (entry.uri != null && valid { context.contentResolver.openInputStream(entry.uri) }) return entry
        return if (entry.file.isFile && entry.file.length() == entry.bytes && valid { entry.file.inputStream() }) entry.copy(uri = null) else null
    }
    fun open(entry: StoredAsset): InputStream = entry.uri?.let {
        context.contentResolver.openInputStream(it) ?: throw IOException("Media is unavailable")
    } ?: entry.file.inputStream()
    fun available(entry: StoredAsset): Boolean = if (entry.uri == null) entry.file.isFile else try {
        context.contentResolver.openAssetFileDescriptor(entry.uri, "r")?.use { true } == true
    } catch (_: IOException) { false } catch (_: SecurityException) { false }
    fun discardLocal(entry: StoredAsset) {
        val published = publication(entry.key)
        check(published != null && !published.pending && published.sha1 == entry.sha1 && published.bytes == entry.bytes)
        val path = entry.file.canonicalFile.toPath()
        check(listOf(root, previewRoot).any { path.startsWith(it.canonicalFile.toPath()) })
        check(!entry.file.exists() || entry.file.delete()) { "Cannot remove staged media" }
    }
    fun cleanupTemporaryMedia(now: Long = System.currentTimeMillis()) {
        all().filter { it.uri != null && it.file.isFile }.forEach { entry ->
            if (verified(entry.postId, entry.assetId)?.uri != null) discardLocal(entry)
        }
        root.walkTopDown().filter { it.isFile && now - it.lastModified() > 24 * 60 * 60 * 1000L }.forEach { it.delete() }
    }
    fun find(postId: String, assetId: String): StoredAsset? = readableDatabase.rawQuery(
        "SELECT * FROM assets WHERE asset_key = ?", arrayOf(key(postId, assetId))).use { cursor ->
        if (!cursor.moveToFirst()) null else attach(StoredAsset(cursor.getString(0), cursor.getString(1), cursor.getString(2),
            AssetKind.valueOf(cursor.getString(3)), File(cursor.getString(4)), cursor.getString(5), cursor.getLong(6), previewFile(cursor.getString(1))))
    }
    fun saveTask(id: Long, document: JSONObject) {
        check(writableDatabase.insertWithOnConflict("tasks", null, ContentValues().apply {
            put("task_id", id); put("document", document.toString())
        }, SQLiteDatabase.CONFLICT_REPLACE) != -1L)
    }
    fun deleteTasks(ids: List<Long>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            ids.forEach { db.delete("tasks", "task_id = ?", arrayOf(it.toString())) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun taskDocuments(): List<Pair<Long, String>> = readableDatabase.rawQuery("SELECT task_id, document FROM tasks ORDER BY task_id DESC", null).use { cursor ->
        buildList { while (cursor.moveToNext()) add(cursor.getLong(0) to cursor.getString(1)) }
    }
    fun tasks(): List<JSONObject> = taskDocuments().map { JSONObject(it.second) }
}

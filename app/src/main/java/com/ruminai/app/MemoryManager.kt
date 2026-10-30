package com.ruminai.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Local, encrypted memory store.
 * Facts are encrypted with an Android Keystore AES-GCM key before entering SQLite.
 */
class MemoryManager(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    "rumin_memory.db",
    null,
    1
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE memory_items (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                type TEXT NOT NULL,
                encrypted_content TEXT NOT NULL,
                normalized_hash TEXT NOT NULL UNIQUE,
                source TEXT NOT NULL,
                importance INTEGER NOT NULL DEFAULT 3,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_memory_type_updated ON memory_items(type, updated_at DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized
    fun saveFact(content: String, source: String = "user", importance: Int = 3): JSONObject {
        val clean = content.trim().take(4000)
        require(clean.isNotBlank()) { "মেমোরিতে ফাঁকা তথ্য রাখা যাবে না।" }
        val normalized = clean.lowercase().replace(Regex("\\s+"), " ").trim()
        val hash = sha256(normalized)
        val now = System.currentTimeMillis()
        val encrypted = encrypt(clean)
        val db = writableDatabase
        val existingId = db.rawQuery(
            "SELECT id FROM memory_items WHERE normalized_hash = ? LIMIT 1",
            arrayOf(hash)
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }

        val values = ContentValues().apply {
            put("type", "fact")
            put("encrypted_content", encrypted)
            put("normalized_hash", hash)
            put("source", source.take(40))
            put("importance", importance.coerceIn(1, 5))
            put("updated_at", now)
        }
        val id = if (existingId != null) {
            db.update("memory_items", values, "id = ?", arrayOf(existingId.toString()))
            existingId
        } else {
            values.put("created_at", now)
            db.insertOrThrow("memory_items", null, values)
        }
        return JSONObject()
            .put("id", id)
            .put("content", clean)
            .put("source", source.take(40))
            .put("importance", importance.coerceIn(1, 5))
            .put("createdAt", now)
            .put("updatedAt", now)
    }

    @Synchronized
    fun getFacts(limit: Int = 100): JSONArray {
        val result = JSONArray()
        readableDatabase.query(
            "memory_items",
            arrayOf("id", "encrypted_content", "source", "importance", "created_at", "updated_at"),
            "type = ?",
            arrayOf("fact"),
            null,
            null,
            "importance DESC, updated_at DESC",
            limit.coerceIn(1, 200).toString()
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val content = decrypt(cursor.getString(1))
                result.put(
                    JSONObject()
                        .put("id", cursor.getLong(0))
                        .put("content", content)
                        .put("source", cursor.getString(2))
                        .put("importance", cursor.getInt(3))
                        .put("createdAt", cursor.getLong(4))
                        .put("updatedAt", cursor.getLong(5))
                )
            }
        }
        return result
    }

    @Synchronized
    fun importLegacyFacts(rawJson: String): Int {
        val rows = try { JSONArray(rawJson) } catch (_: Exception) { JSONArray() }
        var imported = 0
        for (i in 0 until rows.length()) {
            val item = rows.optJSONObject(i)
            val text = item?.optString("text")?.takeIf { it.isNotBlank() }
                ?: rows.optString(i).takeIf { it.isNotBlank() }
                ?: continue
            saveFact(text, source = "legacy-local-storage")
            imported++
        }
        return imported
    }

    @Synchronized
    fun deleteFact(id: Long): Boolean =
        writableDatabase.delete("memory_items", "id = ? AND type = ?", arrayOf(id.toString(), "fact")) > 0

    @Synchronized
    fun clearFacts(): Int =
        writableDatabase.delete("memory_items", "type = ?", arrayOf("fact"))

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val combined = cipher.iv + encrypted
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        val combined = Base64.decode(value, Base64.NO_WRAP)
        require(combined.size > GCM_IV_BYTES) { "মেমোরি ডেটা অসম্পূর্ণ।" }
        val iv = combined.copyOfRange(0, GCM_IV_BYTES)
        val encrypted = combined.copyOfRange(GCM_IV_BYTES, combined.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }

    companion object {
        private const val KEY_ALIAS = "rumin.memory.aes.v1"
        private const val GCM_IV_BYTES = 12
    }
}

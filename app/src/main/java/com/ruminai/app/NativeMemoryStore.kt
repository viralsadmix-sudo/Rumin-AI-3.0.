package com.ruminai.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypted, app-private memory storage.
 * Data is protected at rest with an Android Keystore AES-GCM key.
 * Android removes app-private files/keys on uninstall; this is not a cloud backup.
 */
internal class NativeMemoryStore(context: Context) {
    private val appContext = context.applicationContext
    private val file = File(appContext.filesDir, "rumin-memory-v1.enc")
    private val tempFile = File(appContext.filesDir, "rumin-memory-v1.tmp")
    private val backupFile = File(appContext.filesDir, "rumin-memory-v1.bak")

    @Synchronized
    fun load(): JSONObject {
        if (!file.exists()) return emptyState()
        return try {
            val bytes = file.readBytes()
            if (bytes.size < IV_LENGTH + 1) return emptyState()
            val iv = bytes.copyOfRange(0, IV_LENGTH)
            val encrypted = bytes.copyOfRange(IV_LENGTH, bytes.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_BITS, iv))
            val plain = cipher.doFinal(encrypted).toString(Charsets.UTF_8)
            JSONObject(plain)
        } catch (_: Exception) {
            // Preserve the encrypted file for diagnosis; never silently overwrite unreadable data.
            JSONObject().put("schemaVersion", 1).put("available", false)
                .put("error", "encrypted_memory_unavailable")
        }
    }

    @Synchronized
    fun save(rawJson: String): JSONObject {
        if (rawJson.length > MAX_CHARS) {
            return JSONObject().put("ok", false).put("message", "মেমোরি ডেটা নির্ধারিত সীমার চেয়ে বড়।")
        }
        return try {
            val state = JSONObject(rawJson)
            state.put("schemaVersion", 1)
            state.put("updatedAt", System.currentTimeMillis())
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val encrypted = cipher.doFinal(state.toString().toByteArray(Charsets.UTF_8))
            val output = cipher.iv + encrypted
            tempFile.writeBytes(output)
            if (backupFile.exists()) backupFile.delete()
            if (file.exists() && !file.renameTo(backupFile)) throw IllegalStateException("পুরোনো মেমোরি ফাইল নিরাপদে ব্যাকআপ করা যায়নি")
            if (!tempFile.renameTo(file)) {
                if (backupFile.exists()) backupFile.renameTo(file)
                throw IllegalStateException("নতুন মেমোরি ফাইল সংরক্ষণ করা যায়নি")
            }
            if (backupFile.exists()) backupFile.delete()
            JSONObject().put("ok", true).put("message", "মেমোরি এনক্রিপ্ট করে ফোনে সংরক্ষণ করা হয়েছে।")
                .put("bytes", output.size)
        } catch (e: Exception) {
            try { tempFile.delete() } catch (_: Exception) {}
            JSONObject().put("ok", false).put("message", "মেমোরি সংরক্ষণ করা যায়নি: ${e.message ?: "অজানা সমস্যা"}")
        }
    }

    @Synchronized
    fun clear(): JSONObject {
        return try {
            if (tempFile.exists()) tempFile.delete()
            if (backupFile.exists()) backupFile.delete()
            if (file.exists() && !file.delete()) throw IllegalStateException("মেমোরি ফাইল মুছতে পারিনি")
            JSONObject().put("ok", true).put("message", "এই ফোনের এনক্রিপ্ট করা মেমোরি মুছে ফেলা হয়েছে।")
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("message", "মেমোরি মুছতে সমস্যা: ${e.message ?: "অজানা সমস্যা"}")
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    private fun emptyState() = JSONObject()
        .put("schemaVersion", 1)
        .put("chatHistory", org.json.JSONArray())
        .put("memoryLog", org.json.JSONArray())
        .put("savedFacts", org.json.JSONArray())
        .put("memoryEnabled", true)

    companion object {
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "rumin_ai_memory_aes_gcm_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_LENGTH = 12
        private const val TAG_BITS = 128
        private const val MAX_CHARS = 2_000_000
    }
}

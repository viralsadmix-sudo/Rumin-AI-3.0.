package com.ruminai.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts the PC bearer token at rest with a non-exportable Android Keystore key. */
internal class CompanionTokenStore(context: Context) {
    private val prefs = context.getSharedPreferences("rumin_pc_pairing", Context.MODE_PRIVATE)
    private val alias = "rumin_pc_companion_token_v1"

    private fun getOrCreateKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    @Synchronized fun save(host: String, token: String) {
        require(host.isNotBlank() && token.length >= 32)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        val saved = prefs.edit()
            .putString("host", host)
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("token", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .commit()
        check(saved) { "PC Pairing Token ফোনে সংরক্ষণ করা যায়নি" }
    }

    @Synchronized fun hasPairing(): Boolean =
        !prefs.getString("host", null).isNullOrBlank() &&
            !prefs.getString("token", null).isNullOrBlank() &&
            !prefs.getString("iv", null).isNullOrBlank()

    @Synchronized fun readToken(): String? {
        if (!hasPairing()) return null
        return try {
            val iv = Base64.decode(prefs.getString("iv", "")!!, Base64.NO_WRAP)
            val encrypted = Base64.decode(prefs.getString("token", "")!!, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        } catch (_: Exception) { null }
    }

    @Synchronized fun clear() {
        prefs.edit().clear().commit()
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (store.containsAlias(alias)) store.deleteEntry(alias)
    }
}

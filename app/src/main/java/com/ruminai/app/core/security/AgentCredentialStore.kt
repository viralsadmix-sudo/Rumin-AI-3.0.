package com.ruminai.app.core.security

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Keeps provider credentials encrypted with an Android Keystore-backed master key. */
class AgentCredentialStore(context: Context) {
    private val preferences = EncryptedSharedPreferences.create(
        context,
        "rumin_agent_credentials",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun saveGeminiApiKey(key: String) {
        require(key.isNotBlank())
        preferences.edit().putString("gemini_api_key", key.trim()).apply()
    }

    fun readGeminiApiKey(): String? = preferences.getString("gemini_api_key", null)
    fun clearGeminiApiKey() { preferences.edit().remove("gemini_api_key").apply() }
}

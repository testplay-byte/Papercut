package com.papercut.app.core.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Encrypted home for API key secrets — the fix for the old app storing keys in
 * plaintext inside the user's shared folder.
 *
 * Secrets live in EncryptedSharedPreferences (Android Keystore-backed AES-GCM),
 * keyed by KeyEntry.id. The settings JSON only ever references key metadata.
 * If the keystore is ever unrecoverable we wipe the orphaned metadata so the UI
 * never shows a key that cannot be used.
 */
class SecretStore(context: Context) {

    private val prefsName = "papercut_secrets"

    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            prefsName,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun putKey(entryId: String, secret: String) {
        prefs.edit().putString(entryId, secret).apply()
    }

    fun readKey(entryId: String): String? =
        try {
            prefs.getString(entryId, null)
        } catch (_: Exception) {
            // Keystore key lost (device restore / factory reset scenario).
            null
        }

    fun deleteKey(entryId: String) {
        prefs.edit().remove(entryId).apply()
    }

    /**
     * Delete secrets for keys the user removed. Callers must route deletions
     * through here — EncryptedSharedPreferences encrypts its key names, so we
     * cannot enumerate/orphan-sweep it later; metadata is the index.
     */
    fun deleteKeys(entryIds: List<String>) {
        prefs.edit().apply { entryIds.forEach { remove(it) } }.apply()
    }
}

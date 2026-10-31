package com.melluh.neotime.auth

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.melluh.neotime.timeedit.Account
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** AES-GCM with a key that never leaves the Android Keystore. */
internal object Crypto {
    private const val ALIAS = "neotime-session"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LENGTH = 12

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
        }.generateKey()
    }

    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(cipher.iv + cipher.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }

    /** Null when the data can't be decrypted, e.g. after the key was lost on a restore. */
    fun decrypt(encoded: String): String? = runCatching {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, IV_LENGTH))
        String(cipher.doFinal(bytes, IV_LENGTH, bytes.size - IV_LENGTH))
    }.getOrNull()
}

/** Persists the TimeEdit auth token and account, encrypted. */
class SessionStore(private val dataStore: DataStore<Preferences>) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun token(): String? = read(TOKEN)

    suspend fun account(): Account? = read(ACCOUNT)?.let { runCatching { json.decodeFromString<Account>(it) }.getOrNull() }

    suspend fun save(token: String?, account: Account) {
        dataStore.edit { prefs ->
            if (token != null) prefs[TOKEN] = Crypto.encrypt(token) else prefs.remove(TOKEN)
            prefs[ACCOUNT] = Crypto.encrypt(json.encodeToString(Account.serializer(), account))
        }
    }

    suspend fun clearToken() {
        dataStore.edit { it.remove(TOKEN) }
    }

    suspend fun clear() {
        dataStore.edit {
            it.remove(TOKEN)
            it.remove(ACCOUNT)
        }
    }

    private suspend fun read(key: Preferences.Key<String>): String? =
        dataStore.data.first()[key]?.let(Crypto::decrypt)

    private companion object {
        val TOKEN = stringPreferencesKey("session_token")
        val ACCOUNT = stringPreferencesKey("session_account")
    }
}

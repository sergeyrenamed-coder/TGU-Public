package com.rnmd.tgu.account

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.CodeVerifierCache
import io.github.jan.supabase.auth.FlowType
import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.providers.OAuthProvider
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.serialization.json.Json
import java.io.IOException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

   
                                                                                           
                                                                                            
   
object RnmdIdClient {
    const val SUPABASE_URL = "https://hscsswcevngohjdyetjo.supabase.co"
    const val SUPABASE_PUBLISHABLE_KEY = "sb_publishable_tTZW6Omtev4wdH8TvJ6DTQ_SV-XCh6G"
    const val AUTH_REDIRECT_URI = "tgu://auth/callback"

    @Volatile
    private var instance: io.github.jan.supabase.SupabaseClient? = null

    fun get(context: Context): io.github.jan.supabase.SupabaseClient {
        instance?.let { return it }
        return synchronized(this) {
            instance ?: run {
                val secureStorage = EncryptedAuthStorage(context.applicationContext)
                createSupabaseClient(SUPABASE_URL, SUPABASE_PUBLISHABLE_KEY) {
                    install(Auth) {
                        flowType = FlowType.PKCE
                        scheme = "tgu"
                        host = "auth"
                        defaultRedirectUrl = AUTH_REDIRECT_URI
                        sessionManager = secureStorage
                        codeVerifierCache = secureStorage
                    }
                }.also { instance = it }
            }
        }
    }

    fun telegramProvider(): OAuthProvider = object : OAuthProvider() {
        override val name = "custom:telegram"
    }
}

   
                                                                                          
                                      
   
private class EncryptedAuthStorage(context: Context) : SessionManager, CodeVerifierCache {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    override suspend fun saveSession(session: UserSession) {
        val serialized = json.encodeToString(UserSession.serializer(), session)
        saveEncrypted(SESSION_KEY, serialized)
    }

    override suspend fun loadSession(): UserSession? {
        val serialized = try {
            readEncrypted(SESSION_KEY)
        } catch (_: Exception) {
            preferences.edit().remove(SESSION_KEY).commit()
            null
        } ?: return null

        return try {
            json.decodeFromString(UserSession.serializer(), serialized)
        } catch (_: Exception) {
            preferences.edit().remove(SESSION_KEY).commit()
            null
        }
    }

    override suspend fun deleteSession() {
        check(preferences.edit().remove(SESSION_KEY).commit()) {
            "Could not remove the encrypted RNMD ID session."
        }
    }

    override suspend fun saveCodeVerifier(codeVerifier: String) {
        saveEncrypted(VERIFIER_KEY, codeVerifier)
    }

    override suspend fun loadCodeVerifier(): String? = try {
        readEncrypted(VERIFIER_KEY)
    } catch (_: Exception) {
        preferences.edit().remove(VERIFIER_KEY).commit()
        null
    }

    override suspend fun deleteCodeVerifier() {
        check(preferences.edit().remove(VERIFIER_KEY).commit()) {
            "Could not remove the encrypted RNMD ID PKCE verifier."
        }
    }

    @Synchronized
    private fun saveEncrypted(storageKey: String, value: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val encoded = Base64.encodeToString(encrypted, Base64.NO_WRAP)
        check(preferences.edit().putString(storageKey, encoded).commit()) {
            "Could not persist encrypted RNMD ID authentication data."
        }
    }

    @Synchronized
    private fun readEncrypted(storageKey: String): String? {
        val encoded = preferences.getString(storageKey, null) ?: return null
        val bytes = try {
            Base64.decode(encoded, Base64.NO_WRAP)
        } catch (error: IllegalArgumentException) {
            throw IOException("Encrypted RNMD ID data is malformed.", error)
        }
        if (bytes.size <= IV_BYTES + TAG_BYTES) {
            throw IOException("Encrypted RNMD ID data is incomplete.")
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(),
            GCMParameterSpec(TAG_BITS, bytes.copyOfRange(0, IV_BYTES)),
        )
        return cipher.doFinal(bytes.copyOfRange(IV_BYTES, bytes.size)).toString(Charsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(KEY_ALIAS)) {
            return keyStore.getKey(KEY_ALIAS, null) as SecretKey
        }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFERENCES = "rnmd_id_auth_secure"
        const val SESSION_KEY = "encrypted_session"
        const val VERIFIER_KEY = "encrypted_pkce_verifier"
        const val KEY_ALIAS = "rnmd_id_auth_aes_gcm"
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BYTES = 16
        const val TAG_BITS = 128
    }
}

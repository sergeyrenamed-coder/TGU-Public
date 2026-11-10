package com.rnmd.tgu.proxy

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.IOException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

                                                                                      
class LocalProxySettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    fun shouldRestoreAfterUpdate(): Boolean =
        prefs.getBoolean(RESTORE_AFTER_UPDATE_KEY, false)

    fun setRestoreAfterUpdate(enabled: Boolean) {
        check(prefs.edit().putBoolean(RESTORE_AFTER_UPDATE_KEY, enabled).commit()) {
            "Could not persist local proxy restore state."
        }
    }

    @Synchronized
    fun getOrCreateSecret(): String {
        val stored = prefs.getString(SECRET_KEY, null)
        if (!stored.isNullOrBlank()) return decrypt(stored)
        val secret = ProxyConfig.generateSecret()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.iv + cipher.doFinal(secret.toByteArray(Charsets.US_ASCII))
        check(prefs.edit().putString(SECRET_KEY, Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit()) { "Could not save the encrypted local MTProto secret." }
        return secret
    }
       
                                                                               
                                                                                       
       
    fun telegramProxyUri(): Uri = Uri.Builder()
        .scheme("tg").authority("proxy")
        .appendQueryParameter("server", LOCAL_HOST)
        .appendQueryParameter("port", LOCAL_PORT.toString())
        .appendQueryParameter("secret", "dd" + getOrCreateSecret())
        .build()
    private fun decrypt(encoded: String): String {
        val bytes = try { Base64.decode(encoded, Base64.NO_WRAP) }
        catch (e: IllegalArgumentException) { throw IOException("Stored local proxy settings are corrupted.", e) }
        if (bytes.size <= IV_BYTES + TAG_BYTES) throw IOException("Stored local proxy settings are incomplete.")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_BITS, bytes.copyOfRange(0, IV_BYTES)))
        val value = cipher.doFinal(bytes.copyOfRange(IV_BYTES, bytes.size)).toString(Charsets.US_ASCII)
        require(value.matches(Regex("^[0-9a-fA-F]{32}$"))) { "Stored local MTProto secret has an invalid format." }
        return value
    }
    private fun getOrCreateKey(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        if (store.containsAlias(KEY_ALIAS)) return store.getKey(KEY_ALIAS, null) as SecretKey
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build())
        return generator.generateKey()
    }
    companion object {
        const val LOCAL_HOST = "127.0.0.1"
        const val LOCAL_PORT = 1443
        private const val PREFS_NAME = "tgu_local_proxy_settings"
        private const val SECRET_KEY = "encrypted_mtproto_secret"
        private const val RESTORE_AFTER_UPDATE_KEY = "restore_after_package_update"
        private const val KEY_ALIAS = "tgu_local_mtproto_aes_gcm"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BYTES = 16
        private const val TAG_BITS = 128
    }
}
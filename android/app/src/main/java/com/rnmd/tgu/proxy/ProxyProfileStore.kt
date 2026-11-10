package com.rnmd.tgu.proxy

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.*
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class ProxyType { MTPROTO, SOCKS5 }

data class ProxyProfile(
    val id: String,
    val name: String,
    val type: ProxyType,
    val host: String,
    val port: Int,
    val secret: String = "",
    val username: String = "",
    val password: String = ""
) {
    fun validationError(): String? {
        if (name.isBlank() || name.length > 48) return "Enter a profile name (up to 48 characters)."
        if (host.isBlank() || host.length > 253 || host.any { it.isWhitespace() } ||
            host.contains("://") || host.any { it in "/?#@" }
        ) return "Enter a hostname or IP address without a URL or path."
        if (port !in 1..65535) return "Port must be between 1 and 65535."
        return when (type) {
            ProxyType.MTPROTO -> when {
                secret.length !in 32..1024 || secret.length % 2 != 0 ->
                    "MTProto secret must be an even-length hexadecimal string (at least 32 characters)."
                !secret.all { it in "0123456789abcdefABCDEF" } ->
                    "MTProto secret must contain hexadecimal characters only."
                else -> null
            }
            ProxyType.SOCKS5 -> when {
                username.isBlank() != password.isBlank() ->
                    "Enter both SOCKS5 username and password, or leave both empty."
                username.toByteArray(Charsets.UTF_8).size > 255 ||
                    password.toByteArray(Charsets.UTF_8).size > 255 ->
                    "SOCKS5 credentials must each be at most 255 UTF-8 bytes."
                else -> null
            }
        }
    }
}

                                                                                      
class ProxyProfileStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun load(): List<ProxyProfile> {
        val encoded = prefs.getString(DATA_KEY, null) ?: return emptyList()
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        require(bytes.size > IV_BYTES + TAG_BYTES) { "Encrypted profile data is incomplete." }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            key(),
            GCMParameterSpec(TAG_BITS, bytes.copyOfRange(0, IV_BYTES))
        )
        val plaintext = cipher.doFinal(bytes.copyOfRange(IV_BYTES, bytes.size))
        DataInputStream(ByteArrayInputStream(plaintext)).use { input ->
            require(input.readInt() == MAGIC) { "Unknown proxy profile format." }
            require(input.readInt() == VERSION) { "Unsupported proxy profile format version." }
            val count = input.readInt()
            require(count in 0..MAX_PROFILES) { "Invalid proxy profile count." }
            return buildList {
                repeat(count) {
                    add(
                        ProxyProfile(
                            id = input.readUTF(),
                            name = input.readUTF(),
                            type = ProxyType.valueOf(input.readUTF()),
                            host = input.readUTF(),
                            port = input.readInt(),
                            secret = input.readUTF(),
                            username = input.readUTF(),
                            password = input.readUTF()
                        )
                    )
                }
            }
        }
    }

    @Synchronized
    fun upsert(profile: ProxyProfile): List<ProxyProfile> {
        val current = load().filterNot { it.id == profile.id }
        require(current.size < MAX_PROFILES) { "You can save up to $MAX_PROFILES profiles." }
        val updated = (current + profile).sortedBy { it.name.lowercase() }
        persist(updated)
        return updated
    }

    @Synchronized
    fun delete(id: String): List<ProxyProfile> {
        val updated = load().filterNot { it.id == id }
        persist(updated)
        return updated
    }

    private fun persist(profiles: List<ProxyProfile>) {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            out.writeInt(profiles.size)
            profiles.forEach {
                out.writeUTF(it.id)
                out.writeUTF(it.name)
                out.writeUTF(it.type.name)
                out.writeUTF(it.host)
                out.writeInt(it.port)
                out.writeUTF(it.secret)
                out.writeUTF(it.username)
                out.writeUTF(it.password)
            }
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val bytes = cipher.iv + cipher.doFinal(buffer.toByteArray())
        check(
            prefs.edit().putString(DATA_KEY, Base64.encodeToString(bytes, Base64.NO_WRAP)).commit()
        ) { "Could not write encrypted profiles." }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        if (store.containsAlias(ALIAS)) return store.getKey(ALIAS, null) as SecretKey
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFS = "tgu_encrypted_proxy_profiles"
        const val DATA_KEY = "profiles"
        const val ALIAS = "tgu_proxy_profiles_aes_gcm"
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BYTES = 16
        const val TAG_BITS = 128
        const val MAGIC = 0x54475550
        const val VERSION = 1
        const val MAX_PROFILES = 50
    }
}

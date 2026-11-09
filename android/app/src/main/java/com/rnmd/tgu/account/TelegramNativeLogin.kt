package com.rnmd.tgu.account

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Telegram's Android native login handshake.
 *
 * Telegram cross-app and token endpoints are reached through a narrow Supabase
 * Edge Function. This keeps Telegram network traffic out of the external browser
 * and lets Telegram itself handle the consent screen through its app link.
 */
object TelegramNativeLogin {
    const val CLIENT_ID = "9135700460"
    const val REDIRECT_URI = "https://app2482922030-login.tg.dev/tglogin"
    private const val EDGE_FUNCTION_URL =
        "${RnmdIdClient.SUPABASE_URL}/functions/v1/telegram-native-login"
    private const val SUPABASE_ID_TOKEN_GRANT =
        "${RnmdIdClient.SUPABASE_URL}/auth/v1/token?grant_type=id_token"

    private val http = OkHttpClient.Builder()
        .callTimeout(25, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    suspend fun start(context: Context) {
        val verifier = generateVerifier()
        val challenge = generateChallenge(verifier)
        PendingVerifierStore.save(context.applicationContext, verifier)

        try {
            val payload = withContext(Dispatchers.IO) {
                postJson(
                    EDGE_FUNCTION_URL,
                    JSONObject()
                        .put("action", "start")
                        .put("code_challenge", challenge),
                )
            }

            val loginUrl = payload.optString("url")
            val uri = runCatching { Uri.parse(loginUrl) }.getOrNull()
                ?: throw IOException("Telegram не вернул ссылку для входа.")

            val isTelegramAppLink = uri.scheme.equals("tg", ignoreCase = true)
            if (!isTelegramAppLink) {
                throw IOException("Telegram не вернул ссылку для открытия приложения. Внешний браузер не запускается.")
            }

            val intent = Intent(Intent.ACTION_VIEW, uri)
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (error: Exception) {
            PendingVerifierStore.clear(context.applicationContext)
            if (error is IOException) throw error
            throw IOException(
                "Не удалось открыть нативный вход Telegram. Проверь, что Telegram установлен и обновлён.",
                error,
            )
        }
    }

    fun isCallback(uri: Uri): Boolean =
        uri.scheme.equals("https", ignoreCase = true) &&
            uri.host.equals("app2482922030-login.tg.dev", ignoreCase = true) &&
            uri.path == "/tglogin"

    suspend fun complete(
        context: Context,
        uri: Uri,
        supabase: SupabaseClient,
    ) {
        require(isCallback(uri)) { "Неверный адрес возврата Telegram." }

        val providerError = uri.getQueryParameter("error")
        if (!providerError.isNullOrBlank()) {
            PendingVerifierStore.clear(context.applicationContext)
            throw IOException("Вход через Telegram отменён или отклонён.")
        }

        val code = uri.getQueryParameter("code")
            ?.takeIf { it.isNotBlank() && it.length <= 8_192 }
            ?: run {
                PendingVerifierStore.clear(context.applicationContext)
                throw IOException("Telegram не вернул код авторизации. Попробуй войти ещё раз.")
            }

        // Consume once before network requests: an App Link callback cannot replay this verifier.
        val verifier = PendingVerifierStore.take(context.applicationContext)
            ?: throw IOException("Сеанс входа истёк. Нажми кнопку входа ещё раз.")

        val tokenPayload = withContext(Dispatchers.IO) {
            postJson(
                EDGE_FUNCTION_URL,
                JSONObject()
                    .put("action", "finish")
                    .put("code", code)
                    .put("code_verifier", verifier),
            )
        }

        val idToken = tokenPayload.optString("id_token")
            .takeIf { it.count { char -> char == '.' } == 2 && it.length <= 16_384 }
            ?: throw IOException("Telegram не вернул действительный ID Token.")

        val sessionPayload = withContext(Dispatchers.IO) {
            postJson(
                SUPABASE_ID_TOKEN_GRANT,
                JSONObject()
                    .put("provider", "custom:telegram")
                    .put("id_token", idToken),
            )
        }

        val session = try {
            json.decodeFromString(UserSession.serializer(), sessionPayload.toString())
        } catch (error: Exception) {
            throw IOException("Supabase не вернул корректную сессию RNMD ID.", error)
        }

        if (session.accessToken.isBlank() || session.refreshToken.isBlank()) {
            throw IOException("Supabase вернул неполную сессию RNMD ID.")
        }

        supabase.auth.importSession(session)
    }

    private fun postJson(
        url: String,
        body: JSONObject
    ): JSONObject {
        val requestBody = body.toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val builder = Request.Builder()
            .url(url)
            .post(requestBody)
            .header("apikey", RnmdIdClient.SUPABASE_PUBLISHABLE_KEY)
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")


        http.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val parsed = runCatching { JSONObject(text) }.getOrNull()
            if (!response.isSuccessful) {
                val serverMessage = parsed?.optString("error")
                    ?.takeIf { it.isNotBlank() && it != "null" }
                val message = when {
                    response.request.url.encodedPath.endsWith("/telegram-native-login") ->
                        serverMessage ?: "Не удалось связаться с сервисом Telegram Login."
                    response.code == 400 || response.code == 401 ->
                        "Supabase не принял Telegram ID Token. Проверь настройки RNMD ID."
                    else -> "Сервис RNMD ID временно недоступен (HTTP ${response.code})."
                }
                throw IOException(message)
            }
            return parsed ?: throw IOException("Сервер вернул некорректный ответ.")
        }
    }

    private fun generateVerifier(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(
            bytes,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
    }

    private fun generateChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(
            digest,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
    }
}

/**
 * Short-lived PKCE verifier encrypted independently from the Supabase session.
 * Android Keystore keeps the AES key non-exportable; the verifier is consumed once.
 */
private object PendingVerifierStore {
    private const val PREFERENCES = "tgu_telegram_native_login"
    private const val VALUE_KEY = "encrypted_pending_verifier"
    private const val KEY_ALIAS = "tgu_telegram_pkce_aes_gcm"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val MAX_AGE_MS = 10 * 60 * 1000L

    @Synchronized
    fun save(context: Context, verifier: String) {
        val value = "${System.currentTimeMillis()}\n$verifier"
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val encoded = Base64.encodeToString(encrypted, Base64.NO_WRAP)
        check(
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit().putString(VALUE_KEY, encoded).commit(),
        ) { "Не удалось сохранить состояние входа." }
    }

    @Synchronized
    fun take(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val encoded = prefs.getString(VALUE_KEY, null) ?: return null
        check(prefs.edit().remove(VALUE_KEY).commit()) {
            "Не удалось завершить состояние входа."
        }

        return try {
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            if (bytes.size <= IV_BYTES + 16) return null

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(TAG_BITS, bytes.copyOfRange(0, IV_BYTES)),
            )
            val value = cipher.doFinal(bytes.copyOfRange(IV_BYTES, bytes.size))
                .toString(Charsets.UTF_8)
            val separator = value.indexOf('\n')
            if (separator <= 0) return null

            val createdAt = value.substring(0, separator).toLongOrNull() ?: return null
            val verifier = value.substring(separator + 1)
            if (System.currentTimeMillis() - createdAt !in 0..MAX_AGE_MS) return null
            verifier.takeIf { it.matches(Regex("[A-Za-z0-9._~-]{43,128}")) }
        } catch (_: Exception) {
            null
        }
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit().remove(VALUE_KEY).commit()
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
}

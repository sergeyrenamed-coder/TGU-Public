package com.rnmd.tgu.account

import android.content.Context
import android.os.Build
import android.net.Uri
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

data class RnmdProfile(
    val id: String,
    val telegramUsername: String?,
    val displayName: String?,
    val avatarUrl: String?,
)

data class RnmdDevice(
    val id: String,
    val deviceKey: String,
    val name: String,
    val platform: String,
    val lastSeenAt: String,
)

data class RnmdOrder(
    val id: String,
    val productCode: String,
    val amountMinor: Int,
    val currency: String,
    val status: String,
    val createdAt: String,
)

data class RnmdAccountSnapshot(
    val profile: RnmdProfile?,
    val devices: List<RnmdDevice>,
    val orders: List<RnmdOrder>,
    val hasTguPlus: Boolean,
)

class RnmdAccountRepository(context: Context) {
    private val appContext = context.applicationContext
    private val http = OkHttpClient.Builder()
        .callTimeout(15, TimeUnit.SECONDS)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    fun getOrCreateDeviceKey(): String {
        val preferences = appContext.getSharedPreferences(DEVICE_PREFS, Context.MODE_PRIVATE)
        val stored = preferences.getString(DEVICE_KEY, null)
        if (!stored.isNullOrBlank()) return stored

        val generated = UUID.randomUUID().toString()
        check(preferences.edit().putString(DEVICE_KEY, generated).commit()) {
            "Could not save the TGU installation identifier."
        }
        return generated
    }

    fun loadAccount(accessToken: String, userId: String): RnmdAccountSnapshot {
        requireUuid(userId)
        val profile = getArray(
            accessToken,
            "/rest/v1/profiles?select=id,telegram_username,display_name,avatar_url&id=eq.$userId&limit=1",
        ).optJSONObject(0)?.let { item ->
            RnmdProfile(
                id = item.optString("id"),
                telegramUsername = item.stringOrNull("telegram_username"),
                displayName = item.stringOrNull("display_name"),
                avatarUrl = item.stringOrNull("avatar_url"),
            )
        }

        val devices = getArray(
            accessToken,
            "/rest/v1/devices?select=id,device_key,device_name,platform,last_seen_at&user_id=eq.$userId&revoked_at=is.null&order=last_seen_at.desc&limit=50",
        ).toList().mapNotNull { item ->
            val id = item.stringOrNull("id") ?: return@mapNotNull null
            RnmdDevice(
                id = id,
                deviceKey = item.stringOrNull("device_key") ?: return@mapNotNull null,
                name = item.stringOrNull("device_name") ?: "Устройство без имени",
                platform = item.stringOrNull("platform") ?: "other",
                lastSeenAt = item.stringOrNull("last_seen_at") ?: "",
            )
        }

        val orders = getArray(
            accessToken,
            "/rest/v1/orders?select=id,product_code,amount_minor,currency,status,created_at&user_id=eq.$userId&order=created_at.desc&limit=20",
        ).toList().mapNotNull { item ->
            val id = item.stringOrNull("id") ?: return@mapNotNull null
            RnmdOrder(
                id = id,
                productCode = item.stringOrNull("product_code") ?: "RNMD",
                amountMinor = item.optInt("amount_minor", 0),
                currency = item.stringOrNull("currency") ?: "RUB",
                status = item.stringOrNull("status") ?: "pending",
                createdAt = item.stringOrNull("created_at") ?: "",
            )
        }

        val entitlements = getArray(
            accessToken,
            "/rest/v1/entitlements?select=product_code,expires_at&user_id=eq.$userId&status=eq.active&limit=50",
        )
        val hasTguPlus = entitlements.toList().any { item ->
            item.stringOrNull("product_code") == "TGU_PLUS_LIFETIME" &&
                (item.stringOrNull("expires_at") == null || !isExpired(item.stringOrNull("expires_at")))
        }

        return RnmdAccountSnapshot(profile, devices, orders, hasTguPlus)
    }

    fun registerCurrentDevice(accessToken: String, appVersion: String) {
        val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".trim().take(120)
        val body = JSONObject()
            .put("p_device_key", getOrCreateDeviceKey())
            .put("p_device_name", deviceName)
            .put("p_app_version", appVersion)
        postRpc(accessToken, "register_tgu_device", body)
    }

    fun revokeDevice(accessToken: String, deviceId: String): Boolean {
        requireUuid(deviceId)
        val result = postRpc(accessToken, "revoke_tgu_device", JSONObject().put("p_device_id", deviceId))
        return result.trim().equals("true", ignoreCase = true)
    }

    private fun getArray(accessToken: String, path: String): JSONArray {
        val response = execute(accessToken, path, "GET")
        return try {
            JSONArray(response)
        } catch (_: Exception) {
            throw IOException("RNMD ID returned invalid account data.")
        }
    }

    private fun postRpc(accessToken: String, function: String, body: JSONObject): String =
        execute(accessToken, "/rest/v1/rpc/$function", "POST", body.toString())

    private fun execute(
        accessToken: String,
        path: String,
        method: String,
        body: String? = null,
    ): String {
        require(accessToken.isNotBlank()) { "RNMD ID session is missing." }
        val url = Uri.parse(RnmdIdClient.SUPABASE_URL + path).toString()
        val builder = Request.Builder()
            .url(url)
            .header("apikey", RnmdIdClient.SUPABASE_PUBLISHABLE_KEY)
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "application/json")

        if (method == "POST") {
            builder.post(
                (body ?: "{}").toRequestBody("application/json; charset=utf-8".toMediaType()),
            )
        } else {
            builder.get()
        }

        http.newCall(builder.build()).execute().use { response ->
            val responseText = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                Log.w(TAG, "RNMD ID request failed: $method $path (${response.code})")
                throw IOException("Сервер RNMD ID вернул ошибку ${response.code}.")
            }
            return responseText
        }
    }

    private fun JSONArray.toList(): List<JSONObject> =
        (0 until length()).mapNotNull { optJSONObject(it) }

    private fun JSONObject.stringOrNull(key: String): String? =
        optString(key).takeUnless { it.isBlank() || it == "null" }

    private fun requireUuid(value: String) {
        require(runCatching { UUID.fromString(value) }.isSuccess) {
            "Invalid RNMD ID identifier."
        }
    }

    private fun isExpired(value: String?): Boolean {
        if (value.isNullOrBlank()) return false
        return runCatching {
            java.time.Instant.parse(value).isBefore(java.time.Instant.now())
        }.getOrDefault(false)
    }

    private companion object {
        const val DEVICE_PREFS = "tgu_installation_identity"
        const val DEVICE_KEY = "device_key"
        const val TAG = "RnmdAccountRepository"
    }
}

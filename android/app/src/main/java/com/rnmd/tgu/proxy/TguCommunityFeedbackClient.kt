package com.rnmd.tgu.proxy

import android.content.Context
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

data class CommunityProxyProbeReport(
    val proxyId: String,
    val proxyType: String,
    val outcome: String,
    val latencyMs: Long?,
    val failureCode: String,
)

class TguCommunityFeedbackClient(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(): Boolean {
        // Default to CN feedback on first run, but preserve an explicit saved opt-out.
        if (!prefs.contains(KEY_ENABLED)) {
            prefs.edit().putBoolean(KEY_ENABLED, true).apply()
        }
        return prefs.getBoolean(KEY_ENABLED, true)
    }

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun isConfigured(): Boolean = apiBaseUrl().isNotBlank()

    /**
     * Uploads only aggregate probe results. The installation UUID is generated
     * locally and HMAC-hashed by the server; no IP or Telegram account data is sent.
     * The calls are asynchronous and never block proxy selection.
     */
    fun submitScan(scanId: String, reports: List<CommunityProxyProbeReport>) {
        if (!isEnabled() || reports.isEmpty()) return
        val baseUrl = apiBaseUrl()
        if (baseUrl.isBlank()) return

        val instanceId = prefs.getString(KEY_INSTANCE_ID, null)
            ?: UUID.randomUUID().toString().also {
                prefs.edit().putString(KEY_INSTANCE_ID, it).apply()
            }
        val chunks = reports.distinctBy { it.proxyId }.chunked(MAX_BATCH_SIZE)
        chunks.forEach { chunk ->
            val payload = JSONObject()
                .put("client_instance_id", instanceId)
                .put("scan_id", scanId)
                .put("client_platform", "android")
                .put("app_version", com.rnmd.tgu.BuildConfig.VERSION_NAME)
                .put("network_type", "unknown")
            val items = JSONArray()
            chunk.forEach { report ->
                val item = JSONObject()
                    .put("proxy_id", report.proxyId)
                    .put("proxy_type", report.proxyType)
                    .put("outcome", report.outcome)
                    .put("failure_code", report.failureCode)
                if (report.latencyMs != null) item.put("latency_ms", report.latencyMs)
                items.put(item)
            }
            payload.put("reports", items)

            val request = Request.Builder()
                .url("$baseUrl/.netlify/functions/tgu-api?route=feedback")
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
            CLIENT.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    // Community feedback is best-effort and must never affect the local scan.
                }

                override fun onResponse(call: Call, response: Response) {
                    val responseText = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        android.util.Log.w(TAG, "Community feedback rejected: HTTP ${response.code} ${responseText.take(180)}")
                    } else {
                        val parsed = runCatching { JSONObject(responseText) }.getOrNull()
                        val accepted = parsed?.optInt("accepted", 0) ?: 0
                        val valid = parsed?.optInt("valid", 0) ?: 0
                        android.util.Log.i(TAG, "Community feedback accepted: $accepted/$valid")
                    }
                    response.close()
                }
            })
        }
    }

    private fun apiBaseUrl(): String {
        val value = com.rnmd.tgu.BuildConfig.TGU_COMMUNITY_API_BASE_URL.trim().trimEnd('/')
        if (!value.startsWith("https://", ignoreCase = true)) return ""
        return value
    }

    companion object {
        private const val TAG = "TGUCommunityFeedback"
        private const val PREFS = "tgu_community_feedback"
        private const val KEY_ENABLED = "feedback_enabled"
        private const val KEY_INSTANCE_ID = "client_instance_id"
        private const val MAX_BATCH_SIZE = 100
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val CLIENT = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}

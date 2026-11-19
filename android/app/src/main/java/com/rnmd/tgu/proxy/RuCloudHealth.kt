package com.rnmd.tgu.proxy

import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Optional Russian-cloud reachability hint. Never substitutes a local Telegram probe. */
data class RuCloudEvidence(val latencyMs: Long, val checkedAtEpochMs: Long)

object RuCloudHealth {
    const val FEED_URL = "https://raw.githubusercontent.com/sergeyrenamed-coder/TGU-Proxy-Directory/main/data/ru-health.json"
    private const val MAX_AGE_MS = 24L * 60L * 60L * 1000L
    private val ID = Regex("^[0-9a-f]{20}$")

    private fun String.asUtcEpochMs(): Long? =
        runCatching { Instant.parse(this).toEpochMilli() }.getOrNull()

    private fun JsonObject.text(key: String): String? =
        this[key]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }

    fun isFreshFeed(raw: String?, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (raw.isNullOrBlank() || raw.length > 1_000_000) return false
        return runCatching {
            val root = Json.parseToJsonElement(raw).jsonObject
            val generated = root.text("generated_at")?.asUtcEpochMs() ?: return false
            root["schema_version"]?.jsonPrimitive?.intOrNull == 1 &&
                root.text("checker") == "yandex_cloud_ru" &&
                root.text("status") == "active" &&
                root["ttl_hours"]?.jsonPrimitive?.intOrNull == 24 &&
                generated in (nowMs - MAX_AGE_MS)..nowMs &&
                root["checks"] is JsonArray
        }.getOrDefault(false)
    }

    fun verified(raw: String?, nowMs: Long = System.currentTimeMillis()): Map<String, RuCloudEvidence> {
        if (!isFreshFeed(raw, nowMs)) return emptyMap()
        return runCatching {
            val root = Json.parseToJsonElement(raw!!).jsonObject
            val matches = LinkedHashMap<String, RuCloudEvidence>()
            for (element in root["checks"]!!.jsonArray) {
                val item = element as? JsonObject ?: continue
                val id = item.text("proxy_id") ?: continue
                if (!ID.matches(id) || item.text("status") != "working") continue
                val checked = item.text("checked_at")?.asUtcEpochMs() ?: continue
                if (checked !in (nowMs - MAX_AGE_MS)..nowMs) continue
                val latency = item["latency_ms"]?.jsonPrimitive?.longOrNull ?: continue
                if (latency !in 0L..60_000L) continue
                matches[id] = RuCloudEvidence(latency, checked)
            }
            matches
        }.getOrDefault(emptyMap())
    }
}

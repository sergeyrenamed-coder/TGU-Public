package com.rnmd.tgu.proxy

import android.content.Context
import android.net.Uri
import com.rnmd.tgu.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class FreedomCheckerProxy(
    val id: String,
    val typeLabel: String,
    val endpoint: String,
    val lastChecked: String,
    val profile: ProxyProfile?,
    val supported: Boolean,
    val unsupportedReason: String? = null,
    val serverVerified: Boolean = false,
    val serverRttMs: Long? = null,
    val stale: Boolean = false,
    val directoryId: String? = null,
    val communityReports: Int = 0,
    val communityUniqueReporters: Int = 0,
    val communitySuccessRate: Double? = null,
    val communityMedianRttMs: Long? = null,
)

data class FreedomCheckerSnapshot(
    val entries: List<FreedomCheckerProxy>,
    val fetchedAtEpochMs: Long,
    val generatedAt: String?,
)

class FreedomCheckerCatalogRepository(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun cached(): FreedomCheckerSnapshot? {
        val raw = prefs.getString(KEY_JSON, null) ?: return null
        val fetchedAt = prefs.getLong(KEY_FETCHED_AT, 0L)
        return runCatching { parse(raw, fetchedAt) }.getOrNull()
    }

       
                                                                                 
                                                                                
       
    @Synchronized
    fun refresh(): FreedomCheckerSnapshot {
        val failures = mutableListOf<String>()
        for (sourceUrl in CATALOG_URLS) {
            try {
                val request = Request.Builder()
                    .url(sourceUrl)
                    .header("Accept", "application/json")
                    .header("Cache-Control", "no-cache")
                    .header("User-Agent", "TGU-Android/0.8.1")
                    .get()
                    .build()

                CLIENT.newCall(request).execute().use { response ->
                    if (response.code in 300..399) {
                        val location = response.header("Location").orEmpty()
                        throw IOException("Catalog redirected the JSON request. Target: ${location.take(120)}")
                    }
                    if (!response.isSuccessful) {
                        throw IOException("Catalog returned HTTP ${response.code}.")
                    }
                    if (response.request.url.host !in allowedSourceHosts()) {
                        throw IOException("Unexpected catalog host: ${response.request.url.host}")
                    }
                    val raw = response.body?.string()?.takeIf { it.isNotBlank() }
                        ?: throw IOException("Catalog returned an empty response.")
                    val now = System.currentTimeMillis()
                    val snapshot = parse(raw, now)
                    if (snapshot.entries.isEmpty()) {
                        throw IOException(
                            "JSON loaded, but no complete MTProto/SOCKS5 connection records were found. " +
                                "The catalog may have changed its schema or omitted proxy credentials."
                        )
                    }
                    prefs.edit()
                        .putString(KEY_JSON, raw)
                        .putLong(KEY_FETCHED_AT, now)
                        .apply()
                    return snapshot
                }
            } catch (error: Exception) {
                val sourceName = when (sourceUrl) {
                    SNAPSHOT_URL -> "TGU Proxy Directory"
                    COMMUNITY_CATALOG_URL -> "RNMDStore Netlify"
                    else -> "Freedom Checker"
                }
                failures += "$sourceName: ${error.message.orEmpty().take(150)}"
            }
        }
        throw IOException("All proxy catalog sources failed. " + failures.joinToString("; "))
    }

    private fun parse(raw: String, fetchedAt: Long): FreedomCheckerSnapshot {
        val root: Any = when (raw.trimStart().firstOrNull()) {
            '[' -> JSONArray(raw)
            '{' -> JSONObject(raw)
            else -> throw IOException("The catalog response is not JSON.")
        }
        val records = LinkedHashMap<String, FreedomCheckerProxy>()
        collect(root, records)

        val generatedAt = if (root is JSONObject) {
            firstValue(root, "generated_at", "generatedAt", "updated_at", "updatedAt", "created_at")
        } else null

        return FreedomCheckerSnapshot(
            entries = records.values.toList().sortedWith(
                compareBy<FreedomCheckerProxy> { !it.supported }
                    .thenByDescending { it.communityUniqueReporters >= 3 }
                    .thenByDescending { if (it.communityUniqueReporters >= 3) it.communitySuccessRate ?: -1.0 else -1.0 }
                    .thenBy { if (it.communityUniqueReporters >= 3) it.communityMedianRttMs ?: Long.MAX_VALUE else Long.MAX_VALUE }
                    .thenBy { !it.serverVerified }
                    .thenBy { it.serverRttMs ?: Long.MAX_VALUE }
                    .thenBy { it.typeLabel }
                    .thenBy { it.endpoint.lowercase() }
            ),
            fetchedAtEpochMs = fetchedAt,
            generatedAt = generatedAt,
        )
    }

    private fun collect(value: Any?, records: LinkedHashMap<String, FreedomCheckerProxy>) {
        when (value) {
            is JSONObject -> {
                parseRecord(value)?.let { records[it.id] = it }
                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    collect(value.opt(key), records)
                }
            }
            is JSONArray -> {
                for (index in 0 until value.length()) collect(value.opt(index), records)
            }
        }
    }

    private fun parseRecord(obj: JSONObject): FreedomCheckerProxy? {
                                                                                      
        if (obj.has("enabled") && !obj.optBoolean("enabled", true)) return null
        if (obj.optBoolean("stale", false)) return null
        val probeStatus = firstValue(obj, "probe_status", "probeStatus")
        if (obj.has("probe_status") && !probeStatus.equals("working", ignoreCase = true)) return null

        val serverVerified = probeStatus.equals("working", ignoreCase = true)
        val serverRttMs = firstValue(obj, "rtt_ms", "latency_ms", "rtt")?.toDoubleOrNull()
            ?.takeIf { it >= 0.0 }?.toLong()

        val lastChecked = firstValue(
            obj, "last_check", "last_checked", "lastChecked", "last_checked_at",
            "lastCheckedAt", "checked_at", "checkedAt", "updated_at"
        ).orEmpty()

        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val link = obj.opt(key) as? String ?: continue
            val telegramLink = link.startsWith("tg://", ignoreCase = true) ||
                (link.startsWith("https://t.me/", ignoreCase = true) && runCatching {
                    val candidate = Uri.parse(link)
                    val hasEndpoint = !candidate.getQueryParameter("server").isNullOrBlank() &&
                        !candidate.getQueryParameter("port").isNullOrBlank()
                    hasEndpoint || candidate.path.orEmpty().contains("/proxy", true) ||
                        candidate.path.orEmpty().contains("/socks", true)
                }.getOrDefault(false))
            if (telegramLink) {
                parseTelegramLink(
                    link,
                    lastChecked,
                    firstValue(obj, "id", "proxy_id", "proxyId")
                        ?.takeIf { it.matches(Regex("^[0-9a-f]{20}$")) },
                )?.let { return it }
            }
        }

        val hint = firstValue(obj, "type", "proxy_type", "proxyType", "kind", "protocol", "scheme")
            ?.lowercase().orEmpty()
        val host = firstValue(obj, "server", "host", "ip", "address", "hostname", "domain")
            ?: return null
        val port = firstValue(obj, "port", "server_port", "serverPort")?.toIntOrNull()
            ?: return null
        val secret = firstValue(obj, "secret", "mtproto_secret", "mtprotoSecret", "hash").orEmpty()
        val username = firstValue(obj, "username", "user", "login").orEmpty()
        val password = firstValue(obj, "password", "pass").orEmpty()

        val isWeb = hint.contains("web") || secret.startsWith("ee", ignoreCase = true)
        val isSocks = hint.contains("socks")
        val isMtProto = hint.contains("mt") || hint.contains("telegram") || secret.isNotBlank()

        if (!isWeb && !isSocks && !isMtProto) return null

        val typeLabel = when {
            isWeb -> "WEB"
            isSocks -> "SOCKS5"
            else -> "MTProto"
        }
        val profile = when {
            isSocks -> ProxyProfile(
                id = stableId("SOCKS5", host, port, username, password),
                name = "TGU Catalog SOCKS5 · ${host.take(24)}",
                type = ProxyType.SOCKS5,
                host = host,
                port = port,
                username = username,
                password = password,
            )
            isMtProto -> ProxyProfile(
                id = stableId("MTPROTO", host, port, secret),
                name = "TGU Catalog MTProto · ${host.take(24)}",
                type = ProxyType.MTPROTO,
                host = host,
                port = port,
                secret = secret,
            )
            else -> null
        }

        val validation = profile?.validationError()
        val supported = profile != null &&
            validation == null &&
            (profile.type != ProxyType.MTPROTO || UpstreamProxyConnector.supportsMtProtoProfile(profile))
        val reason = when {
            profile == null -> "The source entry does not include a connection profile."
            validation != null -> validation
            profile.type == ProxyType.MTPROTO && !supported ->
                "WEB/FakeTLS MTProto is not supported by the current TGU engine."
            else -> null
        }

        return FreedomCheckerProxy(
            id = profile?.id ?: stableId(typeLabel, host, port),
            typeLabel = typeLabel,
            endpoint = "$host:$port",
            lastChecked = lastChecked,
            profile = profile,
            supported = supported,
            unsupportedReason = reason,
            serverVerified = serverVerified,
            serverRttMs = serverRttMs,
            stale = false,
            directoryId = firstValue(obj, "id", "proxy_id", "proxyId")
                ?.takeIf { it.matches(Regex("^[0-9a-f]{20}$")) },
            communityReports = firstValue(obj, "community_reports")?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
            communityUniqueReporters = firstValue(obj, "community_unique_reporters")?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
            communitySuccessRate = firstValue(obj, "community_success_rate")?.toDoubleOrNull()
                ?.takeIf { it.isFinite() && it in 0.0..100.0 },
            communityMedianRttMs = firstValue(obj, "community_median_rtt_ms")?.toLongOrNull()
                ?.takeIf { it >= 0L },
        )
    }

    private fun parseTelegramLink(
        raw: String,
        lastChecked: String,
        directoryId: String? = null,
    ): FreedomCheckerProxy? {
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return null
        val authority = (uri.host ?: uri.authority.orEmpty()).lowercase()
        val path = uri.path.orEmpty().lowercase()
        val host = uri.getQueryParameter("server") ?: return null
        val port = uri.getQueryParameter("port")?.toIntOrNull() ?: return null
        val secret = uri.getQueryParameter("secret").orEmpty()
        val isMtProto = authority == "proxy" || path.contains("proxy") || secret.isNotBlank()
        val isSocks = authority == "socks" || path.contains("socks") || (!isMtProto && secret.isBlank())
        if (!isSocks && !isMtProto) return null
        val username = uri.getQueryParameter("user").orEmpty()
        val password = uri.getQueryParameter("pass").orEmpty()
        val isWeb = isMtProto && secret.startsWith("ee", ignoreCase = true)
        val typeLabel = when {
            isWeb -> "WEB"
            isSocks -> "SOCKS5"
            else -> "MTProto"
        }

        val profile = when {
            isSocks -> ProxyProfile(
                id = stableId("SOCKS5", host, port, username, password),
                name = "TGU Catalog SOCKS5 · ${host.take(24)}",
                type = ProxyType.SOCKS5,
                host = host,
                port = port,
                username = username,
                password = password,
            )
            else -> ProxyProfile(
                id = stableId("MTPROTO", host, port, secret),
                name = "TGU Catalog MTProto · ${host.take(24)}",
                type = ProxyType.MTPROTO,
                host = host,
                port = port,
                secret = secret,
            )
        }

        val validation = profile.validationError()
        val supported = validation == null &&
            (profile.type != ProxyType.MTPROTO || UpstreamProxyConnector.supportsMtProtoProfile(profile))
        val reason = when {
            validation != null -> validation
            isWeb || (profile.type == ProxyType.MTPROTO && !supported) ->
                "WEB/FakeTLS MTProto is not supported by the current TGU engine."
            else -> null
        }

        return FreedomCheckerProxy(
            id = profile.id,
            typeLabel = typeLabel,
            endpoint = "$host:$port",
            lastChecked = lastChecked,
            profile = profile,
            supported = supported && !isWeb,
            unsupportedReason = reason,
            directoryId = directoryId,
        )
    }

    private fun firstValue(obj: JSONObject, vararg names: String): String? {
        for (name in names) {
            if (!obj.has(name) || obj.isNull(name)) continue
            val value = obj.opt(name)
            val text = when (value) {
                is String -> value
                is Number, is Boolean -> value.toString()
                else -> null
            }?.trim()
            if (!text.isNullOrBlank()) return text
        }
        return null
    }

    private fun stableId(vararg parts: Any): String {
        val data = parts.joinToString("|").toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        return "fc-" + digest.take(12).joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val SNAPSHOT_URL = "https://raw.githubusercontent.com/sergeyrenamed-coder/TGU-Proxy-Directory/main/data/proxies.json"
        private const val FALLBACK_SNAPSHOT_URL = "https://freedomchecker.ateo.digital/api/snapshot.json"
        private val COMMUNITY_CATALOG_URL = BuildConfig.TGU_COMMUNITY_API_BASE_URL
            .trim().trimEnd('/')
            .takeIf { it.startsWith("https://", ignoreCase = true) }
            ?.let { "$it/api/tgu/catalog" }
        private val CATALOG_URLS = buildList {
            COMMUNITY_CATALOG_URL?.let(::add)
            add(SNAPSHOT_URL)
            add(FALLBACK_SNAPSHOT_URL)
        }
        private fun allowedSourceHosts(): Set<String> =
            CATALOG_URLS.mapNotNull { runCatching { Uri.parse(it).host }.getOrNull() }.toSet()
        private const val PREFS = "tgu_freedom_checker_catalog"
        private const val KEY_JSON = "snapshot_json"
        private const val KEY_FETCHED_AT = "snapshot_fetched_at"
        private val CLIENT = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }
}

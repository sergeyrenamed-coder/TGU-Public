package com.rnmd.tgu.proxy

import java.net.Inet6Address
import java.net.InetAddress
import java.security.SecureRandom

/**
 * Proxy configuration. Matches the Python ProxyConfig.
 */
data class ProxyConfig(
    val host: String = "127.0.0.1",
    val port: Int = 1443,
    val secret: String = generateSecret(),
    val dcRedirects: Map<Int, String> = MtProtoConstants.DEFAULT_DC_REDIRECTS,
    val bufferSize: Int = 256 * 1024,
    val poolSize: Int = 4,
    val cfProxyEnabled: Boolean = true,
    val cfProxyPriority: Boolean = true,
    val cfProxyFirst: Boolean = false,
    val autoOptimizeConnection: Boolean = true,
    val keepCpuAwake: Boolean = false,
    val preconnectWebSockets: Boolean = false,
    val routeProbesEnabled: Boolean = false,
    val cfDomainRefreshEnabled: Boolean = false,
    val webSocketPingIntervalSeconds: Int = 180,
    val showTrafficInNotification: Boolean = true,
    val cfProxyUserDomain: String = "",
    /** Optional external Telegram-only upstream selected by the user. */
    val upstreamProfile: ProxyProfile? = null,
    val showDetailedStats: Boolean = false,
    val appTheme: String = "system",
    val dynamicColor: Boolean = false,
) {
    /**
     * Get the secret as raw bytes (16 bytes from 32 hex chars).
     */
    fun secretBytes(): ByteArray {
        require(secret.matches(Regex("^[0-9a-fA-F]{32}$"))) {
            "Secret must contain exactly 32 hexadecimal characters"
        }
        return secret.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    /**
     * Get all available CF proxy domains for fallback rotation.
     */
    fun getAllCfDomains(): List<String> {
        if (!cfProxyEnabled) return emptyList()
        if (cfProxyUserDomain.isNotBlank()) return listOf(cfProxyUserDomain)
        return CfProxyDomains.getDomains()
    }

    /**
     * Format DC redirects as text for settings display.
     */
    fun dcRedirectsText(): String {
        return dcRedirects.entries
            .sortedBy { it.key }
            .joinToString("\n") { "${it.key}:${it.value}" }
    }

    companion object {
        fun generateSecret(): String {
            val bytes = ByteArray(16)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }

        /**
         * Parse DC redirects from text format (one "DC:IP" per line).
         */
        fun parseDcRedirects(text: String): Map<Int, String> {
            val result = mutableMapOf<Int, String>()
            for (line in text.lines()) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                val parts = trimmed.split(":", limit = 2)
                if (parts.size == 2) {
                    val dc = parts[0].trim().toIntOrNull() ?: continue
                    val ip = parts[1].trim()
                    if (ip.isNotEmpty()) {
                        result[dc] = ip
                    }
                }
            }
            return result
        }

        fun parseDcRedirectsStrict(text: String): Map<Int, String>? {
            val result = mutableMapOf<Int, String>()
            for (line in text.lines()) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                val parts = trimmed.split(":", limit = 2)
                val dc = parts.getOrNull(0)?.trim()?.toIntOrNull()
                val address = parts.getOrNull(1)?.trim().orEmpty()
                if (dc !in setOf(1, 2, 3, 4, 5, 203) || !isValidAddress(address)) return null
                result[dc!!] = address.lowercase()
            }
            return result
        }

        fun isValidAddress(value: String): Boolean {
            val address = value.trim()
            if (address.isEmpty()) return false
            if (address.contains(':')) {
                return try {
                    InetAddress.getByName(address) is Inet6Address
                } catch (_: Exception) {
                    false
                }
            }
            val ipv4 = address.split('.')
            if (ipv4.size == 4 && ipv4.all { part ->
                    part.isNotEmpty() && part.length <= 3 && part.all(Char::isDigit) &&
                            (part.toIntOrNull() ?: -1) in 0..255
                }) {
                return true
            }
            return address == "localhost" || CfProxyDomains.isValidDomain(address.lowercase())
        }
    }
}

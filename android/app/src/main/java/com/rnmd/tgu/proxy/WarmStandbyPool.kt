package com.rnmd.tgu.proxy

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * In-memory warm standbys, bounded to five deviceside-verified candidates.
 * No stored verdict is trusted for a future network: re-probe before use.
 * The scan runs as a child of the foreground-service monitor and is cancellable.
 */
class WarmStandbyPool(
    private val probe: (ProxyProfile, Int, Int) -> ProxyTestResult =
        { profile, connectMs, readMs -> ProxyDiagnostics.check(profile, connectMs, readMs) },
    private val now: () -> Long = System::currentTimeMillis,
) {
    private data class Warm(val entry: FreedomCheckerProxy, val latencyMs: Long, val epochMs: Long)
    private val available = mutableListOf<Warm>()

    @Synchronized
    fun size(): Int {
        trim()
        return available.size
    }

    @Synchronized
    private fun trim() {
        val time = now()
        available.removeAll { time < it.epochMs || time - it.epochMs > TTL_MS }
    }

    @Synchronized
    private fun alreadyKnown(id: String): Boolean {
        trim()
        return available.any { it.entry.id == id }
    }

    @Synchronized
    private fun remember(entry: FreedomCheckerProxy, latency: Long) {
        trim()
        available.removeAll { it.entry.id == entry.id }
        available += Warm(entry, latency, now())
        available.sortBy { it.latencyMs }
        while (available.size > MAX_STANDBYS) available.removeAt(available.lastIndex)
    }

    @Synchronized
    private fun forget(id: String) {
        available.removeAll { it.entry.id == id }
    }

    suspend fun scan(entries: List<FreedomCheckerProxy>, activeId: String) {
        val ordered = entries.filter {
            it.supported && it.profile != null && !it.stale && it.id != activeId
        }.sortedWith(
            compareByDescending<FreedomCheckerProxy> { it.ruVerified }
                .thenBy { it.ruLatencyMs ?: it.serverRttMs ?: Long.MAX_VALUE }
        )
        for (entry in ordered) {
            currentCoroutineContext().ensureActive()
            if (size() >= MAX_STANDBYS) break
            if (alreadyKnown(entry.id)) continue
            val profile = entry.profile ?: continue
            val first = check(profile)
            if (first?.success == true) {
                val second = check(profile)
                if (second?.success == true) {
                    remember(entry, (first.elapsedMs + second.elapsedMs) / 2)
                }
            }
            // One server at a time: avoid saturating cellular connections
            // while the user is actively using Telegram.
            delay(SCAN_GAP_MS)
        }
    }

    /** Returns a reserve only if it *again* passes two local Telegram checks. */
    suspend fun findValidBackup(excluded: Set<String>): FreedomCheckerProxy? {
        val snapshot = synchronized(this) {
            trim()
            available.filter { it.entry.id !in excluded }.sortedBy { it.latencyMs }.toList()
        }
        for (warm in snapshot) {
            val profile = warm.entry.profile ?: continue
            val a = check(profile)
            val b = if (a?.success == true) check(profile) else null
            if (a?.success == true && b?.success == true) return warm.entry
            forget(warm.entry.id)
        }
        return null
    }

    private suspend fun check(profile: ProxyProfile): ProxyTestResult? =
        try {
            withContext(Dispatchers.IO) { probe(profile, CONNECT_MS, READ_MS) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }

    private companion object {
        const val MAX_STANDBYS = 5
        const val TTL_MS = 10 * 60_000L
        const val SCAN_GAP_MS = 1_800L
        const val CONNECT_MS = 3_000
        const val READ_MS = 3_500
    }
}

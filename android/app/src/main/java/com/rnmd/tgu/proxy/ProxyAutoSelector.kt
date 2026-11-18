package com.rnmd.tgu.proxy

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.UUID

data class ProxyAutoSelectionProgress(
    val isRunning: Boolean = false,
    val phase: String = "Автоподбор ещё не запускался",
    val total: Int = 0,
    val completed: Int = 0,
    val available: Int = 0,
    val currentEndpoint: String? = null,
    val bestEndpoint: String? = null,
    val bestLatencyMs: Long? = null,
    val message: String? = null,
)

object ProxyAutoSelectionRuntime {
    private val mutableState = MutableStateFlow(ProxyAutoSelectionProgress())
    val state: StateFlow<ProxyAutoSelectionProgress> = mutableState

    @Synchronized
    internal fun begin(total: Int) {
        mutableState.value = ProxyAutoSelectionProgress(
            isRunning = true,
            phase = "Проверяю серверы по текущей сети телефона",
            total = total,
            message = null,
        )
    }

    @Synchronized
    internal fun candidateStarted(endpoint: String) {
        mutableState.value = mutableState.value.copy(currentEndpoint = endpoint)
    }

    @Synchronized
    internal fun candidateFinished(
        success: Boolean,
        bestEndpoint: String?,
        bestLatencyMs: Long?,
    ) {
        val state = mutableState.value
        mutableState.value = state.copy(
            completed = state.completed + 1,
            available = state.available + if (success) 1 else 0,
            bestEndpoint = bestEndpoint ?: state.bestEndpoint,
            bestLatencyMs = bestLatencyMs ?: state.bestLatencyMs,
            phase = "Ищем лучшее соединение",
        )
    }

    @Synchronized
    internal fun beginVerification(extraChecks: Int) {
        val state = mutableState.value
        mutableState.value = state.copy(
            phase = "Повторно проверяю самые быстрые серверы",
            total = state.completed + extraChecks,
            currentEndpoint = null,
        )
    }

    @Synchronized
    internal fun verificationFinished(endpoint: String) {
        val state = mutableState.value
        mutableState.value = state.copy(
            completed = state.completed + 1,
            currentEndpoint = endpoint,
        )
    }

    @Synchronized
    internal fun complete(
        endpoint: String?,
        latencyMs: Long?,
        message: String,
    ) {
        val state = mutableState.value
        mutableState.value = state.copy(
            isRunning = false,
            phase = if (endpoint != null) "Лучший сервер найден" else "Подходящий сервер не найден",
            bestEndpoint = endpoint,
            bestLatencyMs = latencyMs,
            currentEndpoint = null,
            message = message,
        )
    }

    @Synchronized
    internal fun cancelled() {
        mutableState.value = mutableState.value.copy(
            isRunning = false,
            phase = "Автоподбор прерван",
            currentEndpoint = null,
        )
    }
}

data class ProxyAutoSelectionResult(
    val selected: FreedomCheckerProxy?,
    val totalCandidates: Int,
    val firstPassAvailable: Int,
    val verifiedLatencyMs: Long?,
    val elapsedMs: Long,
    val message: String,
)

private data class CandidateProbe(
    val entry: FreedomCheckerProxy,
    val result: ProxyTestResult,
)

private data class VerifiedCandidate(
    val entry: FreedomCheckerProxy,
    val medianLatencyMs: Long,
    val successfulChecks: Int,
)

   
                                                                                 
                                                                              
                                                                               
   
class ProxyAutoSelector(
    private val probe: (ProxyProfile, Int, Int) -> ProxyTestResult = { profile, connectTimeoutMs, readTimeoutMs ->
        ProxyDiagnostics.check(profile, connectTimeoutMs, readTimeoutMs)
    },
    private val onScanCompleted: (String, List<CommunityProxyProbeReport>) -> Unit = { _, _ -> },
) {
    suspend fun findBest(entries: List<FreedomCheckerProxy>): ProxyAutoSelectionResult {
        // The public API already returns 24h aggregate statistics. Use them only
        // to choose what to test first: no remote report is proof of reachability.
        val preferred = entries.asSequence()
            .filter {
                it.supported && it.profile != null && !it.stale &&
                    it.communityUniqueReporters >= MIN_COMMUNITY_REPORTERS &&
                    it.communityReports >= MIN_COMMUNITY_REPORTS &&
                    (it.communitySuccessRate ?: 0.0) >= MIN_COMMUNITY_SUCCESS_RATE
            }
            .sortedWith(
                compareByDescending<FreedomCheckerProxy> { it.communitySuccessRate ?: 0.0 }
                    .thenByDescending { it.communityUniqueReporters }
                    .thenBy { it.communityMedianRttMs ?: Long.MAX_VALUE }
                    .thenBy { it.serverRttMs ?: Long.MAX_VALUE }
            )
            .take(COMMUNITY_FAST_CANDIDATES)
            .toList()

        if (preferred.isNotEmpty()) {
            // Do not publish failed quick-pass reports when a fallback full
            // scan will repeat them. The completed scan reports once.
            val fast = findBestInternal(preferred, publishFailureReports = false)
            if (fast.selected != null) {
                val totalSupported = entries.count { it.supported && it.profile != null && !it.stale }
                val message = "Community Network: проверено ${fast.totalCandidates} из $totalSupported кандидатов; маршрут подтверждён локально."
                ProxyAutoSelectionRuntime.complete(fast.selected.endpoint, fast.verifiedLatencyMs, message)
                return fast.copy(message = message)
            }
        }
        // Missing or unreliable community data must never block the usual scan.
        return findBestInternal(entries, publishFailureReports = true)
    }

    private suspend fun findBestInternal(
        entries: List<FreedomCheckerProxy>,
        publishFailureReports: Boolean,
    ): ProxyAutoSelectionResult {
        val startedAt = System.nanoTime()
        val scanId = UUID.randomUUID().toString()
        val communityReports = ConcurrentLinkedQueue<CommunityProxyProbeReport>()
        fun publishReports() {
            val completedReports = communityReports.toList()
            if (completedReports.isNotEmpty()) {
                runCatching { onScanCompleted(scanId, completedReports) }
            }
        }
        val candidates = entries
            .filter { it.supported && it.profile != null && !it.stale }
            .sortedWith(
                compareByDescending<FreedomCheckerProxy> { it.serverVerified }
                    .thenBy { it.serverRttMs ?: Long.MAX_VALUE }
            )
        if (candidates.isEmpty()) {
            ProxyAutoSelectionRuntime.begin(0)
            val message = "В каталоге нет поддерживаемых MTProto/SOCKS5 профилей."
            ProxyAutoSelectionRuntime.complete(null, null, message)
            return ProxyAutoSelectionResult(null, 0, 0, null, elapsed(startedAt), message)
        }

        ProxyAutoSelectionRuntime.begin(candidates.size)
        val completed = AtomicInteger(0)
        val available = AtomicInteger(0)
        val successes = ConcurrentLinkedQueue<CandidateProbe>()
        val semaphore = Semaphore(MAX_PARALLEL)

        try {
            coroutineScope {
                candidates.map { entry ->
                    async {
                        semaphore.withPermit {
                            val profile = entry.profile ?: return@withPermit
                            ProxyAutoSelectionRuntime.candidateStarted(entry.endpoint)
                            val result = try {
                                withContext(Dispatchers.IO) {
                                    probe(profile, SWEEP_CONNECT_TIMEOUT_MS, SWEEP_READ_TIMEOUT_MS)
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                ProxyTestResult(
                                    success = false,
                                    title = "Probe failed",
                                    detail = error.javaClass.simpleName,
                                    elapsedMs = 0L,
                                )
                            }

                            entry.directoryId?.let { directoryId ->
                                val proxyType = if (profile.type == ProxyType.MTPROTO) "MTProto" else "SOCKS5"
                                val failureCode = communityFailureCode(result)
                                val outcome = when {
                                    result.success -> "working"
                                    failureCode == "timeout" -> "timeout"
                                    failureCode == "handshake" || failureCode == "protocol" -> "protocol_error"
                                    else -> "failed"
                                }
                                communityReports.add(
                                    CommunityProxyProbeReport(
                                        proxyId = directoryId,
                                        proxyType = proxyType,
                                        outcome = outcome,
                                        latencyMs = result.elapsedMs.takeIf { it in 1L..60_000L },
                                        failureCode = if (result.success) "none" else failureCode,
                                    )
                                )
                            }
                            if (result.success) {
                                successes.add(CandidateProbe(entry, result))
                                available.incrementAndGet()
                            }
                            val done = completed.incrementAndGet()
                            val best = successes.minByOrNull { it.result.elapsedMs }
                            ProxyAutoSelectionRuntime.candidateFinished(
                                success = result.success,
                                bestEndpoint = best?.entry?.endpoint,
                                bestLatencyMs = best?.result?.elapsedMs,
                            )
                        }
                    }
                }.awaitAll()
            }

                                                                                    
                                                                                    
            val firstPass = successes.sortedBy { it.result.elapsedMs }
            if (firstPass.isEmpty()) {
                if (publishFailureReports) publishReports()
                val message = "Доступных прокси не найдено. Проверь интернет или попробуй другую сеть."
                ProxyAutoSelectionRuntime.complete(null, null, message)
                return ProxyAutoSelectionResult(
                    null, candidates.size, 0, null, elapsed(startedAt), message
                )
            }

            val finalists = firstPass.take(FINALISTS_COUNT)
            ProxyAutoSelectionRuntime.beginVerification(finalists.size * VERIFY_REPEATS)

            val verified = coroutineScope {
                finalists.map { candidate ->
                    async(Dispatchers.IO) {
                        val timings = mutableListOf(candidate.result.elapsedMs)
                        repeat(VERIFY_REPEATS) {
                            val profile = candidate.entry.profile!!
                            val result = try {
                                probe(profile, VERIFY_CONNECT_TIMEOUT_MS, VERIFY_READ_TIMEOUT_MS)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                null
                            }
                            if (result?.success == true) timings += result.elapsedMs
                            ProxyAutoSelectionRuntime.verificationFinished(candidate.entry.endpoint)
                        }
                        if (timings.size >= MIN_SUCCESSFUL_CHECKS) {
                            VerifiedCandidate(
                                candidate.entry,
                                median(timings),
                                timings.size,
                            )
                        } else null
                    }
                }.awaitAll().filterNotNull()
            }

            val winner = verified.minWithOrNull(
                compareBy<VerifiedCandidate> { it.medianLatencyMs }
                    .thenByDescending { it.successfulChecks }
            )
            val elapsedMs = elapsed(startedAt)
            if (winner == null) {
                if (publishFailureReports) publishReports()
                val message = "Прокси отвечали нестабильно. Не применяю сервер, который не прошёл повторную проверку."
                ProxyAutoSelectionRuntime.complete(null, null, message)
                return ProxyAutoSelectionResult(
                    null, candidates.size, available.get(), null, elapsedMs, message
                )
            }

            publishReports()
            val message = "Проверено ${candidates.size}; доступно ${available.get()}; лучший сервер подтвердил ${winner.successfulChecks}/3 проверок."
            ProxyAutoSelectionRuntime.complete(
                endpoint = winner.entry.endpoint,
                latencyMs = winner.medianLatencyMs,
                message = message,
            )
            return ProxyAutoSelectionResult(
                selected = winner.entry,
                totalCandidates = candidates.size,
                firstPassAvailable = available.get(),
                verifiedLatencyMs = winner.medianLatencyMs,
                elapsedMs = elapsedMs,
                message = message,
            )
        } catch (cancelled: CancellationException) {
            ProxyAutoSelectionRuntime.cancelled()
            throw cancelled
        } catch (error: Exception) {
            val message = "Автоподбор завершился ошибкой: ${error.message ?: error.javaClass.simpleName}"
            ProxyAutoSelectionRuntime.complete(null, null, message)
            throw error
        }
    }

    private fun communityFailureCode(result: ProxyTestResult): String {
        if (result.success) return "none"
        val detail = (result.detail + " " + result.title).lowercase()
        return when {
            "timeout" in detail || "timed out" in detail || "read timed" in detail -> "timeout"
            "respq" in detail || "handshake" in detail || "secret" in detail -> "handshake"
            "constructor" in detail || "protocol" in detail || "frame length" in detail -> "protocol"
            "connect" in detail || "refused" in detail || "unreachable" in detail -> "connect"
            else -> "unknown"
        }
    }

    private fun median(values: List<Long>): Long {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle]
        else (sorted[middle - 1] + sorted[middle]) / 2L
    }

    private fun elapsed(startedAtNanos: Long): Long =
        ((System.nanoTime() - startedAtNanos) / 1_000_000L).coerceAtLeast(0L)

    private companion object {
        const val COMMUNITY_FAST_CANDIDATES = 20
        const val MIN_COMMUNITY_REPORTERS = 3
        const val MIN_COMMUNITY_REPORTS = 5
        const val MIN_COMMUNITY_SUCCESS_RATE = 80.0
        const val MAX_PARALLEL = 24
        const val FINALISTS_COUNT = 5
        const val VERIFY_REPEATS = 2
        const val MIN_SUCCESSFUL_CHECKS = 2
        const val SWEEP_CONNECT_TIMEOUT_MS = 3_000
        const val SWEEP_READ_TIMEOUT_MS = 3_500
        const val VERIFY_CONNECT_TIMEOUT_MS = 3_500
        const val VERIFY_READ_TIMEOUT_MS = 4_500
    }
}

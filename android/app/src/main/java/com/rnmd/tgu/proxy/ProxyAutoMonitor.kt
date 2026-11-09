package com.rnmd.tgu.proxy

import android.content.Context
import android.util.Log
import com.rnmd.tgu.diagnostics.DiagnosticLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Runs only while the user-started foreground proxy service is alive.
 * Source checks and local health checks are deliberately independent.
 */
class ProxyAutoMonitor(
    context: Context,
    private val onProfileSelected: (ProxyProfile) -> Unit,
) {
    private val appContext = context.applicationContext
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        job?.cancel()
        job = scope.launch(Dispatchers.IO) {
            val repository = FreedomCheckerCatalogRepository(appContext)
            val profileStore = ProxyProfileStore(appContext)
            val upstreamStore = UpstreamProxySettingsStore(appContext)
            var lastCatalogRefreshAt = 0L
            var activeProfileId: String? = null
            var failureStreak = 0
            var switchCount = 0
            var nextSwitchAttemptAt = 0L
            var nextInitialSelectionAt = 0L
            val failedProfileUntil = mutableMapOf<String, Long>()

            while (isActive) {
                val settings = AutoFailoverSettingsStore(appContext).load()
                val now = System.currentTimeMillis()
                failedProfileUntil.entries.removeAll { it.value <= now }

                if (!settings.enabled) {
                    val current = runCatching { upstreamStore.selectedProfile() }.getOrNull()
                    ProxyMonitorRuntime.publish(
                        ProxyMonitorSnapshot(
                            enabled = false,
                            status = "Автопереключение выключено",
                            activeProfileId = current?.id,
                            activeName = current?.name,
                            activeType = current?.type?.name,
                            activeEndpoint = current?.let { "${it.host}:${it.port}" },
                            switchCount = switchCount,
                        )
                    )
                    delay(5_000)
                    continue
                }

                val refreshIntervalMs = settings.catalogRefreshMinutes * 60_000L
                if (lastCatalogRefreshAt == 0L || now - lastCatalogRefreshAt >= refreshIntervalMs) {
                    // On failure the old encrypted profiles and cached JSON remain untouched.
                    lastCatalogRefreshAt = now
                    try {
                        repository.refresh()
                        Log.i(TAG, "Freedom Checker catalog refreshed.")
                    } catch (e: Exception) {
                        Log.w(TAG, "Catalog refresh failed; keeping cached data: ${e.javaClass.simpleName}")
                    }
                }

                val current = try {
                    upstreamStore.selectedProfile()
                } catch (e: Exception) {
                    ProxyMonitorRuntime.publish(
                        ProxyMonitorSnapshot(
                            enabled = true,
                            status = "Ошибка зашифрованного хранилища: ${e.javaClass.simpleName}",
                            activeProfileId = activeProfileId,
                            failureStreak = failureStreak,
                            switchCount = switchCount,
                        )
                    )
                    delay(settings.checkIntervalSeconds * 1_000L)
                    continue
                }

                if (current?.id != activeProfileId) {
                    activeProfileId = current?.id
                    failureStreak = 0
                }

                if (current == null) {
                    val candidate = if (now >= nextInitialSelectionAt) {
                        nextInitialSelectionAt = now + INITIAL_RETRY_DELAY_MS
                        findHealthyCandidate(repository, null, failedProfileUntil.keys)
                    } else null
                    val profile = candidate?.profile
                    if (profile != null) {
                        try {
                            persistAndActivate(profileStore, upstreamStore, profile)
                            onProfileSelected(profile)
                            activeProfileId = profile.id
                            switchCount++
                            ProxyMonitorRuntime.publish(
                                ProxyMonitorSnapshot(
                                    enabled = true,
                                    status = "Подобран и проверен первый upstream",
                                    activeProfileId = profile.id,
                                    activeName = profile.name,
                                    activeType = profile.type.name,
                                    activeEndpoint = "${profile.host}:${profile.port}",
                                    lastCheckEpochMs = System.currentTimeMillis(),
                                    switchCount = switchCount,
                                )
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "Could not activate catalog proxy: ${e.javaClass.simpleName}")
                            ProxyMonitorRuntime.publish(
                                ProxyMonitorSnapshot(
                                    enabled = true,
                                    status = "Не удалось включить найденный прокси: ${e.javaClass.simpleName}",
                                    switchCount = switchCount,
                                )
                            )
                        }
                    } else {
                        ProxyMonitorRuntime.publish(
                            ProxyMonitorSnapshot(
                                enabled = true,
                                status = ProxyAutoSelectionRuntime.state.value.message
                                    ?: "Автоматический поиск сервера повторится через несколько минут",
                                switchCount = switchCount,
                            )
                        )
                    }
                } else {
                    val check = runCatching { ProxyDiagnostics.check(current) }.getOrElse { error ->
                        ProxyTestResult(
                            success = false,
                            title = "Проверка upstream завершилась ошибкой",
                            detail = error.javaClass.simpleName,
                            elapsedMs = 0L,
                        )
                    }

                    if (check.success) failureStreak = 0 else failureStreak++

                    // A healthy route stays pinned. Replacing it only because another
                    // candidate measured slightly faster closes every active Telegram
                    // session and makes the client repeatedly reconnect.
                    val statusText = when {
                        check.success && current.type == ProxyType.MTPROTO ->
                            "MTProto obfuscated2 + req_pq_multi handshake успешен · секрет принят Telegram DC"
                        check.success -> "SOCKS5-туннель и MTProto-ответ Telegram DC подтверждены"
                        else -> "Проверка не прошла: ${check.detail.take(110)}"
                    }
                    ProxyMonitorRuntime.publish(
                        ProxyMonitorSnapshot(
                            enabled = true,
                            status = statusText,
                            activeProfileId = current.id,
                            activeName = current.name,
                            activeType = current.type.name,
                            activeEndpoint = "${current.host}:${current.port}",
                            lastCheckEpochMs = System.currentTimeMillis(),
                            latencyMs = check.elapsedMs,
                            failureStreak = failureStreak,
                            switchCount = switchCount,
                        )
                    )

                    if (ProxyFailoverPolicy.shouldAttemptFailover(
                            settings = settings,
                            failureStreak = failureStreak,
                            nowEpochMs = now,
                            nextAttemptEpochMs = nextSwitchAttemptAt,
                        )
                    ) {
                        nextSwitchAttemptAt = now + 90_000L
                        // Keep a failed route out of candidate selection for a while.
                        // Otherwise intermittent probes can make two flaky proxies
                        // alternate and repeatedly tear down Telegram sessions.
                        failedProfileUntil[current.id] = now + FAILED_PROFILE_COOLDOWN_MS
                        val selection = findBestCandidate(
                            repository = repository,
                            currentId = current.id,
                            excludedProfileIds = failedProfileUntil.keys,
                        )
                        val replacement = selection?.selected?.profile
                        if (replacement != null) {
                            try {
                                persistAndActivate(profileStore, upstreamStore, replacement)
                                onProfileSelected(replacement)
                                activeProfileId = replacement.id
                                failureStreak = 0
                                switchCount++
                                DiagnosticLogger.event(
                                    "upstream_failover_applied",
                                    "failedProfileId" to current.id,
                                    "replacementProfileId" to replacement.id,
                                    "failureStreak" to settings.failureThreshold,
                                )
                                ProxyMonitorRuntime.publish(
                                    ProxyMonitorSnapshot(
                                        enabled = true,
                                        status = "Старый прокси не отвечает · выполнено переключение",
                                        activeProfileId = replacement.id,
                                        activeName = replacement.name,
                                        activeType = replacement.type.name,
                                        activeEndpoint = "${replacement.host}:${replacement.port}",
                                        lastCheckEpochMs = System.currentTimeMillis(),
                                        switchCount = switchCount,
                                    )
                                )
                                Log.w(TAG, "Automatic upstream failover: ${current.host} -> ${replacement.host}")
                            } catch (e: Exception) {
                                Log.e(TAG, "Automatic failover failed: ${e.javaClass.simpleName}")
                                ProxyMonitorRuntime.publish(
                                    ProxyMonitorSnapshot(
                                        enabled = true,
                                        status = "Резерв найден, но переключение не удалось: ${e.javaClass.simpleName}",
                                        activeProfileId = current.id,
                                        activeName = current.name,
                                        activeType = current.type.name,
                                        activeEndpoint = "${current.host}:${current.port}",
                                        failureStreak = failureStreak,
                                        switchCount = switchCount,
                                    )
                                )
                            }
                        } else {
                            ProxyMonitorRuntime.publish(
                                ProxyMonitorSnapshot(
                                    enabled = true,
                                    status = "Активный прокси не отвечает · подходящий резерв пока не найден",
                                    activeProfileId = current.id,
                                    activeName = current.name,
                                    activeType = current.type.name,
                                    activeEndpoint = "${current.host}:${current.port}",
                                    lastCheckEpochMs = System.currentTimeMillis(),
                                    latencyMs = check.elapsedMs,
                                    failureStreak = failureStreak,
                                    switchCount = switchCount,
                                )
                            )
                        }
                    }
                }

                delay(settings.checkIntervalSeconds * 1_000L)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun findHealthyCandidate(
        repository: FreedomCheckerCatalogRepository,
        currentId: String?,
        excludedProfileIds: Set<String> = emptySet(),
    ): FreedomCheckerProxy? = findBestCandidate(repository, currentId, excludedProfileIds)?.selected

    /**
     * Uses the complete device-side probe, not the server's last-known result.
     * The candidate is only applied after the same repeated verification used
     * by the manual selector has succeeded on this phone's current network.
     */
    private suspend fun findBestCandidate(
        repository: FreedomCheckerCatalogRepository,
        currentId: String?,
        excludedProfileIds: Set<String> = emptySet(),
    ): ProxyAutoSelectionResult? {
        val snapshot = repository.cached() ?: try {
            repository.refresh()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "No cached proxy catalog: ${error.javaClass.simpleName}")
            return null
        }
        val candidates = snapshot.entries.filter {
            it.supported && it.profile != null && !it.stale &&
                it.id != currentId && it.id !in excludedProfileIds
        }
        return try {
            ProxyAutoSelector().findBest(candidates)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "Full proxy selection failed: ${error.javaClass.simpleName}")
            null
        }
    }

    private fun persistAndActivate(
        profileStore: ProxyProfileStore,
        upstreamStore: UpstreamProxySettingsStore,
        profile: ProxyProfile,
    ) {
        val existing = profileStore.load()
        if (existing.size >= 50 && existing.none { it.id == profile.id }) {
            val disposable = existing.firstOrNull {
                (it.name.startsWith("TGU Catalog", ignoreCase = true) || it.name.startsWith("Freedom Checker", ignoreCase = true)) &&
                    it.id != upstreamStore.selectedProfileId()
            }
            if (disposable != null) profileStore.delete(disposable.id)
        }
        profileStore.upsert(profile)
        upstreamStore.select(profile.id)
    }

    private companion object {
        const val TAG = "TGU-AutoMonitor"
        const val INITIAL_RETRY_DELAY_MS = 5 * 60_000L
        const val FAILED_PROFILE_COOLDOWN_MS = 10 * 60_000L
    }
}

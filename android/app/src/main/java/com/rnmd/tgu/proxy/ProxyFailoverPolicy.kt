package com.rnmd.tgu.proxy

/**
 * Pure decision rules for automatic upstream replacement.
 *
 * Keeping these checks outside the long-running monitor loop makes the
 * failover thresholds and optimization margin deterministic and testable.
 */
internal object ProxyFailoverPolicy {
    fun isAutoManagedProfile(profile: ProxyProfile): Boolean =
        profile.name.startsWith("TGU Catalog", ignoreCase = true) ||
            profile.name.startsWith("Freedom Checker", ignoreCase = true)

    fun shouldAttemptFailover(
        settings: AutoFailoverSettings,
        failureStreak: Int,
        nowEpochMs: Long,
        nextAttemptEpochMs: Long,
    ): Boolean =
        settings.enabled &&
            failureStreak >= settings.failureThreshold &&
            nowEpochMs >= nextAttemptEpochMs

    /**
     * Avoids churn for tiny/measurement-noise gains. A replacement must be
     * at least 10 ms or 1/6 faster than the current route, whichever is larger.
     */
    fun isMeaningfullyFaster(currentLatencyMs: Long, candidateLatencyMs: Long): Boolean {
        if (currentLatencyMs <= 0L || candidateLatencyMs < 0L) return false
        val requiredGainMs = maxOf(10L, currentLatencyMs / 6L)
        return candidateLatencyMs < currentLatencyMs - requiredGainMs
    }
}

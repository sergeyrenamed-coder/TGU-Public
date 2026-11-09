package com.rnmd.tgu.proxy

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class AutoFailoverSettings(
    val enabled: Boolean = true,
    val checkIntervalSeconds: Int = 60,
    val failureThreshold: Int = 3,
    val catalogRefreshMinutes: Int = 5,
)

class AutoFailoverSettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): AutoFailoverSettings = AutoFailoverSettings(
        enabled = prefs.getBoolean(KEY_ENABLED, true),
        checkIntervalSeconds = prefs.getInt(KEY_INTERVAL, 60).coerceIn(30, 180),
        failureThreshold = prefs.getInt(KEY_THRESHOLD, 3).coerceIn(2, 5),
        catalogRefreshMinutes = prefs.getInt(KEY_REFRESH, 5).coerceIn(2, 30),
    )

    fun save(settings: AutoFailoverSettings) {
        prefs.edit()
            .putBoolean(KEY_ENABLED, settings.enabled)
            .putInt(KEY_INTERVAL, settings.checkIntervalSeconds.coerceIn(30, 180))
            .putInt(KEY_THRESHOLD, settings.failureThreshold.coerceIn(2, 5))
            .putInt(KEY_REFRESH, settings.catalogRefreshMinutes.coerceIn(2, 30))
            .apply()
    }

    private companion object {
        const val PREFS = "tgu_auto_failover"
        const val KEY_ENABLED = "enabled"
        const val KEY_INTERVAL = "check_interval_seconds"
        const val KEY_THRESHOLD = "failure_threshold"
        const val KEY_REFRESH = "catalog_refresh_minutes"
    }
}

data class ProxyMonitorSnapshot(
    val enabled: Boolean = true,
    val status: String = "Ожидание запуска TGU",
    val activeProfileId: String? = null,
    val activeName: String? = null,
    val activeType: String? = null,
    val activeEndpoint: String? = null,
    val lastCheckEpochMs: Long? = null,
    val latencyMs: Long? = null,
    val failureStreak: Int = 0,
    val switchCount: Int = 0,
)

object ProxyMonitorRuntime {
    private val mutableState = MutableStateFlow(ProxyMonitorSnapshot())
    val state: StateFlow<ProxyMonitorSnapshot> = mutableState

    internal fun publish(snapshot: ProxyMonitorSnapshot) {
        mutableState.value = snapshot
    }
}

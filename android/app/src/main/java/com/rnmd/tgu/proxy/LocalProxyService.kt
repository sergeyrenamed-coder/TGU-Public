package com.rnmd.tgu.proxy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.rnmd.tgu.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class LocalProxyStatus { STOPPED, STARTING, RUNNING, STOPPING, ERROR }
data class LocalProxySnapshot(val status: LocalProxyStatus = LocalProxyStatus.STOPPED, val detail: String = "Local proxy is stopped.")

object LocalProxyRuntime {
    private val mutableState = kotlinx.coroutines.flow.MutableStateFlow(LocalProxySnapshot())
    val state: kotlinx.coroutines.flow.StateFlow<LocalProxySnapshot> = mutableState
    internal fun publish(status: LocalProxyStatus, detail: String) { mutableState.value = LocalProxySnapshot(status, detail) }
}

                                                                                
class LocalProxyService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectivity = ConnectivityTracker()
    private lateinit var connectivityManager: ConnectivityManager
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var server: TgWsProxyServer? = null
    private var serverJob: Job? = null
    private var autoMonitor: ProxyAutoMonitor? = null
    @Volatile private var stopping = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        registerNetworkCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                persistRestoreAfterUpdate(false)
                stopProxy()
            }
            ACTION_RECONFIGURE -> reconfigureUpstream()
            else -> {
                persistRestoreAfterUpdate(true)
                startProxy()
            }
        }
        return START_NOT_STICKY
    }

    private fun persistRestoreAfterUpdate(enabled: Boolean) {
        runCatching { LocalProxySettingsStore(this).setRestoreAfterUpdate(enabled) }
            .onFailure { Log.w(TAG, "Could not persist update restore state: ${it.javaClass.simpleName}") }
    }

    private fun startProxy() {
        startAsForeground()
        if (serverJob?.isActive == true) return
        stopping = false
        LocalProxyRuntime.publish(LocalProxyStatus.STARTING, "Starting local MTProto listener…")
        val secret = try { LocalProxySettingsStore(this).getOrCreateSecret() }
        catch (e: Exception) {
            LocalProxyRuntime.publish(LocalProxyStatus.ERROR, "Could not load the encrypted local secret: ${e.javaClass.simpleName}")
            persistRestoreAfterUpdate(false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        val selectedUpstream = try {
            UpstreamProxySettingsStore(this).selectedProfile()
        } catch (e: Exception) {
            LocalProxyRuntime.publish(
                LocalProxyStatus.ERROR,
                "Could not load selected upstream: ${e.javaClass.simpleName}. Clear or reselect it in Proxy Manager."
            )
            persistRestoreAfterUpdate(false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        if (selectedUpstream != null && selectedUpstream.type == ProxyType.MTPROTO &&
            !UpstreamProxyConnector.supportsMtProtoProfile(selectedUpstream)
        ) {
            LocalProxyRuntime.publish(
                LocalProxyStatus.ERROR,
                "Selected MTProto upstream uses WEB/FakeTLS or an unsupported secret. Choose classic MTProto or SOCKS5."
            )
            persistRestoreAfterUpdate(false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        CfProxyDomains.startBackgroundRefresh()
        val engine = TgWsProxyServer(
            config = ProxyConfig(
                host = LocalProxySettingsStore.LOCAL_HOST,
                port = LocalProxySettingsStore.LOCAL_PORT,
                secret = secret,
                cfProxyEnabled = selectedUpstream == null,
                cfProxyPriority = true,
                poolSize = 2,
                upstreamProfile = selectedUpstream,
            ),
            connectivity = connectivity
        )
        engine.onStatusChange = { running ->
            if (server === engine && !stopping) {
                LocalProxyRuntime.publish(
                    if (running) LocalProxyStatus.RUNNING else LocalProxyStatus.STOPPING,
                    if (running) {
                        val route = selectedUpstream?.let { "${it.type} upstream · ${it.host}:${it.port}" }
                            ?: "automatic WebSocket / Cloudflare / TCP routing"
                        "Listening on 127.0.0.1:1443 · $route"
                    } else "Stopping the local listener…"
                )
            }
        }
        server = engine
        autoMonitor?.stop()
        autoMonitor = ProxyAutoMonitor(applicationContext) { profile ->
            val current = server
            if (current != null) {
                current.reconfigureUpstream(
                    current.configSnapshot.copy(
                        upstreamProfile = profile,
                        cfProxyEnabled = false,
                    )
                )
                LocalProxyRuntime.publish(
                    if (current.isRunning) LocalProxyStatus.RUNNING else LocalProxyStatus.STARTING,
                    "Listening on 127.0.0.1:1443 · ${profile.type} upstream · ${profile.host}:${profile.port}"
                )
            }
        }.also { it.start(scope) }
        serverJob = scope.launch {
            try {
                engine.start()
                if (server === engine && !stopping) {
                    LocalProxyRuntime.publish(LocalProxyStatus.ERROR, "The local engine stopped unexpectedly.")
                    stopServiceAfterFailure()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Local MTProto engine failed: ${e.javaClass.simpleName}")
                if (server === engine && !stopping) {
                    LocalProxyRuntime.publish(LocalProxyStatus.ERROR, "Engine startup failed: ${e.javaClass.simpleName}. Check whether port 1443 is already in use.")
                    stopServiceAfterFailure()
                }
            }
        }
    }

    private suspend fun stopServiceAfterFailure() = withContext(Dispatchers.Main) {
        persistRestoreAfterUpdate(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun reconfigureUpstream() {
        val current = server ?: return
        try {
            val selected = UpstreamProxySettingsStore(this).selectedProfile()
            if (selected != null && selected.type == ProxyType.MTPROTO &&
                !UpstreamProxyConnector.supportsMtProtoProfile(selected)
            ) {
                LocalProxyRuntime.publish(
                    LocalProxyStatus.ERROR,
                    "Selected MTProto upstream uses WEB/FakeTLS or an unsupported secret."
                )
                return
            }
            current.reconfigureUpstream(
                current.configSnapshot.copy(
                    upstreamProfile = selected,
                    cfProxyEnabled = selected == null,
                )
            )
            val route = selected?.let { "${it.type} upstream · ${it.host}:${it.port}" }
                ?: "automatic WebSocket / Cloudflare / TCP routing"
            LocalProxyRuntime.publish(
                if (current.isRunning) LocalProxyStatus.RUNNING else LocalProxyStatus.STARTING,
                "Listening on 127.0.0.1:1443 · $route"
            )
            Log.i(TAG, "Local upstream configuration updated: ${selected?.type ?: "AUTO"}")
        } catch (e: Exception) {
            Log.e(TAG, "Could not reload selected upstream: ${e.javaClass.simpleName}")
            LocalProxyRuntime.publish(
                LocalProxyStatus.ERROR,
                "Could not reload selected upstream: ${e.javaClass.simpleName}"
            )
        }
    }

    private fun stopProxy() {
        if (stopping) return
        stopping = true
        autoMonitor?.stop()
        LocalProxyRuntime.publish(LocalProxyStatus.STOPPING, "Stopping local MTProto listener…")
        val current = server
        scope.launch {
            try { current?.stop() }
            finally {
                if (server === current) server = null
                LocalProxyRuntime.publish(LocalProxyStatus.STOPPED, "Local proxy is stopped.")
                withContext(Dispatchers.Main) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    private fun registerNetworkCallback() {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { updateNetwork(network) }
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) { updateNetwork(network, networkCapabilities) }
            override fun onLost(network: Network) { updateCurrentNetwork() }
        }
        networkCallback = callback
        try { connectivityManager.registerDefaultNetworkCallback(callback) }
        catch (e: Exception) { Log.w(TAG, "Network callback unavailable: ${e.javaClass.simpleName}") }
        updateCurrentNetwork()
    }

    private fun updateCurrentNetwork() {
        val active = connectivityManager.activeNetwork
        updateNetwork(active, active?.let(connectivityManager::getNetworkCapabilities))
    }

    private fun updateNetwork(network: Network?, caps: NetworkCapabilities? = network?.let(connectivityManager::getNetworkCapabilities)) {
        val status = when {
            caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> NetworkStatus.OFFLINE
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) -> NetworkStatus.ONLINE
            else -> NetworkStatus.UNVERIFIED
        }
        if (connectivity.updateNetwork(network?.networkHandle, status)) {
            server?.onConnectivityChanged()
        }
    }

    private fun startAsForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(this, 100, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stopIntent = PendingIntent.getService(this, 101, Intent(this, LocalProxyService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("TGU local proxy")
            .setContentText("MTProto listener · 127.0.0.1:1443")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "Stop", stopIntent).build())
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "TGU local proxy", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Status and stop control for the local Telegram proxy."
                setShowBadge(false)
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        stopping = true
        autoMonitor?.stop()
        autoMonitor = null
        ProxyMonitorRuntime.publish(ProxyMonitorSnapshot(enabled = false, status = "TGU остановлен"))
        try { networkCallback?.let(connectivityManager::unregisterNetworkCallback) } catch (_: Exception) { }
        server?.stop()
        server = null
        connectivity.stop()
        CfProxyDomains.stopBackgroundRefresh()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.rnmd.tgu.proxy.action.START"
        const val ACTION_STOP = "com.rnmd.tgu.proxy.action.STOP"
        const val ACTION_RECONFIGURE = "com.rnmd.tgu.proxy.action.RECONFIGURE"
        private const val TAG = "TGU-LocalProxyService"
        private const val CHANNEL_ID = "tgu_local_proxy"
        private const val NOTIFICATION_ID = 6104
    }
}
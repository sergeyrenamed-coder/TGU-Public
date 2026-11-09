package com.rnmd.tgu.diagnostics

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.ConnectException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import java.util.concurrent.Callable
import java.util.concurrent.Executors

data class AddressFamilyResult(
    val addressCount: Int,
    val tcp443Reachable: Boolean,
    val tlsHandshakeSucceeded: Boolean?,
    val tlsProtocol: String?,
    val httpStatusCode: Int?,
    val httpStatusLine: String?,
    val tcpElapsedMs: Long?,
    val tlsElapsedMs: Long?,
    val httpElapsedMs: Long?,
    val elapsedMs: Long,
    val detail: String
)

data class EndpointCheck(
    val host: String,
    val isControl: Boolean,
    val dnsResolved: Boolean,
    val ipv4: AddressFamilyResult?,
    val ipv6: AddressFamilyResult?,
    val tcp443Reachable: Boolean,
    val elapsedMs: Long?,
    val detail: String
) {
    val tlsHandshakeSucceeded: Boolean
        get() = ipv4?.tlsHandshakeSucceeded == true || ipv6?.tlsHandshakeSucceeded == true

    val httpResponseReceived: Boolean
        get() = ipv4?.httpStatusLine != null || ipv6?.httpStatusLine != null
}

data class NetworkReport(
    val checkedAtEpochMs: Long,
    val activeNetworkPresent: Boolean,
    val transport: String,
    val hasInternetCapability: Boolean,
    val isValidated: Boolean,
    val isMetered: Boolean,
    val checks: List<EndpointCheck>,
    val dcChecks: List<DcPortCheck>
) {
    val telegramChecks: List<EndpointCheck>
        get() = checks.filterNot { it.isControl }

    val controlChecks: List<EndpointCheck>
        get() = checks.filter { it.isControl }

    val telegramReachableCount: Int
        get() = telegramChecks.count { it.tcp443Reachable }

    val telegramTlsReachableCount: Int
        get() = telegramChecks.count { it.tlsHandshakeSucceeded }

    val telegramHttpResponseCount: Int
        get() = telegramChecks.count { it.httpResponseReceived }

    val controlReachableCount: Int
        get() = controlChecks.count { it.tcp443Reachable }

    val controlTlsReachableCount: Int
        get() = controlChecks.count { it.tlsHandshakeSucceeded }

    val controlHttpResponseCount: Int
        get() = controlChecks.count { it.httpResponseReceived }

    val allTelegramEndpointsReachable: Boolean
        get() = telegramChecks.isNotEmpty() && telegramReachableCount == telegramChecks.size
}

class NetworkDiagnostics(context: Context) {
    private val appContext = context.applicationContext

    fun inspect(): NetworkReport {
        val manager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = manager.activeNetwork
        val capabilities = activeNetwork?.let(manager::getNetworkCapabilities)
        val checkedAt = System.currentTimeMillis()

        val transport = when {
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "Wi-Fi"
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "Cellular"
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "Ethernet"
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) == true -> "Bluetooth"
            activeNetwork != null -> "Other"
            else -> "Unavailable"
        }

        val endpointExecutor = Executors.newFixedThreadPool(TARGET_HOSTS.size)
        val checks = try {
            endpointExecutor.invokeAll(
                TARGET_HOSTS.map { target ->
                    Callable { checkEndpoint(target.host, target.isControl) }
                }
            ).map { it.get() }
        } finally {
            endpointExecutor.shutdownNow()
        }
        val dcChecks = TelegramDcScanner.scan()

        return NetworkReport(
            checkedAtEpochMs = checkedAt,
            activeNetworkPresent = activeNetwork != null,
            transport = transport,
            hasInternetCapability = capabilities?.hasCapability(
                NetworkCapabilities.NET_CAPABILITY_INTERNET
            ) == true,
            isValidated = capabilities?.hasCapability(
                NetworkCapabilities.NET_CAPABILITY_VALIDATED
            ) == true,
            isMetered = manager.isActiveNetworkMetered,
            checks = checks,
            dcChecks = dcChecks
        )
    }

    private fun checkEndpoint(host: String, isControl: Boolean): EndpointCheck {
        val startedAt = SystemClock.elapsedRealtime()
        val addresses = try {
            InetAddress.getAllByName(host).toList()
        } catch (_: UnknownHostException) {
            return failedDns(host, isControl, startedAt, "DNS lookup failed")
        } catch (_: IOException) {
            return failedDns(host, isControl, startedAt, "DNS or network I/O error")
        } catch (_: SecurityException) {
            return failedDns(host, isControl, startedAt, "DNS lookup blocked by system policy")
        }

        if (addresses.isEmpty()) {
            return failedDns(host, isControl, startedAt, "DNS returned no addresses")
        }

        val ipv4Addresses = addresses.filterIsInstance<Inet4Address>()
        val ipv6Addresses = addresses.filterIsInstance<Inet6Address>()

        // Probe one address per family. Each probe performs TCP, TLS/SNI and one
        // harmless HTTP GET / request over the same connection.
        val ipv4 = ipv4Addresses.firstOrNull()?.let {
            checkHttpsAddress(host, it, ipv4Addresses.size)
        }
        val ipv6 = ipv6Addresses.firstOrNull()?.let {
            checkHttpsAddress(host, it, ipv6Addresses.size)
        }

        val reachable = ipv4?.tcp443Reachable == true || ipv6?.tcp443Reachable == true
        val details = buildList {
            ipv4?.let { add("IPv4: ${it.detail}") }
            ipv6?.let { add("IPv6: ${it.detail}") }
            if (ipv4 == null) add("IPv4: no address returned by DNS")
            if (ipv6 == null) add("IPv6: no address returned by DNS")
        }.joinToString("; ")

        return EndpointCheck(
            host = host,
            isControl = isControl,
            dnsResolved = true,
            ipv4 = ipv4,
            ipv6 = ipv6,
            tcp443Reachable = reachable,
            elapsedMs = SystemClock.elapsedRealtime() - startedAt,
            detail = details
        )
    }

    private fun checkHttpsAddress(
        host: String,
        address: InetAddress,
        addressCount: Int
    ): AddressFamilyResult {
        val startedAt = SystemClock.elapsedRealtime()
        var phase = ProbePhase.TCP_CONNECT
        var phaseStartedAt = startedAt
        var tcpConnected = false
        var tcpElapsedMs: Long? = null
        var tlsElapsedMs: Long? = null
        var httpElapsedMs: Long? = null
        var tlsSucceeded: Boolean? = null
        var tlsProtocol: String? = null
        var httpStatusCode: Int? = null
        var httpStatusLine: String? = null
        var detail = "Probe did not complete"

        try {
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            Socket().use { rawSocket ->
                phase = ProbePhase.TCP_CONNECT
                phaseStartedAt = SystemClock.elapsedRealtime()
                rawSocket.connect(InetSocketAddress(address, HTTPS_PORT), CONNECT_TIMEOUT_MS)
                tcpElapsedMs = SystemClock.elapsedRealtime() - phaseStartedAt
                tcpConnected = true

                phase = ProbePhase.TLS_HANDSHAKE
                phaseStartedAt = SystemClock.elapsedRealtime()
                val sslSocket = factory.createSocket(
                    rawSocket,
                    host,
                    HTTPS_PORT,
                    true
                ) as SSLSocket

                sslSocket.use { socket ->
                    socket.soTimeout = TLS_TIMEOUT_MS
                    val sslParameters = socket.sslParameters
                    sslParameters.serverNames = listOf(SNIHostName(host))
                    sslParameters.endpointIdentificationAlgorithm = "HTTPS"
                    socket.sslParameters = sslParameters

                    socket.startHandshake()
                    tlsElapsedMs = SystemClock.elapsedRealtime() - phaseStartedAt
                    tlsSucceeded = true
                    tlsProtocol = socket.session.protocol

                    phase = ProbePhase.HTTP_RESPONSE
                    phaseStartedAt = SystemClock.elapsedRealtime()
                    socket.soTimeout = HTTP_TIMEOUT_MS

                    val request = buildString {
                        append("GET / HTTP/1.1\r\n")
                        append("Host: $host\r\n")
                        append("User-Agent: TGU-Diagnostics/0.4\r\n")
                        append("Accept: */*\r\n")
                        append("Connection: close\r\n")
                        append("\r\n")
                    }
                    socket.outputStream.write(request.toByteArray(Charsets.US_ASCII))
                    socket.outputStream.flush()

                    val statusLine = BufferedReader(
                        InputStreamReader(socket.inputStream, Charsets.ISO_8859_1)
                    ).readLine()

                    httpElapsedMs = SystemClock.elapsedRealtime() - phaseStartedAt
                    if (statusLine.isNullOrBlank()) {
                        detail = "TCP connected; TLS succeeded; server closed without an HTTP status line"
                    } else {
                        httpStatusLine = statusLine.take(MAX_STATUS_LINE_LENGTH)
                        httpStatusCode = HTTP_STATUS_REGEX.find(statusLine)
                            ?.groupValues
                            ?.getOrNull(1)
                            ?.toIntOrNull()
                        detail = if (httpStatusCode != null) {
                            "TCP connected; TLS $tlsProtocol; HTTP $httpStatusCode"
                        } else {
                            "TCP connected; TLS $tlsProtocol; HTTP response received"
                        }
                    }
                }
            }
        } catch (_: SocketTimeoutException) {
            val elapsed = SystemClock.elapsedRealtime() - phaseStartedAt
            detail = when (phase) {
                ProbePhase.TCP_CONNECT -> {
                    tcpElapsedMs = elapsed
                    "TCP connection timed out"
                }
                ProbePhase.TLS_HANDSHAKE -> {
                    tlsElapsedMs = elapsed
                    tlsSucceeded = false
                    "TCP connected; TLS handshake timed out"
                }
                ProbePhase.HTTP_RESPONSE -> {
                    httpElapsedMs = elapsed
                    "TCP connected; TLS succeeded; HTTP response timed out"
                }
            }
        } catch (exception: SSLHandshakeException) {
            val elapsed = SystemClock.elapsedRealtime() - phaseStartedAt
            if (phase == ProbePhase.HTTP_RESPONSE) {
                httpElapsedMs = elapsed
                detail = "TCP connected; TLS succeeded; HTTP TLS stream failed (${exception.javaClass.simpleName})"
            } else {
                tlsElapsedMs = elapsed
                tlsSucceeded = false
                detail = "TCP connected; TLS certificate/handshake validation failed (${exception.javaClass.simpleName})"
            }
        } catch (exception: SSLException) {
            val elapsed = SystemClock.elapsedRealtime() - phaseStartedAt
            if (phase == ProbePhase.HTTP_RESPONSE) {
                httpElapsedMs = elapsed
                detail = "TCP connected; TLS succeeded; HTTP TLS stream failed (${exception.javaClass.simpleName})"
            } else {
                tlsElapsedMs = elapsed
                tlsSucceeded = false
                detail = "TCP connected; TLS handshake failed (${exception.javaClass.simpleName})"
            }
        } catch (_: ConnectException) {
            val elapsed = SystemClock.elapsedRealtime() - phaseStartedAt
            detail = when (phase) {
                ProbePhase.TCP_CONNECT -> {
                    tcpElapsedMs = elapsed
                    "TCP connection refused or unreachable"
                }
                ProbePhase.TLS_HANDSHAKE -> {
                    tlsElapsedMs = elapsed
                    tlsSucceeded = false
                    "TCP connected; TLS connection failed"
                }
                ProbePhase.HTTP_RESPONSE -> {
                    httpElapsedMs = elapsed
                    "TCP connected; TLS succeeded; HTTP connection closed before response"
                }
            }
        } catch (_: IOException) {
            val elapsed = SystemClock.elapsedRealtime() - phaseStartedAt
            detail = when (phase) {
                ProbePhase.TCP_CONNECT -> {
                    tcpElapsedMs = elapsed
                    "TCP network I/O error"
                }
                ProbePhase.TLS_HANDSHAKE -> {
                    tlsElapsedMs = elapsed
                    tlsSucceeded = false
                    "TCP connected; TLS network I/O error"
                }
                ProbePhase.HTTP_RESPONSE -> {
                    httpElapsedMs = elapsed
                    "TCP connected; TLS succeeded; HTTP exchange failed"
                }
            }
        } catch (_: SecurityException) {
            detail = "Connection blocked by system policy"
        }

        return AddressFamilyResult(
            addressCount = addressCount,
            tcp443Reachable = tcpConnected,
            tlsHandshakeSucceeded = tlsSucceeded,
            tlsProtocol = tlsProtocol,
            httpStatusCode = httpStatusCode,
            httpStatusLine = httpStatusLine,
            tcpElapsedMs = tcpElapsedMs,
            tlsElapsedMs = tlsElapsedMs,
            httpElapsedMs = httpElapsedMs,
            elapsedMs = SystemClock.elapsedRealtime() - startedAt,
            detail = detail
        )
    }

    private fun failedDns(
        host: String,
        isControl: Boolean,
        startedAt: Long,
        detail: String
    ) = EndpointCheck(
        host = host,
        isControl = isControl,
        dnsResolved = false,
        ipv4 = null,
        ipv6 = null,
        tcp443Reachable = false,
        elapsedMs = SystemClock.elapsedRealtime() - startedAt,
        detail = detail
    )

    private enum class ProbePhase {
        TCP_CONNECT,
        TLS_HANDSHAKE,
        HTTP_RESPONSE
    }

    private data class TargetHost(val host: String, val isControl: Boolean)

    private companion object {
        val TARGET_HOSTS = listOf(
            TargetHost("web.telegram.org", false),
            TargetHost("api.telegram.org", false),
            TargetHost("telegram.org", false),
            TargetHost("t.me", false),
            TargetHost("example.com", true),
            TargetHost("www.cloudflare.com", true)
        )
        val HTTP_STATUS_REGEX = Regex("^HTTP/\\d(?:\\.\\d)?\\s+(\\d{3})(?:\\s+.*)?$")
        const val HTTPS_PORT = 443
        const val CONNECT_TIMEOUT_MS = 2_500
        const val TLS_TIMEOUT_MS = 4_000
        const val HTTP_TIMEOUT_MS = 4_000
        const val MAX_STATUS_LINE_LENGTH = 160
    }
}

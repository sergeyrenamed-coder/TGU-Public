package com.rnmd.tgu.diagnostics

import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.Callable
import java.util.concurrent.Executors

data class DcPortCheck(
    val dcLabel: String,
    val address: String,
    val addressFamily: String,
    val source: String,
    val port: Int,
    val reachable: Boolean,
    val elapsedMs: Long,
    val detail: String
)

   
                                                                           
  
                                                                              
                                                                              
                                                                          
   
object TelegramDcScanner {
    private const val CONNECT_TIMEOUT_MS = 1_500
    private const val WORKER_COUNT = 8
    private val TEST_PORTS = listOf(80, 443)

    private data class DcTarget(
        val label: String,
        val address: String,
        val family: String,
        val source: String
    )

    private val targets = listOf(
        DcTarget("DC1", "149.154.175.53", "IPv4", "Pyrogram production reference"),
        DcTarget("DC1", "2001:b28:f23d:f001::a", "IPv6", "Pyrogram production reference"),
        DcTarget("DC2", "149.154.167.51", "IPv4", "Pyrogram production reference / OONI"),
        DcTarget("DC2", "2001:67c:4e8:f002::a", "IPv6", "Pyrogram production reference"),
        DcTarget("DC3", "149.154.175.100", "IPv4", "Pyrogram production reference / OONI"),
        DcTarget("DC3", "2001:b28:f23d:f003::a", "IPv6", "Pyrogram production reference"),
        DcTarget("DC4", "149.154.167.91", "IPv4", "Pyrogram production reference / OONI"),
        DcTarget("DC4", "2001:67c:4e8:f004::a", "IPv6", "Pyrogram production reference"),
        DcTarget("DC5", "91.108.56.130", "IPv4", "Pyrogram production reference"),
        DcTarget("DC5", "2001:b28:f23f:f005::a", "IPv6", "Pyrogram production reference"),
        DcTarget("Additional access point", "149.154.175.50", "IPv4", "OONI Telegram test reference"),
        DcTarget("Additional access point", "149.154.171.5", "IPv4", "OONI Telegram test reference"),
        DcTarget("Additional access point", "95.161.76.100", "IPv4", "OONI Telegram test reference")
    )

    fun scan(): List<DcPortCheck> {
        val tasks = targets.flatMap { target ->
            TEST_PORTS.map { port ->
                Callable { probe(target, port) }
            }
        }

        val executor = Executors.newFixedThreadPool(WORKER_COUNT)
        return try {
            executor.invokeAll(tasks).map { it.get() }
                .sortedWith(compareBy<DcPortCheck>({ it.dcLabel }, { it.addressFamily }, { it.address }, { it.port }))
        } finally {
            executor.shutdownNow()
        }
    }

    private fun probe(target: DcTarget, port: Int): DcPortCheck {
        val startedAt = System.nanoTime()
        return try {
            val address = InetAddress.getByName(target.address)
            Socket().use { socket ->
                socket.connect(InetSocketAddress(address, port), CONNECT_TIMEOUT_MS)
            }
            result(target, port, startedAt, true, "TCP connection established")
        } catch (_: SocketTimeoutException) {
            result(target, port, startedAt, false, "Connection timed out")
        } catch (_: ConnectException) {
            result(target, port, startedAt, false, "Connection refused or unreachable")
        } catch (_: UnknownHostException) {
            result(target, port, startedAt, false, "Address could not be parsed")
        } catch (_: IOException) {
            result(target, port, startedAt, false, "Network I/O error")
        } catch (_: SecurityException) {
            result(target, port, startedAt, false, "Connection blocked by system policy")
        }
    }

    private fun result(
        target: DcTarget,
        port: Int,
        startedAtNanos: Long,
        reachable: Boolean,
        detail: String
    ) = DcPortCheck(
        dcLabel = target.label,
        address = target.address,
        addressFamily = target.family,
        source = target.source,
        port = port,
        reachable = reachable,
        elapsedMs = (System.nanoTime() - startedAtNanos) / 1_000_000L,
        detail = detail
    )
}

package com.rnmd.tgu.proxy

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Opens the engine's outbound stream through a selected remote proxy.
 *
 * SOCKS5: establishes CONNECT to the selected Telegram DC and sends the
 * ordinary relay handshake through the tunnel.
 *
 * MTProto: connects to the selected remote MTProxy and sends a fresh
 * obfuscated2 handshake derived with that remote proxy's secret.
 *
 * This is a Telegram-only transport adapter. It does not create a VPN.
 */
internal object UpstreamProxyConnector {
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val HANDSHAKE_TIMEOUT_MS = 8_000

    fun supportsMtProtoProfile(profile: ProxyProfile): Boolean {
        if (profile.type != ProxyType.MTPROTO) return false
        return runCatching { mtProtoSecretBytes(profile.secret) }.isSuccess
    }

    fun mtProtoSecretBytes(value: String): ByteArray {
        val normalized = value.trim().lowercase()
        // "dd" is the Telegram padded-intermediate transport marker. It is
        // not part of the 16-byte secret used by the obfuscated2 KDF.
        val rawHex = if (normalized.startsWith("dd") && normalized.length == 34) {
            normalized.drop(2)
        } else {
            normalized
        }
        if (rawHex.startsWith("ee")) {
            throw IllegalArgumentException("WEB/FakeTLS MTProxy secrets are not supported as upstream yet.")
        }
        if (rawHex.length != 32 || rawHex.any { it !in "0123456789abcdef" }) {
            throw IllegalArgumentException("This upstream requires a classic 16-byte MTProto secret.")
        }
        return rawHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    fun connect(
        profile: ProxyProfile,
        targetDcHost: String,
        targetDcPort: Int,
        bufferSize: Int,
        relayInit: ByteArray,
    ): Socket {
        require(profile.validationError() == null) {
            profile.validationError() ?: "Invalid upstream profile."
        }
        require(targetDcPort in 1..65535)

        val socket = Socket()
        var success = false
        try {
            socket.tcpNoDelay = true
            socket.keepAlive = true
            socket.sendBufferSize = bufferSize
            socket.receiveBufferSize = bufferSize
            socket.connect(InetSocketAddress(profile.host, profile.port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = HANDSHAKE_TIMEOUT_MS

            if (profile.type == ProxyType.SOCKS5) {
                establishSocks5Tunnel(socket, profile, targetDcHost, targetDcPort)
            }

            socket.getOutputStream().apply {
                write(relayInit)
                flush()
            }
            socket.soTimeout = 0
            success = true
            return socket
        } finally {
            if (!success) {
                runCatching { socket.close() }
            }
        }
    }

    private fun establishSocks5Tunnel(
        socket: Socket,
        profile: ProxyProfile,
        targetHost: String,
        targetPort: Int,
    ) {
        val input = DataInputStream(socket.getInputStream())
        val output = DataOutputStream(socket.getOutputStream())
        val hasCredentials = profile.username.isNotEmpty()

        // Offer only methods that TGU can actually complete.
        output.writeByte(5)
        if (hasCredentials) {
            output.writeByte(2)
            output.writeByte(0)
            output.writeByte(2)
        } else {
            output.writeByte(1)
            output.writeByte(0)
        }
        output.flush()

        val version = input.readUnsignedByte()
        val method = input.readUnsignedByte()
        if (version != 5) throw IOException("Upstream returned an invalid SOCKS5 version.")
        when (method) {
            0 -> Unit
            2 -> {
                if (!hasCredentials) throw IOException("SOCKS5 upstream requires credentials.")
                val user = profile.username.toByteArray(Charsets.UTF_8)
                val pass = profile.password.toByteArray(Charsets.UTF_8)
                if (user.size !in 1..255 || pass.size !in 1..255) {
                    throw IOException("SOCKS5 credentials have an invalid length.")
                }
                output.writeByte(1)
                output.writeByte(user.size)
                output.write(user)
                output.writeByte(pass.size)
                output.write(pass)
                output.flush()
                if (input.readUnsignedByte() != 1 || input.readUnsignedByte() != 0) {
                    throw IOException("SOCKS5 upstream rejected authentication.")
                }
            }
            255 -> throw IOException("SOCKS5 upstream rejected the offered authentication methods.")
            else -> throw IOException("SOCKS5 upstream selected an unsupported authentication method.")
        }

        val ipv4 = targetHost.split('.').takeIf { parts ->
            parts.size == 4 && parts.all { part ->
                part.isNotEmpty() && part.length <= 3 && part.all(Char::isDigit) &&
                    (part.toIntOrNull() ?: -1) in 0..255
            }
        }?.map { it.toInt().toByte() }?.toByteArray()

        output.writeByte(5)
        output.writeByte(1) // CONNECT
        output.writeByte(0)
        when {
            ipv4 != null -> {
                output.writeByte(1)
                output.write(ipv4)
            }
            targetHost.contains(':') -> {
                val raw = java.net.InetAddress.getByName(targetHost).address
                if (raw.size != 16) throw IOException("Invalid IPv6 target address.")
                output.writeByte(4)
                output.write(raw)
            }
            else -> {
                val host = java.net.IDN.toASCII(targetHost).toByteArray(Charsets.US_ASCII)
                if (host.isEmpty() || host.size > 255) throw IOException("Invalid SOCKS5 target hostname.")
                output.writeByte(3)
                output.writeByte(host.size)
                output.write(host)
            }
        }
        output.writeShort(targetPort)
        output.flush()

        val replyVersion = input.readUnsignedByte()
        val reply = input.readUnsignedByte()
        input.readUnsignedByte() // RSV
        val addressType = input.readUnsignedByte()
        if (replyVersion != 5) throw IOException("Invalid SOCKS5 CONNECT response.")
        when (addressType) {
            1 -> input.readFully(ByteArray(4))
            3 -> input.readFully(ByteArray(input.readUnsignedByte()))
            4 -> input.readFully(ByteArray(16))
            else -> throw IOException("SOCKS5 returned an unknown address type.")
        }
        input.readUnsignedShort()
        if (reply != 0) {
            val reason = when (reply) {
                1 -> "general failure"
                2 -> "connection not allowed"
                3 -> "network unreachable"
                4 -> "host unreachable"
                5 -> "connection refused"
                6 -> "TTL expired"
                7 -> "command unsupported"
                8 -> "address type unsupported"
                else -> "reply $reply"
            }
            throw ConnectException("SOCKS5 CONNECT failed: $reason.")
        }
    }
}

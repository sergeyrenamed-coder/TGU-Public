package com.rnmd.tgu.proxy

import android.net.Uri
import android.os.SystemClock
import java.io.*
import java.net.*
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException

data class ProxyTestResult(
    val success: Boolean,
    val title: String,
    val detail: String,
    val elapsedMs: Long
)

object ProxyDiagnostics {
    private const val RES_PQ_CONSTRUCTOR = 0x05162463
    private const val CONNECT_TIMEOUT = 5_000
    private const val READ_TIMEOUT = 5_000
    private const val TELEGRAM_DC_HOST = "149.154.167.51"
    private const val TELEGRAM_DC_PORT = 443

    fun check(
        profile: ProxyProfile,
        connectTimeoutMs: Int = CONNECT_TIMEOUT,
        readTimeoutMs: Int = READ_TIMEOUT,
    ): ProxyTestResult =
        when (profile.type) {
            ProxyType.MTPROTO -> checkMtprotoHandshake(profile, connectTimeoutMs, readTimeoutMs)
            ProxyType.SOCKS5 -> checkSocks5(profile, connectTimeoutMs, readTimeoutMs)
        }

       
                                                                          
                                                                            
                                                                 
       
    private fun checkMtprotoHandshake(profile: ProxyProfile, connectTimeoutMs: Int, readTimeoutMs: Int): ProxyTestResult {
        val start = SystemClock.elapsedRealtime()
        var stage = "MTProxy TCP connect"
        return try {
            val secret = UpstreamProxyConnector.mtProtoSecretBytes(profile.secret)
            val padded = profile.secret.trim().startsWith("dd", ignoreCase = true) &&
                profile.secret.trim().length == 34
            val relay = MtProtoCrypto.generateRelayInit(
                if (padded) MtProtoConstants.TAG_PADDED_INTERMEDIATE else MtProtoConstants.TAG_INTERMEDIATE,
                2.toShort(),
                secret,
            )

            Socket().use { socket ->
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(profile.host, profile.port), connectTimeoutMs)
                socket.soTimeout = readTimeoutMs
                verifyTelegramDc(socket, relay, padded) { stage = it }

                ProxyTestResult(
                    success = true,
                    title = "MTProto handshake verified",
                    detail = "Obfuscated2 secret accepted; Telegram DC2 returned a valid resPQ response.",
                    elapsedMs = SystemClock.elapsedRealtime() - start,
                )
            }
        } catch (e: Exception) {
            ProxyTestResult(
                success = false,
                title = "MTProto handshake failed",
                detail = failureText(stage, e),
                elapsedMs = SystemClock.elapsedRealtime() - start,
            )
        }
    }

       
                                                                         
                                                                         
                                                                           
       
    private fun verifyTelegramDc(
        socket: Socket,
        relay: RelayInitResult,
        padded: Boolean,
        onStage: (String) -> Unit,
    ) {
        val input = DataInputStream(socket.getInputStream())
        val output = socket.getOutputStream()

        onStage("MTProto obfuscated2 handshake")
        output.write(relay.initPacket)

                                                     
                                                                       
        val random = java.security.SecureRandom()
        val nonce = ByteArray(16).also(random::nextBytes)
        val request = ByteArray(40)
        val nowMs = System.currentTimeMillis()
        val messageId = (
            ((nowMs / 1000L) shl 32) or
                (((nowMs % 1000L) * 4_294_967L / 1_000L) and 0xFFFFFFFFL)
            ) and -4L
        for (index in 0 until 8) {
            request[8 + index] = (messageId ushr (index * 8)).toByte()
        }
        MtProtoCrypto.writeInt32LE(request, 16, 20)
        MtProtoCrypto.writeInt32LE(request, 20, 0xBE7E8EF1.toInt())
        System.arraycopy(nonce, 0, request, 24, nonce.size)

                                                                                              
        val paddingLength = if (padded) random.nextInt(16) else 0
        val envelope = ByteArray(4 + request.size + paddingLength)
        MtProtoCrypto.writeInt32LE(envelope, 0, request.size + paddingLength)
        System.arraycopy(request, 0, envelope, 4, request.size)
        if (paddingLength > 0) {
            val padding = ByteArray(paddingLength).also(random::nextBytes)
            System.arraycopy(padding, 0, envelope, 4 + request.size, paddingLength)
        }
        output.write(relay.telegramEncryptor.update(envelope))
        output.flush()

        onStage("MTProto resPQ response")
        val encryptedLength = ByteArray(4)
        input.readFully(encryptedLength)
        val plainLength = relay.telegramDecryptor.update(encryptedLength)
                                                                                        
        val frameLength = MtProtoCrypto.readInt32LE(plainLength, 0) and 0x7FFFFFFF
        if (frameLength !in 4..4_096) {
            throw IOException("Invalid MTProto response frame length: $frameLength.")
        }

        val encryptedResponse = ByteArray(frameLength)
        input.readFully(encryptedResponse)
        val response = relay.telegramDecryptor.update(encryptedResponse)
        if (frameLength == 4) {
            val transportError = MtProtoCrypto.readInt32LE(response, 0)
            throw IOException("Telegram transport error $transportError.")
        }
        if (response.size < 40) throw IOException("MTProto response was too short.")
        if (response.copyOfRange(0, 8).any { it != 0.toByte() }) {
            throw IOException("MTProto response was not an unauthenticated resPQ packet.")
        }

        val bodyLength = MtProtoCrypto.readInt32LE(response, 16)
        if (bodyLength < 20 || bodyLength > response.size - 20) {
            throw IOException("MTProto response body length was invalid.")
        }
        val constructor = MtProtoCrypto.readInt32LE(response, 20)
        if (constructor != RES_PQ_CONSTRUCTOR) {
            throw IOException(
                "Telegram DC returned an unexpected MTProto constructor: 0x" +
                    constructor.toUInt().toString(16) + "."
            )
        }
        val echoedNonce = response.copyOfRange(24, 40)
        if (!nonce.contentEquals(echoedNonce)) {
            throw IOException("Telegram DC did not echo the MTProto test nonce.")
        }
    }

       
                                                                               
                                                                                    
                                                                   
       
    private fun checkSocks5(
        profile: ProxyProfile,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): ProxyTestResult {
        val start = SystemClock.elapsedRealtime()
        var stage = "SOCKS5 TCP connect"
        return try {
            Socket().use { proxySocket ->
                proxySocket.tcpNoDelay = true
                proxySocket.connect(InetSocketAddress(profile.host, profile.port), connectTimeoutMs)
                proxySocket.soTimeout = readTimeoutMs
                val input = DataInputStream(proxySocket.getInputStream())
                val output = DataOutputStream(proxySocket.getOutputStream())

                stage = "SOCKS5 method negotiation"
                val hasCredentials = profile.username.isNotEmpty()
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
                if (version != 5) throw IOException("Proxy returned an invalid SOCKS version.")
                if (method == 255) throw IOException("Proxy rejected the offered authentication methods.")

                when (method) {
                    0 -> Unit
                    2 -> {
                        if (!hasCredentials) throw IOException("Proxy requires a username and password.")
                        stage = "SOCKS5 username/password authentication"
                        val user = profile.username.toByteArray(Charsets.UTF_8)
                        val pass = profile.password.toByteArray(Charsets.UTF_8)
                        output.writeByte(1)
                        output.writeByte(user.size)
                        output.write(user)
                        output.writeByte(pass.size)
                        output.write(pass)
                        output.flush()
                        if (input.readUnsignedByte() != 1 || input.readUnsignedByte() != 0) {
                            throw IOException("SOCKS5 authentication was rejected.")
                        }
                    }
                    else -> throw IOException("Proxy selected an unsupported authentication method.")
                }

                stage = "SOCKS5 CONNECT Telegram DC $TELEGRAM_DC_HOST:$TELEGRAM_DC_PORT"
                val address = InetAddress.getByName(TELEGRAM_DC_HOST).address
                if (address.size != 4) throw IOException("Telegram DC test address was not IPv4.")
                output.writeByte(5)
                output.writeByte(1)
                output.writeByte(0)
                output.writeByte(1)
                output.write(address)
                output.writeShort(TELEGRAM_DC_PORT)
                output.flush()

                val replyVersion = input.readUnsignedByte()
                val replyCode = input.readUnsignedByte()
                input.readUnsignedByte()
                val addressType = input.readUnsignedByte()
                if (replyVersion != 5) throw IOException("Invalid SOCKS5 CONNECT response.")
                when (addressType) {
                    1 -> input.readFully(ByteArray(4))
                    3 -> input.readFully(ByteArray(input.readUnsignedByte()))
                    4 -> input.readFully(ByteArray(16))
                    else -> throw IOException("Unknown SOCKS5 bound-address type.")
                }
                input.readUnsignedShort()
                if (replyCode != 0) throw IOException("SOCKS5 CONNECT failed with reply code $replyCode.")

                                                                                      
                                                                                         
                                                                                         
                val relay = MtProtoCrypto.generateRelayInit(
                    MtProtoConstants.TAG_INTERMEDIATE,
                    2.toShort(),
                )
                verifyTelegramDc(proxySocket, relay, padded = false) { stage = it }

                ProxyTestResult(
                    success = true,
                    title = "SOCKS5 Telegram MTProto handshake verified",
                    detail = "SOCKS5 tunnel reached Telegram DC2 and returned a valid resPQ response.",
                    elapsedMs = SystemClock.elapsedRealtime() - start,
                )
            }
        } catch (e: Exception) {
            ProxyTestResult(
                success = false,
                title = "SOCKS5 check failed at $stage",
                detail = failureText(stage, e),
                elapsedMs = SystemClock.elapsedRealtime() - start,
            )
        }
    }

    private fun failureText(stage: String, e: Exception): String {
        val reason = when (e) {
            is SocketTimeoutException -> "Timed out."
            is UnknownHostException -> "The proxy hostname could not be resolved."
            is ConnectException -> "Connection refused or unreachable."
            is SSLHandshakeException -> "TLS certificate or handshake validation failed."
            is SSLException -> "TLS handshake failed."
            is IOException -> e.message?.take(180) ?: "Network I/O error."
            else -> e.javaClass.simpleName
        }
        return stage + ": " + reason
    }

    fun telegramUri(profile: ProxyProfile): Uri {
        val uri = Uri.Builder().scheme("tg")
        when (profile.type) {
            ProxyType.MTPROTO -> uri.authority("proxy")
                .appendQueryParameter("server", profile.host)
                .appendQueryParameter("port", profile.port.toString())
                .appendQueryParameter("secret", profile.secret)
            ProxyType.SOCKS5 -> {
                uri.authority("socks")
                    .appendQueryParameter("server", profile.host)
                    .appendQueryParameter("port", profile.port.toString())
                if (profile.username.isNotEmpty()) {
                    uri.appendQueryParameter("user", profile.username)
                        .appendQueryParameter("pass", profile.password)
                }
            }
        }
        return uri.build()
    }
}

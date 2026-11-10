package com.rnmd.tgu.proxy

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

   
                                                        
   
class AesCtr(key: ByteArray, iv: ByteArray) {
    private val cipher: Cipher = Cipher.getInstance("AES/CTR/NoPadding").apply {
        init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
    }

    fun update(data: ByteArray): ByteArray = cipher.update(data)
}

   
                                           
                                                                             
   
class CryptoCtx(
    val clientDecryptor: AesCtr,
    val clientEncryptor: AesCtr,
    val telegramDecryptor: AesCtr,
    val telegramEncryptor: AesCtr,
)

   
                                                                
   
object MtProtoCrypto {
    private val ZERO_64 = ByteArray(64)

       
                                                         
       
    fun deriveKey(prekey: ByteArray, secret: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(prekey)
        md.update(secret)
        return md.digest()
    }

       
                                                     
                                                                             
       
    fun parseHandshake(
        initData: ByteArray,
        secret: ByteArray,
    ): HandshakeResult? {
        if (initData.size != MtProtoConstants.HANDSHAKE_LEN) return null

                                                             
        val prekey = initData.sliceArray(MtProtoConstants.SKIP_LEN until MtProtoConstants.SKIP_LEN + MtProtoConstants.PREKEY_LEN)
        val iv = initData.sliceArray(MtProtoConstants.SKIP_LEN + MtProtoConstants.PREKEY_LEN until MtProtoConstants.SKIP_LEN + MtProtoConstants.PREKEY_LEN + MtProtoConstants.IV_LEN)

                                
        val decKey = deriveKey(prekey, secret)
        val decryptor = AesCtr(decKey, iv)

                                
        val decrypted = decryptor.update(initData)

                                            
        val protoTag = readInt32LE(decrypted, MtProtoConstants.PROTO_TAG_POS)
        if (protoTag != MtProtoConstants.TAG_ABRIDGED &&
            protoTag != MtProtoConstants.TAG_INTERMEDIATE &&
            protoTag != MtProtoConstants.TAG_PADDED_INTERMEDIATE
        ) {
            return null
        }

                                                            
        val dcIdx = readInt16LE(decrypted, MtProtoConstants.DC_IDX_POS)

                                   
                                                                       
        val clientDecKey = deriveKey(prekey, secret)
        val clientDecryptor = AesCtr(clientDecKey, iv)
                                                 
        clientDecryptor.update(ZERO_64)

                                                                              
        val encPrekeyIv = initData.sliceArray(
            MtProtoConstants.SKIP_LEN until MtProtoConstants.SKIP_LEN + MtProtoConstants.PREKEY_LEN + MtProtoConstants.IV_LEN
        ).reversedArray()
        val encPrekey = encPrekeyIv.sliceArray(0 until MtProtoConstants.PREKEY_LEN)
        val encIv = encPrekeyIv.sliceArray(MtProtoConstants.PREKEY_LEN until MtProtoConstants.PREKEY_LEN + MtProtoConstants.IV_LEN)
        val clientEncKey = deriveKey(encPrekey, secret)
        val clientEncryptor = AesCtr(clientEncKey, encIv)

        return HandshakeResult(
            protoTag = protoTag,
            dcIndex = dcIdx,
            clientDecryptor = clientDecryptor,
            clientEncryptor = clientEncryptor,
        )
    }

       
                                                                           
                                                                                      
      
                                                                            
                                                         
       
    fun generateRelayInit(
        protoTag: Int,
        dcIndex: Short,
        mtProxySecret: ByteArray? = null,
    ): RelayInitResult {
        require(mtProxySecret == null || mtProxySecret.size == 16) {
            "Classic MTProxy upstream secrets must be exactly 16 bytes."
        }
        val rng = java.security.SecureRandom()
        while (true) {
            val relayInit = ByteArray(64).also { rng.nextBytes(it) }

                                         
            if (relayInit[0] in MtProtoConstants.RESERVED_FIRST_BYTES) continue

                                                                                                   
            val first4 = relayInit.sliceArray(0 until 4)
            if (MtProtoConstants.RESERVED_STARTS.any { it.contentEquals(first4) }) continue

                                                                 
            if (relayInit[4] == 0.toByte() && relayInit[5] == 0.toByte() &&
                relayInit[6] == 0.toByte() && relayInit[7] == 0.toByte()) continue

                                                                         
                                                                                   
            val prekey = relayInit.sliceArray(
                MtProtoConstants.SKIP_LEN until MtProtoConstants.SKIP_LEN + MtProtoConstants.PREKEY_LEN
            )
            val encIv = relayInit.sliceArray(
                MtProtoConstants.SKIP_LEN + MtProtoConstants.PREKEY_LEN until
                    MtProtoConstants.SKIP_LEN + MtProtoConstants.PREKEY_LEN + MtProtoConstants.IV_LEN
            )
            val encKey = mtProxySecret?.let { deriveKey(prekey, it) } ?: prekey

                                                                                                 
            val dcBytes = ByteArray(2)
            writeInt16LE(dcBytes, 0, dcIndex)
            val randomPad = ByteArray(2).also { rng.nextBytes(it) }
            val tailPlain = ByteArray(8)
            writeInt32LE(tailPlain, 0, protoTag)
            System.arraycopy(dcBytes, 0, tailPlain, 4, 2)
            System.arraycopy(randomPad, 0, tailPlain, 6, 2)

            val handshakeCipher = AesCtr(encKey, encIv)
            val encryptedFull = handshakeCipher.update(relayInit.clone())
            val keystreamTail = ByteArray(8) { i ->
                (encryptedFull[56 + i].toInt() xor relayInit[56 + i].toInt()).toByte()
            }
            val encryptedTail = ByteArray(8) { i ->
                (tailPlain[i].toInt() xor keystreamTail[i].toInt()).toByte()
            }
            System.arraycopy(encryptedTail, 0, relayInit, MtProtoConstants.PROTO_TAG_POS, 8)

                                                                                     
            val tgEncryptor = AesCtr(encKey, encIv)
            tgEncryptor.update(ZERO_64)

                                                                                  
                                                                                   
            val revPrekeyIv = relayInit.sliceArray(
                MtProtoConstants.SKIP_LEN until
                    MtProtoConstants.SKIP_LEN + MtProtoConstants.PREKEY_LEN + MtProtoConstants.IV_LEN
            ).reversedArray()
            val reversedKey = revPrekeyIv.sliceArray(0 until MtProtoConstants.KEY_LEN)
            val tgDecKey = mtProxySecret?.let { deriveKey(reversedKey, it) } ?: reversedKey
            val tgDecIv = revPrekeyIv.sliceArray(
                MtProtoConstants.KEY_LEN until MtProtoConstants.KEY_LEN + MtProtoConstants.IV_LEN
            )
            val tgDecryptor = AesCtr(tgDecKey, tgDecIv)

            return RelayInitResult(
                initPacket = relayInit,
                telegramEncryptor = tgEncryptor,
                telegramDecryptor = tgDecryptor,
            )
        }
    }

    private fun sha256(data: ByteArray): ByteArray {
        return MessageDigest.getInstance("SHA-256").digest(data)
    }

    fun readInt32LE(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xFF) or
                ((data[offset + 1].toInt() and 0xFF) shl 8) or
                ((data[offset + 2].toInt() and 0xFF) shl 16) or
                ((data[offset + 3].toInt() and 0xFF) shl 24)
    }

    fun readInt16LE(data: ByteArray, offset: Int): Short {
        return ((data[offset].toInt() and 0xFF) or
                ((data[offset + 1].toInt() and 0xFF) shl 8)).toShort()
    }

    fun writeInt32LE(data: ByteArray, offset: Int, value: Int) {
        data[offset] = (value and 0xFF).toByte()
        data[offset + 1] = ((value shr 8) and 0xFF).toByte()
        data[offset + 2] = ((value shr 16) and 0xFF).toByte()
        data[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    fun writeInt16LE(data: ByteArray, offset: Int, value: Short) {
        data[offset] = (value.toInt() and 0xFF).toByte()
        data[offset + 1] = ((value.toInt() shr 8) and 0xFF).toByte()
    }
}

data class HandshakeResult(
    val protoTag: Int,
    val dcIndex: Short,
    val clientDecryptor: AesCtr,
    val clientEncryptor: AesCtr,
)

data class RelayInitResult(
    val initPacket: ByteArray,
    val telegramEncryptor: AesCtr,
    val telegramDecryptor: AesCtr,
) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

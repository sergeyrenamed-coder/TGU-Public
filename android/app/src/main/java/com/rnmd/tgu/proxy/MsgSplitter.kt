package com.rnmd.tgu.proxy

   
                                                                               
                                                                                     
                                                                             
                                                           
   
class MsgSplitter(
    protoTag: Int,
) {
    companion object {
        internal const val MAX_PACKET_SIZE = 16 * 1024 * 1024
    }

    private val proto: Int = protoTag
    private var cipherBuf = ByteArray(0)
    private var plainBuf = ByteArray(0)
    private var disabled = false

       
                                                                      
                                                                        
                                                                             
       
    fun split(chunk: ByteArray, plain: ByteArray): List<ByteArray> {
        require(chunk.size == plain.size) { "MTProto splitter chunks must have equal lengths" }
        if (chunk.isEmpty()) return emptyList()
        if (disabled) return listOf(chunk)

        cipherBuf = append(cipherBuf, chunk)
        plainBuf = append(plainBuf, plain)

        val parts = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < cipherBuf.size) {
            val packetLen = nextPacketLen(offset)
            if (packetLen == null) {
                                                      
                break
            }
            if (packetLen <= 0) {
                                                                                            
                parts.add(cipherBuf.copyOfRange(offset, cipherBuf.size))
                cipherBuf = ByteArray(0)
                plainBuf = ByteArray(0)
                disabled = true
                return parts
            }
                                          
            parts.add(cipherBuf.copyOfRange(offset, offset + packetLen))
            offset += packetLen
        }

        if (offset > 0) {
            cipherBuf = cipherBuf.copyOfRange(offset, cipherBuf.size)
            plainBuf = plainBuf.copyOfRange(offset, plainBuf.size)
        }
        if (cipherBuf.size > MAX_PACKET_SIZE + 4) {
            throw ProxyException("MTProto splitter buffer too large")
        }
        return parts
    }

       
                                                            
       
    fun flush(): List<ByteArray> {
        if (cipherBuf.isEmpty()) return emptyList()
        val tail = cipherBuf.copyOf()
        cipherBuf = ByteArray(0)
        plainBuf = ByteArray(0)
        return listOf(tail)
    }

       
                                                                    
                                                                                     
                                     
       
    private fun nextPacketLen(offset: Int): Int? {
        if (offset >= plainBuf.size) return null
        return when (proto) {
            MtProtoConstants.TAG_ABRIDGED -> nextAbridgedLen(offset)
            MtProtoConstants.TAG_INTERMEDIATE,
            MtProtoConstants.TAG_PADDED_INTERMEDIATE -> nextIntermediateLen(offset)
            else -> 0                                       
        }
    }

       
                                             
                                                                         
                                                                                   
       
    private fun nextAbridgedLen(offset: Int): Int? {
        val first = plainBuf[offset].toInt() and 0xFF
        val headerLen: Int
        val payloadLen: Int

        if (first == 0x7F || first == 0xFF) {
            if (plainBuf.size - offset < 4) return null
            payloadLen = ((plainBuf[offset + 1].toInt() and 0xFF) or
                    ((plainBuf[offset + 2].toInt() and 0xFF) shl 8) or
                    ((plainBuf[offset + 3].toInt() and 0xFF) shl 16)) * 4
            headerLen = 4
        } else {
            payloadLen = (first and 0x7F) * 4
            headerLen = 1
        }

        if (payloadLen <= 0) return 0
        if (payloadLen > MAX_PACKET_SIZE) {
            throw ProxyException("MTProto packet too large: $payloadLen")
        }
        val packetLen = headerLen + payloadLen
        return if (plainBuf.size - offset < packetLen) null else packetLen
    }

       
                                                 
                                                                          
       
    private fun nextIntermediateLen(offset: Int): Int? {
        if (plainBuf.size - offset < 4) return null
        val payloadLen = MtProtoCrypto.readInt32LE(plainBuf, offset).toLong() and 0x7FFFFFFFL
        if (payloadLen <= 0) return 0
        if (payloadLen > MAX_PACKET_SIZE) {
            throw ProxyException("MTProto packet too large: $payloadLen")
        }
        val packetLen = 4 + payloadLen.toInt()
        return if (plainBuf.size - offset < packetLen) null else packetLen
    }

    private fun append(current: ByteArray, chunk: ByteArray): ByteArray {
        if (current.isEmpty()) return chunk.copyOf()
        return ByteArray(current.size + chunk.size).also {
            System.arraycopy(current, 0, it, 0, current.size)
            System.arraycopy(chunk, 0, it, current.size, chunk.size)
        }
    }
}

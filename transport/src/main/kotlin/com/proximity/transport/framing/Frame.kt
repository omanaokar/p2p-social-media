package com.proximity.transport.framing

sealed interface Frame {
    companion object {
        const val MAGIC_BYTE: Byte = 0x50 // 'P' for Proximity
        const val PROTOCOL_VERSION: Byte = 0x01

        const val TYPE_SINGLE: Byte = 0x01
        const val TYPE_CHUNK: Byte = 0x02
        const val TYPE_HEARTBEAT: Byte = 0x03
        const val TYPE_ACK: Byte = 0x04
    }
}

/**
 * Single-frame payload for data that fits entirely in one chunk (<= chunkSizeBytes).
 */
data class SingleFrame(
    val payload: ByteArray,
) : Frame {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as SingleFrame
        return payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int = payload.contentHashCode()
}

/**
 * A sequenced chunk of a larger payload (e.g. image or large bundle).
 */
data class ChunkFrame(
    val transferId: Long,
    val totalPayloadLength: Int,
    val totalChunks: Int,
    val chunkIndex: Int,
    val totalCrc32: Long,
    val data: ByteArray,
) : Frame {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ChunkFrame
        return transferId == other.transferId &&
                totalPayloadLength == other.totalPayloadLength &&
                totalChunks == other.totalChunks &&
                chunkIndex == other.chunkIndex &&
                totalCrc32 == other.totalCrc32 &&
                data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var result = transferId.hashCode()
        result = 31 * result + totalPayloadLength
        result = 31 * result + totalChunks
        result = 31 * result + chunkIndex
        result = 31 * result + totalCrc32.hashCode()
        result = 31 * result + data.contentHashCode()
        return result
    }
}

/**
 * Periodic keepalive and radio telemetry between connected peers.
 */
data class HeartbeatFrame(
    val timestamp: Long,
    val reportedSignal: Int?,
) : Frame

/**
 * Acknowledgment for a received chunk (optional reliability feedback).
 */
data class AckFrame(
    val transferId: Long,
    val chunkIndex: Int,
) : Frame


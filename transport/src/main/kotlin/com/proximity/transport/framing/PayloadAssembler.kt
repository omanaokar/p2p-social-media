package com.proximity.transport.framing

import java.time.Duration
import java.time.Instant
import java.util.zip.CRC32

class PayloadAssembler(
    private val assemblyTimeout: Duration = Duration.ofSeconds(30),
) {
    private data class TransferKey(val peerId: String, val transferId: Long)

    private class InFlightTransfer(
        val peerId: String,
        val transferId: Long,
        val totalLength: Int,
        val totalChunks: Int,
        val expectedCrc32: Long,
        val createdAt: Instant,
        val chunks: Array<ByteArray?>,
        var receivedCount: Int = 0,
        var lastUpdatedAt: Instant = createdAt,
    )

    private val inFlight = mutableMapOf<TransferKey, InFlightTransfer>()

    /**
     * Processes an incoming [ChunkFrame] from [peerId].
     * Returns the fully assembled and validated byte array if this chunk completes
     * the transfer, or null if more chunks are needed (or if corrupted).
     */
    @Synchronized
    fun receiveChunk(peerId: String, chunk: ChunkFrame, now: Instant = Instant.now()): ByteArray? {
        cleanupExpired(now)

        val key = TransferKey(peerId, chunk.transferId)
        val transfer = inFlight.getOrPut(key) {
            InFlightTransfer(
                peerId = peerId,
                transferId = chunk.transferId,
                totalLength = chunk.totalPayloadLength,
                totalChunks = chunk.totalChunks,
                expectedCrc32 = chunk.totalCrc32,
                createdAt = now,
                chunks = arrayOfNulls(chunk.totalChunks),
            )
        }

        // Validate chunk index bounds
        if (chunk.chunkIndex < 0 || chunk.chunkIndex >= transfer.totalChunks) {
            return null
        }

        // Ignore duplicate chunk if already received
        if (transfer.chunks[chunk.chunkIndex] == null) {
            transfer.chunks[chunk.chunkIndex] = chunk.data
            transfer.receivedCount++
            transfer.lastUpdatedAt = now
        }

        // Check if all chunks have arrived
        if (transfer.receivedCount == transfer.totalChunks) {
            inFlight.remove(key)
            return assemblePayload(transfer)
        }

        return null
    }

    private fun assemblePayload(transfer: InFlightTransfer): ByteArray? {
        val result = ByteArray(transfer.totalLength)
        var offset = 0
        for (i in 0 until transfer.totalChunks) {
            val chunk = transfer.chunks[i] ?: return null
            System.arraycopy(chunk, 0, result, offset, chunk.size)
            offset += chunk.size
        }

        if (offset != transfer.totalLength) {
            return null
        }

        val crc = CRC32()
        crc.update(result)
        if (crc.value != transfer.expectedCrc32) {
            // Checksum failed, discard corrupt payload
            return null
        }

        return result
    }

    /**
     * Removes in-flight transfers that have timed out without receiving all chunks.
     */
    @Synchronized
    fun cleanupExpired(now: Instant = Instant.now()) {
        val cutoff = now.minus(assemblyTimeout)
        val iterator = inFlight.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value.lastUpdatedAt.isBefore(cutoff)) {
                iterator.remove()
            }
        }
    }

    /**
     * Clears all in-flight transfers for a peer that has disconnected.
     */
    @Synchronized
    fun clearPeer(peerId: String) {
        inFlight.keys.removeIf { it.peerId == peerId }
    }

    @Synchronized
    fun inFlightCount(): Int = inFlight.size
}


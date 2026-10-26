package com.proximity.transport.framing

import java.util.concurrent.atomic.AtomicLong
import java.util.zip.CRC32
import kotlin.random.Random

class PayloadChunker(
    private val chunkSizeBytes: Int = 24 * 1024,
    private val maxPayloadSizeBytes: Int = 10 * 1024 * 1024,
) {
    private val transferCounter = AtomicLong((Random.nextLong(0, Long.MAX_VALUE / 2)))

    /**
     * Splits an opaque byte array into framed transmissions.
     * If the payload fits in one chunk, a [SingleFrame] is returned.
     * Otherwise, a sequence of [ChunkFrame]s is generated.
     *
     * @throws IllegalArgumentException if payload exceeds [maxPayloadSizeBytes].
     */
    fun chunk(payload: ByteArray): List<Frame> {
        require(payload.size <= maxPayloadSizeBytes) {
            "Payload size ${payload.size} exceeds maximum allowed size $maxPayloadSizeBytes"
        }

        if (payload.size <= chunkSizeBytes) {
            return listOf(SingleFrame(payload))
        }

        val crc = CRC32()
        crc.update(payload)
        val crcValue = crc.value

        val transferId = transferCounter.incrementAndGet()
        val totalLength = payload.size
        val totalChunks = (totalLength + chunkSizeBytes - 1) / chunkSizeBytes
        val frames = ArrayList<Frame>(totalChunks)

        for (index in 0 until totalChunks) {
            val offset = index * chunkSizeBytes
            val length = minOf(chunkSizeBytes, totalLength - offset)
            val chunkData = payload.copyOfRange(offset, offset + length)

            frames.add(
                ChunkFrame(
                    transferId = transferId,
                    totalPayloadLength = totalLength,
                    totalChunks = totalChunks,
                    chunkIndex = index,
                    totalCrc32 = crcValue,
                    data = chunkData,
                )
            )
        }

        return frames
    }
}


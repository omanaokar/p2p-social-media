package com.proximity.transport

import com.proximity.transport.framing.ChunkFrame
import com.proximity.transport.framing.PayloadAssembler
import com.proximity.transport.framing.PayloadChunker
import com.proximity.transport.framing.SingleFrame
import java.time.Duration
import java.time.Instant
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PayloadChunkingAndReassemblyTest {

    @Test
    fun `small payload stays in single frame`() {
        val chunker = PayloadChunker(chunkSizeBytes = 24 * 1024)
        val payload = Random.nextBytes(500)

        val frames = chunker.chunk(payload)
        assertEquals(1, frames.size)
        assertTrue(frames.first() is SingleFrame)
        assertTrue(payload.contentEquals((frames.first() as SingleFrame).payload))
    }

    @Test
    fun `large image payload is chunked and reassembled bit-identically`() {
        val chunker = PayloadChunker(chunkSizeBytes = 24 * 1024)
        val assembler = PayloadAssembler()

        // 250 KB payload (simulating a compressed image)
        val imagePayload = Random.nextBytes(250 * 1024)
        val frames = chunker.chunk(imagePayload)

        assertEquals(11, frames.size) // ceil(250 / 24) = 11

        var assembled: ByteArray? = null
        for (frame in frames) {
            assertTrue(frame is ChunkFrame)
            val result = assembler.receiveChunk("peer-1", frame)
            if (result != null) {
                assembled = result
            }
        }

        assertNotNull(assembled)
        assertEquals(imagePayload.size, assembled.size)
        assertTrue(imagePayload.contentEquals(assembled))
    }

    @Test
    fun `reassembles out of order chunks correctly`() {
        val chunker = PayloadChunker(chunkSizeBytes = 1024)
        val assembler = PayloadAssembler()

        val payload = Random.nextBytes(5000)
        val frames = chunker.chunk(payload).map { it as ChunkFrame }.shuffled()

        var assembled: ByteArray? = null
        for (chunk in frames) {
            val result = assembler.receiveChunk("peer-1", chunk)
            if (result != null) {
                assembled = result
            }
        }

        assertNotNull(assembled)
        assertTrue(payload.contentEquals(assembled))
    }

    @Test
    fun `duplicate chunks are ignored idempotently`() {
        val chunker = PayloadChunker(chunkSizeBytes = 1024)
        val assembler = PayloadAssembler()

        val payload = Random.nextBytes(3000)
        val frames = chunker.chunk(payload).map { it as ChunkFrame }

        // Send chunk 0 twice
        assembler.receiveChunk("peer-1", frames[0])
        assembler.receiveChunk("peer-1", frames[0])

        var assembled: ByteArray? = null
        for (i in 1 until frames.size) {
            val res = assembler.receiveChunk("peer-1", frames[i])
            if (res != null) assembled = res
        }

        assertNotNull(assembled)
        assertTrue(payload.contentEquals(assembled))
    }

    @Test
    fun `corrupted chunk payload is rejected by checksum verification`() {
        val chunker = PayloadChunker(chunkSizeBytes = 1024)
        val assembler = PayloadAssembler()

        val payload = Random.nextBytes(3000)
        val frames = chunker.chunk(payload).map { it as ChunkFrame }.toMutableList()

        // Corrupt chunk 1
        val corruptedChunkData = frames[1].data.copyOf()
        corruptedChunkData[0] = (corruptedChunkData[0] + 1).toByte()
        frames[1] = frames[1].copy(data = corruptedChunkData)

        var assembled: ByteArray? = null
        for (chunk in frames) {
            val res = assembler.receiveChunk("peer-1", chunk)
            if (res != null) assembled = res
        }

        // Checksum mismatch must cause assemblePayload to return null
        assertNull(assembled)
    }

    @Test
    fun `assembler cleans up expired incomplete transfers`() {
        val assembler = PayloadAssembler(assemblyTimeout = Duration.ofSeconds(10))
        val chunker = PayloadChunker(chunkSizeBytes = 1024)

        val payload = Random.nextBytes(3000)
        val frames = chunker.chunk(payload).map { it as ChunkFrame }

        val startTime = Instant.parse("2026-10-26T10:00:00Z")
        assembler.receiveChunk("peer-1", frames[0], now = startTime)
        assertEquals(1, assembler.inFlightCount())

        // 15 seconds later, transfer should expire and be purged
        assembler.cleanupExpired(now = startTime.plusSeconds(15))
        assertEquals(0, assembler.inFlightCount())
    }
}


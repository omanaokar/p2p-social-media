package com.proximity.transport

import com.proximity.transport.framing.AckFrame
import com.proximity.transport.framing.ChunkFrame
import com.proximity.transport.framing.Frame
import com.proximity.transport.framing.FrameCodec
import com.proximity.transport.framing.HeartbeatFrame
import com.proximity.transport.framing.SingleFrame
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FrameCodecTest {

    @Test
    fun `encodes and decodes SingleFrame correctly`() {
        val payload = Random.nextBytes(1024)
        val frame = SingleFrame(payload)

        val encoded = FrameCodec.encode(frame)
        val decoded = FrameCodec.decode(encoded)

        assertNotNull(decoded)
        assertTrue(decoded is SingleFrame)
        assertTrue(payload.contentEquals(decoded.payload))
    }

    @Test
    fun `encodes and decodes ChunkFrame correctly`() {
        val chunkData = Random.nextBytes(4096)
        val frame = ChunkFrame(
            transferId = 123456789L,
            totalPayloadLength = 100000,
            totalChunks = 25,
            chunkIndex = 7,
            totalCrc32 = 987654321L,
            data = chunkData,
        )

        val encoded = FrameCodec.encode(frame)
        val decoded = FrameCodec.decode(encoded)

        assertNotNull(decoded)
        assertTrue(decoded is ChunkFrame)
        assertEquals(frame.transferId, decoded.transferId)
        assertEquals(frame.totalPayloadLength, decoded.totalPayloadLength)
        assertEquals(frame.totalChunks, decoded.totalChunks)
        assertEquals(frame.chunkIndex, decoded.chunkIndex)
        assertEquals(frame.totalCrc32, decoded.totalCrc32)
        assertTrue(chunkData.contentEquals(decoded.data))
    }

    @Test
    fun `encodes and decodes HeartbeatFrame correctly`() {
        val frame = HeartbeatFrame(timestamp = 1700000000L, reportedSignal = -65)
        val encoded = FrameCodec.encode(frame)
        val decoded = FrameCodec.decode(encoded)

        assertNotNull(decoded)
        assertTrue(decoded is HeartbeatFrame)
        assertEquals(1700000000L, decoded.timestamp)
        assertEquals(-65, decoded.reportedSignal)
    }

    @Test
    fun `encodes and decodes AckFrame correctly`() {
        val frame = AckFrame(transferId = 42L, chunkIndex = 3)
        val encoded = FrameCodec.encode(frame)
        val decoded = FrameCodec.decode(encoded)

        assertNotNull(decoded)
        assertTrue(decoded is AckFrame)
        assertEquals(42L, decoded.transferId)
        assertEquals(3, decoded.chunkIndex)
    }

    @Test
    fun `rejects invalid magic byte or corrupted data`() {
        val invalidBytes = byteArrayOf(0x00, 0x01, 0x01, 0x00)
        assertNull(FrameCodec.decode(invalidBytes))

        val emptyBytes = byteArrayOf()
        assertNull(FrameCodec.decode(emptyBytes))
    }
}


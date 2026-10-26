package com.proximity.transport.framing

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

object FrameCodec {

    /**
     * Encodes a [Frame] into a framed byte array with magic byte, version, and headers.
     */
    fun encode(frame: Frame): ByteArray {
        val bos = ByteArrayOutputStream()
        val dos = DataOutputStream(bos)

        dos.writeByte(Frame.MAGIC_BYTE.toInt())
        dos.writeByte(Frame.PROTOCOL_VERSION.toInt())

        when (frame) {
            is SingleFrame -> {
                dos.writeByte(Frame.TYPE_SINGLE.toInt())
                dos.writeInt(frame.payload.size)
                dos.write(frame.payload)
            }
            is ChunkFrame -> {
                dos.writeByte(Frame.TYPE_CHUNK.toInt())
                dos.writeLong(frame.transferId)
                dos.writeInt(frame.totalPayloadLength)
                dos.writeShort(frame.totalChunks)
                dos.writeShort(frame.chunkIndex)
                dos.writeLong(frame.totalCrc32)
                dos.writeInt(frame.data.size)
                dos.write(frame.data)
            }
            is HeartbeatFrame -> {
                dos.writeByte(Frame.TYPE_HEARTBEAT.toInt())
                dos.writeLong(frame.timestamp)
                dos.writeInt(frame.reportedSignal ?: Int.MIN_VALUE)
            }
            is AckFrame -> {
                dos.writeByte(Frame.TYPE_ACK.toInt())
                dos.writeLong(frame.transferId)
                dos.writeShort(frame.chunkIndex)
            }
        }
        dos.flush()
        return bos.toByteArray()
    }

    /**
     * Decodes a byte array into a [Frame].
     * Returns null if the byte array does not start with the magic byte or is invalid.
     */
    fun decode(bytes: ByteArray): Frame? {
        if (bytes.size < 3) return null
        val dis = DataInputStream(ByteArrayInputStream(bytes))
        try {
            val magic = dis.readByte()
            if (magic != Frame.MAGIC_BYTE) return null

            val version = dis.readByte()
            if (version != Frame.PROTOCOL_VERSION) return null

            return when (val type = dis.readByte()) {
                Frame.TYPE_SINGLE -> {
                    val length = dis.readInt()
                    if (length < 0 || dis.available() < length) return null
                    val payload = ByteArray(length)
                    dis.readFully(payload)
                    SingleFrame(payload)
                }
                Frame.TYPE_CHUNK -> {
                    val transferId = dis.readLong()
                    val totalLength = dis.readInt()
                    val totalChunks = dis.readUnsignedShort()
                    val chunkIndex = dis.readUnsignedShort()
                    val totalCrc32 = dis.readLong()
                    val dataLength = dis.readInt()
                    if (dataLength < 0 || dis.available() < dataLength) return null
                    val data = ByteArray(dataLength)
                    dis.readFully(data)
                    ChunkFrame(
                        transferId = transferId,
                        totalPayloadLength = totalLength,
                        totalChunks = totalChunks,
                        chunkIndex = chunkIndex,
                        totalCrc32 = totalCrc32,
                        data = data,
                    )
                }
                Frame.TYPE_HEARTBEAT -> {
                    val timestamp = dis.readLong()
                    val signal = dis.readInt()
                    HeartbeatFrame(
                        timestamp = timestamp,
                        reportedSignal = if (signal == Int.MIN_VALUE) null else signal,
                    )
                }
                Frame.TYPE_ACK -> {
                    val transferId = dis.readLong()
                    val chunkIndex = dis.readUnsignedShort()
                    AckFrame(transferId = transferId, chunkIndex = chunkIndex)
                }
                else -> null
            }
        } catch (_: IOException) {
            return null
        }
    }
}


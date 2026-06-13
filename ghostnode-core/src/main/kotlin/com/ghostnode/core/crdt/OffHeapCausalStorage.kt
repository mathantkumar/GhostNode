package com.ghostnode.core.crdt

import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Off-heap storage for CausalOperation objects using Direct ByteBuffers.
 * Minimizes GC cycles in high-concurrency environments by caching logs off-heap.
 */
class OffHeapCausalStorage(
    val capacityBytes: Int = 5 * 1024 * 1024 // 5 MB off-heap capacity
) {
    private val buffer: ByteBuffer = ByteBuffer.allocateDirect(capacityBytes)
    private val index = ConcurrentHashMap<String, IntRange>()
    private var writePointer = 0

    /**
     * Serializes and writes a causal operation into the direct ByteBuffer.
     */
    @Synchronized
    fun put(op: CausalOperation<String>) {
        val jsonStr = Json.encodeToString(op)
        val bytes = jsonStr.toByteArray(Charsets.UTF_8)

        if (writePointer + bytes.size > capacityBytes) {
            throw IllegalStateException("Off-heap storage capacity exceeded ($capacityBytes bytes)")
        }

        buffer.position(writePointer)
        buffer.put(bytes)
        
        index[op.id] = writePointer until (writePointer + bytes.size)
        writePointer += bytes.size
    }

    /**
     * Reads, deserializes, and returns a causal operation from the direct ByteBuffer.
     */
    fun get(id: String): CausalOperation<String>? {
        val range = index[id] ?: return null
        val length = range.last - range.first + 1
        val bytes = ByteArray(length)

        synchronized(buffer) {
            buffer.position(range.first)
            buffer.get(bytes)
        }

        val jsonStr = String(bytes, Charsets.UTF_8)
        return Json.decodeFromString<CausalOperation<String>>(jsonStr)
    }

    /**
     * Lists all operation IDs currently stored off-heap.
     */
    fun keys(): Set<String> = index.keys

    /**
     * Returns the current number of bytes written to the buffer.
     */
    fun getBytesUsed(): Int = writePointer
}

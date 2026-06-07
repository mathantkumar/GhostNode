package com.ghostnode.core.clock

import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

// Causal ordering relationship between two vector clocks.
enum class CausalOrder {
    BEFORE,
    AFTER,
    CONCURRENT,
    EQUAL
}

object VectorClockSerializer : KSerializer<VectorClock> {
    override val descriptor: SerialDescriptor = VectorClockSurrogate.serializer().descriptor

    override fun serialize(encoder: Encoder, value: VectorClock) {
        val surrogate = VectorClockSurrogate(value.entries, value.lastSeen)
        encoder.encodeSerializableValue(VectorClockSurrogate.serializer(), surrogate)
    }

    override fun deserialize(decoder: Decoder): VectorClock {
        val surrogate = decoder.decodeSerializableValue(VectorClockSurrogate.serializer())
        return VectorClock(
            entries = surrogate.entries.toPersistentMap(),
            lastSeen = surrogate.lastSeen.toPersistentMap()
        )
    }
}

@Serializable
private data class VectorClockSurrogate(
    val entries: Map<String, Long>,
    val lastSeen: Map<String, Long> = emptyMap()
)

// An immutable vector clock for causality tracking in distributed systems.
@Serializable(with = VectorClockSerializer::class)
data class VectorClock(
    val entries: PersistentMap<String, Long> = persistentMapOf(),
    val lastSeen: PersistentMap<String, Long> = persistentMapOf()
) : Comparable<VectorClock> {

    // Returns the logical counter for [nodeId], defaulting to 0 if not present.
    operator fun get(nodeId: String): Long = entries[nodeId] ?: 0L

    // Bumps the logical counter for [nodeId] and returns a new [VectorClock].
    fun increment(nodeId: String, now: Long = System.currentTimeMillis()): VectorClock {
        return VectorClock(
            entries = entries.put(nodeId, this[nodeId] + 1),
            lastSeen = lastSeen.put(nodeId, now)
        )
    }

    // Merges this clock with another by taking the element-wise maximum of all counters.
    fun merge(other: VectorClock): VectorClock {
        val allNodeIds = entries.keys + other.entries.keys
        val mergedEntries = allNodeIds.associateWith { nodeId -> maxOf(this[nodeId], other[nodeId]) }
        val mergedLastSeen = allNodeIds.associateWith { nodeId ->
            maxOf(this.lastSeen[nodeId] ?: 0L, other.lastSeen[nodeId] ?: 0L)
        }
        return VectorClock(mergedEntries.toPersistentMap(), mergedLastSeen.toPersistentMap())
    }

    // Prunes nodes from the vector clock that have not been seen in thresholdMs.
    fun prune(thresholdMs: Long, now: Long = System.currentTimeMillis()): VectorClock {
        val activeEntries = entries.filterKeys { nodeId ->
            val lastSeenTime = lastSeen[nodeId] ?: 0L
            now - lastSeenTime <= thresholdMs
        }
        val activeLastSeen = lastSeen.filterKeys { nodeId ->
            val lastSeenTime = lastSeen[nodeId] ?: 0L
            now - lastSeenTime <= thresholdMs
        }
        return VectorClock(activeEntries.toPersistentMap(), activeLastSeen.toPersistentMap())
    }

    // Determines the relationship ([CausalOrder]) between this clock and [other].
    fun relationTo(other: VectorClock): CausalOrder {
        val allNodeIds = entries.keys + other.entries.keys

        var thisHasGreater = false
        var otherHasGreater = false

        for (nodeId in allNodeIds) {
            val thisValue = this[nodeId]
            val otherValue = other[nodeId]
            when {
                thisValue > otherValue -> thisHasGreater = true
                otherValue > thisValue -> otherHasGreater = true
            }
            if (thisHasGreater && otherHasGreater) return CausalOrder.CONCURRENT
        }

        return when {
            !thisHasGreater && !otherHasGreater -> CausalOrder.EQUAL
            otherHasGreater -> CausalOrder.BEFORE
            else -> CausalOrder.AFTER
        }
    }

    // Compares two vector clocks. Throws if the clocks are [CausalOrder.CONCURRENT].
    override fun compareTo(other: VectorClock): Int =
        when (relationTo(other)) {
            CausalOrder.BEFORE -> -1
            CausalOrder.AFTER -> 1
            CausalOrder.EQUAL -> 0
            CausalOrder.CONCURRENT ->
                throw IllegalStateException(
                    "Cannot compare concurrent vector clocks. Use relationTo() instead."
                )
        }

    override fun toString(): String =
        entries.entries.joinToString(prefix = "VClock{", postfix = "}") { (k, v) -> "$k:$v" }
}


package com.ghostnode.core.clock

import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentMap

// Causal ordering relationship between two vector clocks.
enum class CausalOrder {
    BEFORE,
    AFTER,
    CONCURRENT,
    EQUAL
}

// An immutable vector clock for causality tracking in distributed systems.

data class VectorClock(val entries: PersistentMap<String, Long> = persistentMapOf()) :
        Comparable<VectorClock> {

    // Returns the logical counter for [nodeId], defaulting to 0 if not present.

    operator fun get(nodeId: String): Long = entries[nodeId] ?: 0L

    // Bumps the logical counter for [nodeId] and returns a new [VectorClock].

    fun increment(nodeId: String): VectorClock = VectorClock(entries.put(nodeId, this[nodeId] + 1))

    // Merges this clock with another by taking the element-wise maximum of all counters.

    fun merge(other: VectorClock): VectorClock {
        val allNodeIds = entries.keys + other.entries.keys
        val merged = allNodeIds.associateWith { nodeId -> maxOf(this[nodeId], other[nodeId]) }
        return VectorClock(merged.toPersistentMap())
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

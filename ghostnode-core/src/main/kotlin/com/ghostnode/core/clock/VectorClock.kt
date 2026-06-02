package com.ghostnode.core.clock

import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentMap

/**
 * Represents the causal relationship between two [VectorClock] instances.
 *
 * Vector clocks define a **partial order** — two clocks may be [CONCURRENT]
 * when neither causally precedes the other.
 */
enum class CausalOrder {
    /** This clock happened before the other. */
    BEFORE,

    /** This clock happened after the other. */
    AFTER,

    /** The two clocks are causally concurrent (no ordering). */
    CONCURRENT,

    /** The two clocks are identical. */
    EQUAL
}

/**
 * An immutable vector clock for causality tracking in distributed systems.
 *
 * Each node in the system is identified by a [String] node ID, and maintains
 * a monotonically increasing logical counter. Every mutation returns a **new**
 * [VectorClock] instance — the original is never modified.
 *
 * Backed by [PersistentMap] from `kotlinx-collections-immutable` to ensure
 * structural sharing and efficient copy-on-write semantics.
 *
 * ## Usage
 * ```kotlin
 * val clock = VectorClock()
 *     .increment("node-a")
 *     .increment("node-a")
 *     .increment("node-b")
 *
 * val other = VectorClock()
 *     .increment("node-b")
 *     .increment("node-b")
 *
 * val merged = clock.merge(other)
 * // merged["node-a"] == 2, merged["node-b"] == 2
 * ```
 */
data class VectorClock(
    val entries: PersistentMap<String, Long> = persistentMapOf()
) : Comparable<VectorClock> {

    /**
     * Returns the logical time for the given [nodeId], or `0` if absent.
     */
    operator fun get(nodeId: String): Long = entries[nodeId] ?: 0L

    /**
     * Increments the logical counter for [nodeId] and returns a new clock.
     *
     * @param nodeId the identifier of the node whose counter should be bumped.
     * @return a new [VectorClock] with the incremented counter.
     */
    fun increment(nodeId: String): VectorClock =
        VectorClock(entries.put(nodeId, this[nodeId] + 1))

    /**
     * Merges this clock with [other] by taking the element-wise maximum
     * of every node's counter.
     *
     * The merge operation is:
     * - **Commutative**: `a.merge(b) == b.merge(a)`
     * - **Associative**: `a.merge(b).merge(c) == a.merge(b.merge(c))`
     * - **Idempotent**: `a.merge(a) == a`
     *
     * @return a new [VectorClock] representing the least upper bound.
     */
    fun merge(other: VectorClock): VectorClock {
        val allNodeIds = entries.keys + other.entries.keys
        val merged = allNodeIds.associateWith { nodeId ->
            maxOf(this[nodeId], other[nodeId])
        }
        return VectorClock(merged.toPersistentMap())
    }

    /**
     * Determines the [CausalOrder] relationship between this clock and [other].
     *
     * - [CausalOrder.BEFORE]: every counter in `this` ≤ the corresponding
     *   counter in [other], and at least one is strictly less.
     * - [CausalOrder.AFTER]: the inverse of BEFORE.
     * - [CausalOrder.EQUAL]: all counters are identical.
     * - [CausalOrder.CONCURRENT]: neither clock dominates the other.
     */
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
            // Short-circuit: if both sides already dominate on some entry,
            // the clocks are necessarily concurrent.
            if (thisHasGreater && otherHasGreater) return CausalOrder.CONCURRENT
        }

        return when {
            !thisHasGreater && !otherHasGreater -> CausalOrder.EQUAL
            otherHasGreater -> CausalOrder.BEFORE
            else -> CausalOrder.AFTER
        }
    }

    /**
     * Compares this clock to [other] using the causal partial order.
     *
     * @return a negative value if this is [CausalOrder.BEFORE], zero if
     *   [CausalOrder.EQUAL], or a positive value if [CausalOrder.AFTER].
     * @throws IllegalStateException if the clocks are [CausalOrder.CONCURRENT],
     *   since concurrent clocks have no total ordering.
     */
    override fun compareTo(other: VectorClock): Int = when (relationTo(other)) {
        CausalOrder.BEFORE -> -1
        CausalOrder.AFTER -> 1
        CausalOrder.EQUAL -> 0
        CausalOrder.CONCURRENT -> throw IllegalStateException(
            "Cannot compare concurrent vector clocks. " +
                "Use relationTo() for partial-order comparisons."
        )
    }

    override fun toString(): String =
        entries.entries.joinToString(prefix = "VClock{", postfix = "}") { (k, v) -> "$k:$v" }
}

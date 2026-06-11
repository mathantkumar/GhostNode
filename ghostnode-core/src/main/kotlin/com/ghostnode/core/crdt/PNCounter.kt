package com.ghostnode.core.crdt

import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.serialization.Serializable

/**
 * An immutable state-based Positive-Negative Counter CRDT.
 * Guarantees convergence of increments and decrements across node replicas.
 */
@Serializable
data class PNCounter(
    val p: PersistentMap<String, Long> = persistentMapOf(),
    val n: PersistentMap<String, Long> = persistentMapOf()
) {
    /**
     * Computes the current counter value.
     */
    val value: Long
        get() = p.values.sum() - n.values.sum()

    /**
     * Increments the counter for [nodeId] by [delta].
     */
    fun increment(nodeId: String, delta: Long = 1L): PNCounter {
        require(delta >= 0L) { "Delta must be non-negative" }
        val current = p[nodeId] ?: 0L
        return copy(p = p.put(nodeId, current + delta))
    }

    /**
     * Decrements the counter for [nodeId] by [delta].
     */
    fun decrement(nodeId: String, delta: Long = 1L): PNCounter {
        require(delta >= 0L) { "Delta must be non-negative" }
        val current = n[nodeId] ?: 0L
        return copy(n = n.put(nodeId, current + delta))
    }

    /**
     * Merges this counter state with [other] by taking the entry-wise maximum.
     * This operation is commutative, associative, and idempotent.
     */
    fun merge(other: PNCounter): PNCounter {
        var mergedP = p
        for ((node, count) in other.p) {
            val current = mergedP[node] ?: 0L
            mergedP = mergedP.put(node, maxOf(current, count))
        }

        var mergedN = n
        for ((node, count) in other.n) {
            val current = mergedN[node] ?: 0L
            mergedN = mergedN.put(node, maxOf(current, count))
        }

        return PNCounter(mergedP, mergedN)
    }
}

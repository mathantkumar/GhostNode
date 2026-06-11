package com.ghostnode.core.crdt

import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.serialization.Serializable

@Serializable
enum class MapOperationType {
    PUT, REMOVE
}

/**
 * Represents a single key-value mutation in the OR-Map causal log.
 */
@Serializable
data class MapOperation<K, V>(
    val id: String,
    val type: MapOperationType,
    val key: K,
    val value: V?,
    val timestamp: Long,
    val dependencies: Set<String>
)

/**
 * An immutable Observed-Remove Map (OR-Map) CRDT.
 * Tracks key-value mutations via operational logs to converge divergent mappings.
 */
@Serializable
data class ORMap<K, V>(
    val operations: PersistentMap<String, MapOperation<K, V>> = persistentMapOf()
) {
    /**
     * Associates [value] with [key] on [nodeId], recording current causal dependencies.
     */
    fun put(nodeId: String, key: K, value: V): ORMap<K, V> {
        val nextSeq = operations.keys.filter { it.startsWith("$nodeId:") }.size + 1
        val opId = "$nodeId:$nextSeq"
        val deps = operations.keys.toSet()

        val op = MapOperation<K, V>(
            id = opId,
            type = MapOperationType.PUT,
            key = key,
            value = value,
            timestamp = System.currentTimeMillis(),
            dependencies = deps
        )
        return copy(operations = operations.put(opId, op))
    }

    /**
     * Removes the mapping for [key] on [nodeId], recording dependencies.
     */
    fun remove(nodeId: String, key: K): ORMap<K, V> {
        val nextSeq = operations.keys.filter { it.startsWith("$nodeId:") }.size + 1
        val opId = "$nodeId:$nextSeq"
        val deps = operations.keys.toSet()

        val op = MapOperation<K, V>(
            id = opId,
            type = MapOperationType.REMOVE,
            key = key,
            value = null,
            timestamp = System.currentTimeMillis(),
            dependencies = deps
        )
        return copy(operations = operations.put(opId, op))
    }

    /**
     * Retrieves the converged value associated with [key].
     */
    fun get(key: K): V? {
        val puts = operations.values.filter { it.type == MapOperationType.PUT && it.key == key }
        if (puts.isEmpty()) return null

        val removes = operations.values
            .filter { it.type == MapOperationType.REMOVE && it.key == key }
            .flatMap { it.dependencies }
            .toSet()

        // Active puts are those not targeted by any remove dependencies
        val activePuts = puts.filter { it.id !in removes }
        if (activePuts.isEmpty()) return null

        // Resolve concurrent PUTs deterministically using timestamp, with ID as a tie-breaker
        return activePuts.maxWithOrNull(compareBy({ it.timestamp }, { it.id }))?.value
    }

    /**
     * Returns the set of all keys currently present in the map.
     */
    fun keys(): Set<K> {
        return operations.values
            .filter { it.type == MapOperationType.PUT }
            .map { it.key }
            .filter { get(it) != null }
            .toSet()
    }

    /**
     * Merges this map state with [other] by computing the union of operation logs.
     */
    fun merge(other: ORMap<K, V>): ORMap<K, V> {
        return copy(operations = operations.putAll(other.operations))
    }
}

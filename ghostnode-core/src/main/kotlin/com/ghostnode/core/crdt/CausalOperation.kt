package com.ghostnode.core.crdt

import kotlinx.serialization.Serializable

@Serializable
enum class OperationType {
    ADD, REMOVE
}

/**
 * Represents a single mutation operation in the causal history graph.
 */
@Serializable
data class CausalOperation<E>(
    val id: String,                  // Unique identifier, e.g. "client-a:1"
    val type: OperationType,
    val element: E,
    val timestamp: Long,            // Wall-clock time of creation
    val dependencies: Set<String>    // Identifiers of operations observed by the creator
)

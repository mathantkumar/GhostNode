package com.ghostnode.core.crdt

import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

class CausalLedgerSerializer<E>(
    elementSerializer: KSerializer<E>
) : KSerializer<CausalLedger<E>> {

    private val delegateSerializer = CausalLedgerSurrogate.serializer(elementSerializer)

    override val descriptor: SerialDescriptor = delegateSerializer.descriptor

    override fun serialize(encoder: Encoder, value: CausalLedger<E>) {
        val surrogate = CausalLedgerSurrogate(value.operations.values.toList())
        encoder.encodeSerializableValue(delegateSerializer, surrogate)
    }

    override fun deserialize(decoder: Decoder): CausalLedger<E> {
        val surrogate = decoder.decodeSerializableValue(delegateSerializer)
        val opsMap = surrogate.operations.associateBy { it.id }.toPersistentMap()
        return CausalLedger(opsMap)
    }
}

@Serializable
private data class CausalLedgerSurrogate<E>(
    val operations: List<CausalOperation<E>>
)

/**
 * An immutable CRDT representing a Causal History Operation Log (Op-Log/OR-Set).
 */
@Serializable(with = CausalLedgerSerializer::class)
data class CausalLedger<E>(
    val operations: PersistentMap<String, CausalOperation<E>> = persistentMapOf()
) {

    /**
     * Records a new mutation operation into the ledger.
     */
    fun applyOperation(op: CausalOperation<E>): CausalLedger<E> {
        if (operations.containsKey(op.id)) return this
        return copy(operations = operations.put(op.id, op))
    }

    /**
     * Checks if [element] is currently active in the set based on Observed-Remove logic.
     * An element is present if there is an ADD operation that has not been causally removed.
     */
    fun lookup(element: E): Boolean {
        val adds = operations.values.filter { it.type == OperationType.ADD && it.element == element }
        if (adds.isEmpty()) return false

        // Collect all predecessor operations targeted by REMOVE operations in the ledger
        val removedIds = operations.values
            .filter { it.type == OperationType.REMOVE && it.element == element }
            .flatMap { it.dependencies }
            .toSet()

        // Element is active if any ADD id is not contained in the set of removed IDs
        return adds.any { it.id !in removedIds }
    }

    /**
     * Returns the set of all active elements.
     */
    fun elements(): Set<E> =
        operations.values
            .filter { it.type == OperationType.ADD }
            .map { it.element }
            .filterTo(mutableSetOf()) { lookup(it) }

    /**
     * Merges this ledger with [other] by unioning the set of operation logs.
     * This operation is commutative, associative, and idempotent.
     */
    fun merge(other: CausalLedger<E>): CausalLedger<E> {
        val mergedOps = operations.putAll(other.operations)
        return CausalLedger(mergedOps)
    }
}

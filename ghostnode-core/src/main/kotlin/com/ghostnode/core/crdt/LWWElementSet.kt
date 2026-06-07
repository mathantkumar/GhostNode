package com.ghostnode.core.crdt

import com.ghostnode.core.clock.CausalOrder
import com.ghostnode.core.clock.VectorClock
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import org.slf4j.LoggerFactory

class LWWElementSetSerializer<E>(
    elementSerializer: KSerializer<E>
) : KSerializer<LWWElementSet<E>> {

    private val delegateSerializer = LWWElementSetSurrogate.serializer(elementSerializer)

    override val descriptor: SerialDescriptor = delegateSerializer.descriptor

    override fun serialize(encoder: Encoder, value: LWWElementSet<E>) {
        val surrogate = LWWElementSetSurrogate(
            addSet = value.addSet,
            removeSet = value.removeSet,
            defaultBias = value.defaultBias
        )
        encoder.encodeSerializableValue(delegateSerializer, surrogate)
    }

    override fun deserialize(decoder: Decoder): LWWElementSet<E> {
        val surrogate = decoder.decodeSerializableValue(delegateSerializer)
        return LWWElementSet(
            addSet = surrogate.addSet.toPersistentMap(),
            removeSet = surrogate.removeSet.toPersistentMap(),
            defaultBias = surrogate.defaultBias
        )
    }
}

@Serializable
private data class LWWElementSetSurrogate<E>(
    val addSet: Map<E, LWWRegister<E>>,
    val removeSet: Map<E, LWWRegister<E>>,
    val defaultBias: LWWElementSet.Bias = LWWElementSet.Bias.ADD
)

/**
 * An immutable **LWW-Element-Set** (Last-Writer-Wins Element Set) CRDT.
 */
@Serializable(with = LWWElementSetSerializer::class)
data class LWWElementSet<E>(
    val addSet: PersistentMap<E, LWWRegister<E>> = persistentMapOf(),
    val removeSet: PersistentMap<E, LWWRegister<E>> = persistentMapOf(),
    val defaultBias: Bias = Bias.ADD,
    @Transient
    val changeListeners: List<(LWWElementSet<E>) -> Unit> = emptyList()
) {

    companion object {
        private val logger = LoggerFactory.getLogger(LWWElementSet::class.java)
    }

    enum class Bias {
        ADD,
        REMOVE
    }

    /**
     * Registers a listener to be notified when state updates or merges.
     */
    fun addListener(listener: (LWWElementSet<E>) -> Unit): LWWElementSet<E> {
        return copy(changeListeners = changeListeners + listener)
    }

    private fun triggerChange() {
        changeListeners.forEach { it(this) }
    }

    /**
     * Adds [element] to this set with the given [timestamp] and causal [clock].
     */
    fun add(element: E, timestamp: Long, clock: VectorClock): LWWElementSet<E> {
        val existing = addSet[element]
        val result = if (existing != null && existing.timestamp >= timestamp) {
            this
        } else {
            copy(addSet = addSet.put(element, LWWRegister(element, timestamp, clock)))
        }
        result.triggerChange()
        return result
    }

    /**
     * Removes [element] from this set with the given [timestamp] and causal [clock].
     */
    fun remove(element: E, timestamp: Long, clock: VectorClock): LWWElementSet<E> {
        val existing = removeSet[element]
        val result = if (existing != null && existing.timestamp >= timestamp) {
            this
        } else {
            copy(removeSet = removeSet.put(element, LWWRegister(element, timestamp, clock)))
        }
        result.triggerChange()
        return result
    }

    /**
     * Checks whether [element] is present in this set.
     */
    fun lookup(element: E, bias: Bias = defaultBias): Boolean {
        val addEntry = addSet[element] ?: return false
        val removeEntry = removeSet[element] ?: return true

        return when {
            addEntry.timestamp > removeEntry.timestamp -> true
            addEntry.timestamp < removeEntry.timestamp -> false
            else -> bias == Bias.ADD
        }
    }

    /**
     * Returns all elements currently present in this set.
     */
    fun elements(bias: Bias = defaultBias): Set<E> =
        addSet.keys.filterTo(mutableSetOf()) { lookup(it, bias) }

    /**
     * Merges this set with [other].
     */
    fun merge(other: LWWElementSet<E>): LWWElementSet<E> {
        val startTime = System.nanoTime()
        logger.debug("Starting merge of LWWElementSet. Local size: {}, Remote size: {}", addSet.size + removeSet.size, other.addSet.size + other.removeSet.size)

        val mergedAdds = mergeMaps(this.addSet, other.addSet)
        val mergedRemoves = mergeMaps(this.removeSet, other.removeSet)

        val durationMs = (System.nanoTime() - startTime) / 1_000_000
        GhostNodeMetrics.recordMergeDuration(durationMs)
        GhostNodeMetrics.reportStateSize(mergedAdds.size + mergedRemoves.size)

        logger.debug("Merge completed in {} ms. Resolved size: {}", durationMs, mergedAdds.size + mergedRemoves.size)

        // Inherit listeners from local state
        val mergedSet = LWWElementSet(mergedAdds, mergedRemoves, defaultBias, changeListeners)
        mergedSet.triggerChange()
        return mergedSet
    }

    /**
     * Tombstone compaction: prunes removed elements that have expired (timestamp < now - thresholdMs)
     * and are no longer active.
     */
    fun compact(thresholdMs: Long, now: Long = System.currentTimeMillis()): LWWElementSet<E> {
        logger.debug("Running tombstone compaction with threshold {} ms", thresholdMs)
        var newAddSet = addSet
        var newRemoveSet = removeSet

        for ((element, removeReg) in removeSet) {
            if (removeReg.timestamp < now - thresholdMs) {
                val addReg = addSet[element]
                if (addReg == null || addReg.timestamp <= removeReg.timestamp) {
                    logger.trace("Compacting/pruning element: {}", element)
                    newAddSet = newAddSet.remove(element)
                    newRemoveSet = newRemoveSet.remove(element)
                }
            }
        }
        val compactedSet = LWWElementSet(newAddSet, newRemoveSet, defaultBias, changeListeners)
        compactedSet.triggerChange()
        return compactedSet
    }

    private fun mergeMaps(
        left: PersistentMap<E, LWWRegister<E>>,
        right: PersistentMap<E, LWWRegister<E>>
    ): PersistentMap<E, LWWRegister<E>> {
        val allKeys = left.keys + right.keys
        val merged = allKeys.associateWith { key ->
            val leftEntry = left[key]
            val rightEntry = right[key]
            when {
                leftEntry == null -> rightEntry!!
                rightEntry == null -> leftEntry
                leftEntry.timestamp > rightEntry.timestamp -> leftEntry
                rightEntry.timestamp > leftEntry.timestamp -> rightEntry
                else -> {
                    // Equal timestamps - resolve conflict
                    logger.debug("Conflict resolved at equal timestamps for key: {}", key)
                    GhostNodeMetrics.incrementConflictsResolved()
                    when (leftEntry.clock.relationTo(rightEntry.clock)) {
                        CausalOrder.AFTER -> leftEntry
                        CausalOrder.BEFORE -> rightEntry
                        CausalOrder.CONCURRENT, CausalOrder.EQUAL -> leftEntry
                    }
                }
            }
        }
        return merged.toPersistentMap()
    }

    override fun toString(): String =
        "LWWElementSet(elements=${elements()})"
}


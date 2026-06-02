package com.ghostnode.core.crdt

import com.ghostnode.core.clock.CausalOrder
import com.ghostnode.core.clock.VectorClock
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentMap

/**
 * An immutable **LWW-Element-Set** (Last-Writer-Wins Element Set) CRDT,
 * following the specification from Shapiro et al.
 *
 * The data structure maintains two internal sets:
 * - [addSet]: records the latest "add" timestamp for each element.
 * - [removeSet]: records the latest "remove" timestamp for each element.
 *
 * An element is considered **present** in the set if its add-timestamp is
 * strictly greater than its remove-timestamp. Ties are broken according to
 * the configured [Bias].
 *
 * All operations return a **new** [LWWElementSet] — the original is never
 * mutated. Internal maps are backed by [PersistentMap] for efficient
 * structural sharing.
 *
 * ## Properties (merge)
 * - **Commutative**: `a.merge(b) == b.merge(a)`
 * - **Associative**: `a.merge(b).merge(c) == a.merge(b.merge(c))`
 * - **Idempotent**: `a.merge(a) == a`
 *
 * ## Usage
 * ```kotlin
 * val clock = VectorClock().increment("node-1")
 * var set = LWWElementSet<String>()
 *     .add("alice", timestamp = 1, clock = clock)
 *     .add("bob",   timestamp = 2, clock = clock.increment("node-1"))
 *
 * set.elements() // {"alice", "bob"}
 *
 * set = set.remove("alice", timestamp = 3, clock = clock.increment("node-1"))
 * set.elements() // {"bob"}
 * ```
 *
 * @param E the element type.
 * @property addSet per-element add registers, keyed by element.
 * @property removeSet per-element remove registers, keyed by element.
 */
data class LWWElementSet<E>(
    val addSet: PersistentMap<E, LWWRegister<E>> = persistentMapOf(),
    val removeSet: PersistentMap<E, LWWRegister<E>> = persistentMapOf()
) {

    /**
     * Controls tie-breaking when an element's add and remove timestamps
     * are exactly equal.
     */
    enum class Bias {
        /** Element is considered present on timestamp tie. */
        ADD,

        /** Element is considered absent on timestamp tie. */
        REMOVE
    }

    /**
     * Adds [element] to this set with the given [timestamp] and causal [clock].
     *
     * If the element already has an add-entry with a higher or equal timestamp,
     * the existing entry is preserved (last-writer-wins).
     *
     * @return a new [LWWElementSet] reflecting the add operation.
     */
    fun add(element: E, timestamp: Long, clock: VectorClock): LWWElementSet<E> {
        val existing = addSet[element]
        return if (existing != null && existing.timestamp >= timestamp) {
            this
        } else {
            copy(addSet = addSet.put(element, LWWRegister(element, timestamp, clock)))
        }
    }

    /**
     * Removes [element] from this set with the given [timestamp] and causal [clock].
     *
     * If the element already has a remove-entry with a higher or equal timestamp,
     * the existing entry is preserved (last-writer-wins).
     *
     * @return a new [LWWElementSet] reflecting the remove operation.
     */
    fun remove(element: E, timestamp: Long, clock: VectorClock): LWWElementSet<E> {
        val existing = removeSet[element]
        return if (existing != null && existing.timestamp >= timestamp) {
            this
        } else {
            copy(removeSet = removeSet.put(element, LWWRegister(element, timestamp, clock)))
        }
    }

    /**
     * Checks whether [element] is present in this set according to
     * the LWW semantics and the specified [bias].
     *
     * @param element the element to look up.
     * @param bias controls tie-breaking when timestamps are equal.
     * @return `true` if the element is considered present.
     */
    fun lookup(element: E, bias: Bias = Bias.ADD): Boolean {
        val addEntry = addSet[element] ?: return false
        val removeEntry = removeSet[element] ?: return true

        return when {
            addEntry.timestamp > removeEntry.timestamp -> true
            addEntry.timestamp < removeEntry.timestamp -> false
            // Timestamps equal — use bias
            else -> bias == Bias.ADD
        }
    }

    /**
     * Returns all elements currently present in this set.
     *
     * @param bias controls tie-breaking when timestamps are equal.
     * @return an immutable [Set] of present elements.
     */
    fun elements(bias: Bias = Bias.ADD): Set<E> =
        addSet.keys.filterTo(mutableSetOf()) { lookup(it, bias) }

    /**
     * Merges this LWW-Element-Set with [other] by taking the entry with the
     * higher timestamp for each element in both the add-set and remove-set.
     *
     * When timestamps are equal, the [VectorClock] causal order is used as
     * a secondary tiebreaker. If the clocks are concurrent, the local entry
     * is preferred (deterministic choice).
     *
     * @return a new [LWWElementSet] representing the merged state.
     */
    fun merge(other: LWWElementSet<E>): LWWElementSet<E> {
        val mergedAdds = mergeMaps(this.addSet, other.addSet)
        val mergedRemoves = mergeMaps(this.removeSet, other.removeSet)
        return LWWElementSet(mergedAdds, mergedRemoves)
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
                // Equal timestamps — use causal order as tiebreaker
                else -> when (leftEntry.clock.relationTo(rightEntry.clock)) {
                    CausalOrder.AFTER -> leftEntry
                    CausalOrder.BEFORE -> rightEntry
                    // CONCURRENT or EQUAL: deterministic choice — prefer left (local)
                    CausalOrder.CONCURRENT, CausalOrder.EQUAL -> leftEntry
                }
            }
        }
        return merged.toPersistentMap()
    }

    override fun toString(): String =
        "LWWElementSet(elements=${elements()})"
}

package com.ghostnode.core.crdt

import com.ghostnode.core.clock.VectorClock

/**
 * An immutable Last-Writer-Wins register that pairs a [value] with its
 * wall-clock [timestamp] and causal [clock] context.
 *
 * Used as the entry type inside [LWWElementSet]'s add-set and remove-set.
 * When two registers for the same element are compared, the one with the
 * higher [timestamp] wins. If timestamps are equal, the [VectorClock]
 * provides a secondary ordering mechanism.
 *
 * @param T the type of the stored value.
 * @property value the replicated value.
 * @property timestamp the wall-clock or hybrid-logical-clock timestamp
 *   at which this value was written.
 * @property clock the [VectorClock] capturing the causal context of this write.
 */
data class LWWRegister<T>(
    val value: T,
    val timestamp: Long,
    val clock: VectorClock
)

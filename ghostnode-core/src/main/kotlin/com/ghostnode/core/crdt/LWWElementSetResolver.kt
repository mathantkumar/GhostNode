package com.ghostnode.core.crdt

import com.ghostnode.core.clock.VectorClock

/**
 * A [ConflictResolver] for [LWWElementSet] that delegates entirely to the
 * set's own [merge][LWWElementSet.merge] operation.
 *
 * Since [LWWElementSet.merge] is already commutative, associative, and
 * idempotent, this resolver inherits all three properties automatically.
 *
 * The [VectorClock] parameters are **intentionally unused** here — the
 * per-element clocks stored inside each [LWWRegister] already provide the
 * causal context needed for conflict resolution. The clock parameters are
 * accepted to satisfy the [ConflictResolver] contract, which is designed
 * for resolvers that need external causal context.
 *
 * ## Usage
 * ```kotlin
 * val resolver = LWWElementSetResolver<String>()
 * val merged = resolver.resolve(localSet, remoteSet, localClock, remoteClock)
 * ```
 *
 * @param E the element type of the LWW-Element-Set.
 */
@Deprecated("Use CausalLedger convergence rules instead")
class LWWElementSetResolver<E> : ConflictResolver<LWWElementSet<E>> {

    override fun resolve(
        local: LWWElementSet<E>,
        remote: LWWElementSet<E>,
        localClock: VectorClock,
        remoteClock: VectorClock
    ): LWWElementSet<E> = local.merge(remote)
}

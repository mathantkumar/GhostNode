package com.ghostnode.core.crdt

import com.ghostnode.core.clock.VectorClock

/**
 * Strategy interface for resolving conflicting replicated state between
 * two nodes in a distributed system.
 *
 * Implementations **MUST** satisfy the following algebraic properties to
 * guarantee convergence across all replicas:
 *
 * - **Commutative**: `resolve(a, b, ca, cb) == resolve(b, a, cb, ca)`
 * - **Idempotent**: `resolve(a, a, c, c) == a`
 * - **Associative**: `resolve(resolve(a, b, ca, cb), c, merge(ca,cb), cc) ==
 *   resolve(a, resolve(b, c, cb, cc), ca, merge(cb,cc))`
 *
 * The [VectorClock] parameters provide causal context, enabling
 * implementations to make ordering decisions beyond simple value comparison.
 *
 * ## Usage
 * ```kotlin
 * // Using SAM-conversion for a simple max-wins resolver:
 * val resolver = ConflictResolver<Int> { local, remote, localClock, remoteClock ->
 *     if (localClock > remoteClock) local else remote
 * }
 * ```
 *
 * @param T the type of the replicated value being resolved.
 */
@Deprecated("Use CausalLedger convergence rules instead")
fun interface ConflictResolver<T> {

    /**
     * Resolves a conflict between [local] and [remote] values.
     *
     * @param local the value held by the local replica.
     * @param remote the value received from the remote replica.
     * @param localClock the [VectorClock] associated with the local value.
     * @param remoteClock the [VectorClock] associated with the remote value.
     * @return the resolved value that all replicas should converge to.
     */
    fun resolve(
        local: T,
        remote: T,
        localClock: VectorClock,
        remoteClock: VectorClock
    ): T
}

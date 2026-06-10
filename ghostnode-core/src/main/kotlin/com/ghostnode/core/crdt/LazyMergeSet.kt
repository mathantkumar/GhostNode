package com.ghostnode.core.crdt

import java.util.concurrent.ConcurrentLinkedQueue

/**
 * A wrapper for [LWWElementSet] that buffers incoming merge states
 * and merges them lazily/on-demand. Thread-safe.
 */
class LazyMergeSet<E>(
    initialState: LWWElementSet<E> = LWWElementSet()
) {
    @Volatile
    private var baseState: LWWElementSet<E> = initialState

    private val pendingMerges = ConcurrentLinkedQueue<LWWElementSet<E>>()

    /**
     * Enqueues a remote state to be merged later.
     */
    fun queueMerge(other: LWWElementSet<E>) {
        pendingMerges.add(other)
    }

    /**
     * Processes any pending merges and returns the up-to-date converged state.
     */
    fun getLatestState(): LWWElementSet<E> {
        if (pendingMerges.isEmpty()) return baseState

        synchronized(this) {
            var current = baseState
            while (true) {
                val next = pendingMerges.poll() ?: break
                current = current.merge(next)
            }
            baseState = current
        }
        return baseState
    }

    fun elements(): Set<E> = getLatestState().elements()

    fun lookup(element: E): Boolean = getLatestState().lookup(element)

    /**
     * Compacts the base state, flushing any pending merges first.
     */
    fun compact(thresholdMs: Long, now: Long = System.currentTimeMillis()) {
        synchronized(this) {
            getLatestState() // flush pending merges first
            baseState = baseState.compact(thresholdMs, now)
        }
    }
}

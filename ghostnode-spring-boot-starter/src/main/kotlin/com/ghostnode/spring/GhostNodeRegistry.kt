package com.ghostnode.spring

import com.ghostnode.core.crdt.LWWElementSet
import com.ghostnode.core.crdt.LazyMergeSet
import java.util.concurrent.ConcurrentHashMap

/**
 * Registry mapping of active and lazy LWWElementSets. Allows central management
 * (compaction/monitoring) of CRDT states in the application.
 */
class GhostNodeRegistry {
    private val activeSets = ConcurrentHashMap<String, LWWElementSet<*>>()
    private val lazySets = ConcurrentHashMap<String, LazyMergeSet<*>>()

    fun register(name: String, set: LWWElementSet<*>) {
        activeSets[name] = set
    }

    fun registerLazy(name: String, set: LazyMergeSet<*>) {
        lazySets[name] = set
    }

    fun get(name: String): LWWElementSet<*>? = activeSets[name]

    fun getLazy(name: String): LazyMergeSet<*>? = lazySets[name]

    fun getAllActive(): Map<String, LWWElementSet<*>> = activeSets

    fun getAllLazy(): Map<String, LazyMergeSet<*>> = lazySets
}

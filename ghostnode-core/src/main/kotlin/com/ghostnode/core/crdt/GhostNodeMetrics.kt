package com.ghostnode.core.crdt

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Metrics
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit

object GhostNodeMetrics {
    private val stateSize = AtomicLong(0)
    private val causalDrift = AtomicLong(0)

    init {
        // Register gauge with the global registry
        Metrics.globalRegistry.gauge("ghostnode.state.size", stateSize)
        Metrics.globalRegistry.gauge("ghostnode.causal.drift", causalDrift)
    }

    /**
     * Configure a custom [MeterRegistry] (e.g. from Spring Boot).
     */
    fun bindTo(registry: MeterRegistry) {
        registry.gauge("ghostnode.state.size", stateSize)
        registry.gauge("ghostnode.causal.drift", causalDrift)
        registry.timer("ghostnode.merge.duration")
        registry.counter("ghostnode.conflicts.resolved")
        Metrics.addRegistry(registry)
    }

    fun recordMergeDuration(timeMs: Long) {
        Metrics.globalRegistry.timer("ghostnode.merge.duration")
            .record(timeMs, TimeUnit.MILLISECONDS)
    }

    fun incrementConflictsResolved() {
        Metrics.globalRegistry.counter("ghostnode.conflicts.resolved").increment()
    }

    fun reportStateSize(size: Int) {
        stateSize.set(size.toLong())
    }

    fun reportCausalDrift(drift: Long) {
        causalDrift.set(drift)
    }
}

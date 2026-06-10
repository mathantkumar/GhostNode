package com.ghostnode.spring

import com.ghostnode.core.clock.VectorClock
import com.ghostnode.core.crdt.LWWElementSet
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Metrics
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.ApplicationListener
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.atomic.AtomicReference

class GhostNodeAutoConfigurationTest {

    companion object {
        val receivedEvent = AtomicReference<GhostNodeMergeEvent<String>?>(null)
    }

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(GhostNodeAutoConfiguration::class.java))

    @AfterEach
    fun cleanUp() {
        Metrics.globalRegistry.clear()
        receivedEvent.set(null)
    }

    @Test
    fun `test auto-configuration loads default beans`() {
        contextRunner.run { context ->
            assertNotNull(context.getBean(GhostNodeRegistry::class.java))
            assertNotNull(context.getBean(GhostNodeEventPublisher::class.java))
            assertNotNull(context.getBean(GhostNodeProperties::class.java))
        }
    }

    @Test
    fun `test config properties override default values`() {
        contextRunner
            .withPropertyValues(
                "ghostnode.bias=REMOVE",
                "ghostnode.compaction.threshold-ms=1000",
                "ghostnode.compaction.auto-enabled=false"
            )
            .run { context ->
                val props = context.getBean(GhostNodeProperties::class.java)
                assertEquals(LWWElementSet.Bias.REMOVE, props.bias)
                assertEquals(1000L, props.compaction.thresholdMs)
                assertEquals(false, props.compaction.autoEnabled)
            }
    }

    @Test
    fun `test micrometer metrics binding when MeterRegistry is available`() {
        val meterRegistry = SimpleMeterRegistry()
        contextRunner
            .withBean(MeterRegistry::class.java, { meterRegistry })
            .run { context ->
                val clock1 = VectorClock().increment("node-1")
                val clock2 = VectorClock().increment("node-2")
                val set1 = LWWElementSet<String>().add("alice", 200L, clock1)
                val set2 = LWWElementSet<String>().add("alice", 200L, clock2) // same timestamp, conflict!
                
                // Perform merge to record duration, size, and conflicts
                set1.merge(set2)

                val timer = meterRegistry.find("ghostnode.merge.duration").timer()
                val counter = meterRegistry.find("ghostnode.conflicts.resolved").counter()
                val gauge = meterRegistry.find("ghostnode.state.size").gauge()

                assertNotNull(gauge)
                assertNotNull(timer)
                assertNotNull(counter)
                
                assertEquals(1.0, counter!!.count())
                assertEquals(1.0, gauge!!.value())
            }
    }

    @Configuration
    open class TestConfig {
        @Bean
        open fun mergeListener() = ApplicationListener<GhostNodeMergeEvent<*>> { event ->
            receivedEvent.set(event as GhostNodeMergeEvent<String>)
        }
    }

    @Test
    fun `test merge publishes native Spring ApplicationEvent`() {
        contextRunner
            .withUserConfiguration(TestConfig::class.java)
            .run { context ->
                val publisher = context.getBean(GhostNodeEventPublisher::class.java)
                val clock = VectorClock()
                val set = LWWElementSet<String>().add("test-item", 100L, clock)
                
                // Triggers merge listener callback
                publisher.publishMerge(set)
                
                val event = receivedEvent.get()
                assertNotNull(event)
                assertEquals(set, event!!.resolvedState)
                assertTrue(event.resolvedState.lookup("test-item"))
            }
    }
}

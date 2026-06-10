package com.ghostnode.spring

import com.ghostnode.core.crdt.GhostNodeMetrics
import com.ghostnode.core.crdt.LWWElementSet
import com.ghostnode.core.crdt.LWWElementSetResolver
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Bean
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy

@AutoConfiguration
@ConditionalOnClass(LWWElementSet::class)
@EnableConfigurationProperties(GhostNodeProperties::class)
class GhostNodeAutoConfiguration {

    private val logger = LoggerFactory.getLogger(GhostNodeAutoConfiguration::class.java)
    private var scheduler: ScheduledExecutorService? = null

    @Autowired
    private lateinit var context: org.springframework.context.ApplicationContext

    @Autowired
    private lateinit var properties: GhostNodeProperties

    @Autowired(required = false)
    fun bindMetrics(meterRegistry: MeterRegistry?) {
        if (meterRegistry != null) {
            logger.info("Injected Spring MeterRegistry: Binding Micrometer metrics for GhostNode.")
            GhostNodeMetrics.bindTo(meterRegistry)
        }
    }

    @Bean
    @ConditionalOnMissingBean
    fun ghostNodeRegistry(): GhostNodeRegistry = GhostNodeRegistry()

    @Bean
    @ConditionalOnMissingBean
    fun <E> lwwElementSetResolver(): LWWElementSetResolver<E> = LWWElementSetResolver()

    @Bean
    @ConditionalOnMissingBean
    fun ghostNodeEventPublisher(publisher: ApplicationEventPublisher): GhostNodeEventPublisher =
        GhostNodeEventPublisher(publisher)

    @PostConstruct
    fun startCompactionScheduler() {
        val config = properties.compaction
        if (config.autoEnabled) {
            logger.info("Starting background CRDT Compaction scheduler. Interval: {} ms, Threshold: {} ms", config.intervalMs, config.thresholdMs)
            val sched = Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "ghostnode-compaction-worker").apply { isDaemon = true }
            }
            scheduler = sched
            sched.scheduleAtFixedRate({
                try {
                    val registry = context.getBean(GhostNodeRegistry::class.java)
                    val now = System.currentTimeMillis()
                    val threshold = config.thresholdMs
                    
                    // Compact Active Sets
                    for ((name, set) in registry.getAllActive()) {
                        @Suppress("UNCHECKED_CAST")
                        val compacted = (set as LWWElementSet<Any>).compact(threshold, now)
                        registry.register(name, compacted)
                    }

                    // Compact Lazy Sets
                    for ((_, lazySet) in registry.getAllLazy()) {
                        lazySet.compact(threshold, now)
                    }
                    logger.debug("Automatic CRDT tombstone compaction executed successfully.")
                } catch (e: Exception) {
                    logger.error("Error occurred during background CRDT tombstone compaction", e)
                }
            }, config.intervalMs, config.intervalMs, TimeUnit.MILLISECONDS)
        }
    }

    @PreDestroy
    fun stopCompactionScheduler() {
        scheduler?.let {
            logger.info("Stopping background CRDT Compaction scheduler.")
            it.shutdown()
            try {
                if (!it.awaitTermination(5, TimeUnit.SECONDS)) {
                    it.shutdownNow()
                }
            } catch (e: InterruptedException) {
                it.shutdownNow()
            }
        }
    }
}

package com.ghostnode.spring

import com.ghostnode.core.crdt.LWWElementSet
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "ghostnode")
class GhostNodeProperties {
    /**
     * Default tie-breaking bias when timestamps are exactly equal.
     * Can be either ADD or REMOVE.
     */
    var bias: LWWElementSet.Bias = LWWElementSet.Bias.ADD

    var compaction = CompactionProperties()
    var clockPruning = ClockPruningProperties()

    class CompactionProperties {
        /**
         * Threshold duration in milliseconds older than which tombstones (deleted items) are pruned.
         * Default is 30 days.
         */
        var thresholdMs: Long = 2_592_000_000L // 30 days

        /**
         * Enable background schedule for running tombstone compaction automatically.
         */
        var autoEnabled: Boolean = true

        /**
         * Interval in milliseconds at which compaction runs.
         * Default is 1 hour.
         */
        var intervalMs: Long = 3_600_000L // 1 hour
    }

    class ClockPruningProperties {
        /**
         * Threshold duration in milliseconds older than which inactive node IDs are pruned from Vector Clocks.
         * Default is 30 days.
         */
        var thresholdMs: Long = 2_592_000_000L // 30 days
    }
}

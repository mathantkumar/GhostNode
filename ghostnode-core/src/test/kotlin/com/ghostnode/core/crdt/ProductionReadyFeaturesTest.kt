package com.ghostnode.core.crdt

import com.ghostnode.core.clock.VectorClock
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProductionReadyFeaturesTest {

    @Test
    fun `test VectorClock schema evolution from v1 to v2`() {
        // Old JSON format with only 'entries' mapping
        val v1Json = """{"entries":{"node-1":5}}"""
        val clock = Json.decodeFromString<VectorClock>(v1Json)
        
        // Should parse successfully and default lastSeen to an empty map
        assertEquals(5L, clock["node-1"])
        assertTrue(clock.lastSeen.isEmpty())
        
        // Increment the clock, adding a lastSeen value
        val incremented = clock.increment("node-1", now = 999L)
        assertEquals(999L, incremented.lastSeen["node-1"])
        
        // Serialize again to make sure it includes the new fields
        val v2Json = Json.encodeToString(VectorClock.serializer(), incremented)
        assertTrue(v2Json.contains("lastSeen"))
    }

    @Test
    fun `test VectorClock pruning`() {
        var clock = VectorClock()
        clock = clock.increment("node-1", now = 1000L)
        clock = clock.increment("node-2", now = 2000L)

        // Prune nodes older than 500ms at now = 2200L
        // node-1 age = 2200 - 1000 = 1200ms (> 500ms) -> pruned
        // node-2 age = 2200 - 2000 = 200ms (<= 500ms) -> kept
        val pruned = clock.prune(thresholdMs = 500L, now = 2200L)

        assertEquals(0L, pruned["node-1"])
        assertEquals(1L, pruned["node-2"])
        assertFalse(pruned.entries.containsKey("node-1"))
        assertTrue(pruned.entries.containsKey("node-2"))
    }

    @Test
    fun `test LWWElementSet tombstone compaction`() {
        val clock = VectorClock()
        var set = LWWElementSet<String>()

        // Add then remove "alice"
        set = set.add("alice", timestamp = 100L, clock = clock)
        set = set.remove("alice", timestamp = 200L, clock = clock)

        // Add "bob" (active)
        set = set.add("bob", timestamp = 150L, clock = clock)

        assertFalse(set.lookup("alice"))
        assertTrue(set.lookup("bob"))

        // Compact with threshold = 50ms at now = 260L
        // alice tombstone age = 260 - 200 = 60ms (> 50ms) -> pruned
        // bob is present -> not pruned
        val compacted = set.compact(thresholdMs = 50L, now = 260L)

        assertFalse(compacted.addSet.containsKey("alice"))
        assertFalse(compacted.removeSet.containsKey("alice"))
        assertTrue(compacted.addSet.containsKey("bob"))
        assertTrue(compacted.lookup("bob"))
    }

    @Test
    fun `test LazyMergeSet lazy merging`() {
        val clock = VectorClock()
        val setA = LWWElementSet<String>().add("item-1", 100L, clock)
        val setB = LWWElementSet<String>().add("item-2", 200L, clock)

        val lazySet = LazyMergeSet(setA)
        assertEquals(setOf("item-1"), lazySet.elements())

        lazySet.queueMerge(setB)
        // Verify it merges on demand when elements() is requested
        assertEquals(setOf("item-1", "item-2"), lazySet.elements())
    }

    @Test
    fun `test change listeners`() {
        var callCount = 0
        val clock = VectorClock()
        var set = LWWElementSet<String>().addListener {
            callCount++
        }

        set = set.add("item-1", 100L, clock)
        assertEquals(1, callCount)

        set = set.remove("item-1", 200L, clock)
        assertEquals(2, callCount)
    }

    @Test
    fun `test LWWElementSet serialization`() {
        val clock = VectorClock().increment("node-1")
        val set = LWWElementSet<String>()
            .add("alice", 100L, clock)
            .remove("bob", 200L, clock)

        val serializer = LWWElementSet.serializer(kotlinx.serialization.serializer<String>())
        val jsonStr = Json.encodeToString(serializer, set)

        val deserialized = Json.decodeFromString(serializer, jsonStr)
        assertEquals(set.elements(), deserialized.elements())
        assertEquals(100L, deserialized.addSet["alice"]?.timestamp)
        assertEquals(200L, deserialized.removeSet["bob"]?.timestamp)
    }
}

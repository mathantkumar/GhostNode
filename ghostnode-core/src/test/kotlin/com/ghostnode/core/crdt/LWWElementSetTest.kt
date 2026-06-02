package com.ghostnode.core.crdt

import com.ghostnode.core.clock.VectorClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LWWElementSetTest {

    private val clock = VectorClock().increment("node-1")

    @Test
    fun `add then lookup returns true`() {
        val set = LWWElementSet<String>()
            .add("alice", timestamp = 1, clock = clock)

        assertTrue(set.lookup("alice"))
    }

    @Test
    fun `lookup on absent element returns false`() {
        val set = LWWElementSet<String>()

        assertFalse(set.lookup("alice"))
    }

    @Test
    fun `remove after add makes element absent`() {
        val set = LWWElementSet<String>()
            .add("alice", timestamp = 1, clock = clock)
            .remove("alice", timestamp = 2, clock = clock.increment("node-1"))

        assertFalse(set.lookup("alice"))
    }

    @Test
    fun `add after remove makes element present again`() {
        val set = LWWElementSet<String>()
            .add("alice", timestamp = 1, clock = clock)
            .remove("alice", timestamp = 2, clock = clock)
            .add("alice", timestamp = 3, clock = clock.increment("node-1"))

        assertTrue(set.lookup("alice"))
    }

    @Test
    fun `remove before add leaves element present`() {
        val set = LWWElementSet<String>()
            .remove("alice", timestamp = 1, clock = clock)
            .add("alice", timestamp = 2, clock = clock.increment("node-1"))

        assertTrue(set.lookup("alice"))
    }

    @Test
    fun `ADD bias keeps element on timestamp tie`() {
        val set = LWWElementSet<String>()
            .add("alice", timestamp = 5, clock = clock)
            .remove("alice", timestamp = 5, clock = clock)

        assertTrue(set.lookup("alice", bias = LWWElementSet.Bias.ADD))
    }

    @Test
    fun `REMOVE bias drops element on timestamp tie`() {
        val set = LWWElementSet<String>()
            .add("alice", timestamp = 5, clock = clock)
            .remove("alice", timestamp = 5, clock = clock)

        assertFalse(set.lookup("alice", bias = LWWElementSet.Bias.REMOVE))
    }

    @Test
    fun `elements returns all present elements`() {
        val set = LWWElementSet<String>()
            .add("alice", timestamp = 1, clock = clock)
            .add("bob", timestamp = 2, clock = clock)
            .add("carol", timestamp = 3, clock = clock)
            .remove("bob", timestamp = 4, clock = clock)

        assertEquals(setOf("alice", "carol"), set.elements())
    }

    @Test
    fun `merge combines two divergent sets`() {
        val clockA = VectorClock().increment("node-a")
        val clockB = VectorClock().increment("node-b")

        val setA = LWWElementSet<String>()
            .add("alice", timestamp = 1, clock = clockA)
            .add("bob", timestamp = 2, clock = clockA)

        val setB = LWWElementSet<String>()
            .add("carol", timestamp = 1, clock = clockB)
            .add("bob", timestamp = 3, clock = clockB) // bob at higher timestamp

        val merged = setA.merge(setB)

        assertEquals(setOf("alice", "bob", "carol"), merged.elements())
        // bob's add register should come from setB (timestamp 3 > 2)
        assertEquals(3L, merged.addSet["bob"]?.timestamp)
    }

    @Test
    fun `merge respects remove timestamps`() {
        val clockA = VectorClock().increment("node-a")
        val clockB = VectorClock().increment("node-b")

        val setA = LWWElementSet<String>()
            .add("alice", timestamp = 1, clock = clockA)

        val setB = LWWElementSet<String>()
            .add("alice", timestamp = 1, clock = clockB)
            .remove("alice", timestamp = 2, clock = clockB)

        val merged = setA.merge(setB)

        assertFalse(merged.lookup("alice"), "alice should be absent — remove at t=2 > add at t=1")
    }

    @Test
    fun `merge is commutative`() {
        val clockA = VectorClock().increment("a")
        val clockB = VectorClock().increment("b")

        val setA = LWWElementSet<String>()
            .add("x", 1, clockA)
            .remove("y", 2, clockA)

        val setB = LWWElementSet<String>()
            .add("y", 3, clockB)
            .add("z", 1, clockB)

        assertEquals(setA.merge(setB).elements(), setB.merge(setA).elements())
    }

    @Test
    fun `merge is idempotent`() {
        val set = LWWElementSet<String>()
            .add("alice", 1, clock)
            .add("bob", 2, clock)
            .remove("alice", 3, clock)

        assertEquals(set.merge(set), set)
    }

    @Test
    fun `merge is associative`() {
        val cA = VectorClock().increment("a")
        val cB = VectorClock().increment("b")
        val cC = VectorClock().increment("c")

        val a = LWWElementSet<String>().add("x", 1, cA)
        val b = LWWElementSet<String>().add("y", 2, cB)
        val c = LWWElementSet<String>().add("z", 3, cC)

        assertEquals(
            a.merge(b).merge(c).elements(),
            a.merge(b.merge(c)).elements()
        )
    }

    @Test
    fun `add with older timestamp is ignored`() {
        val set = LWWElementSet<String>()
            .add("alice", timestamp = 5, clock = clock)
            .add("alice", timestamp = 3, clock = clock) // stale add

        assertEquals(5L, set.addSet["alice"]?.timestamp, "Should keep the newer timestamp")
    }

    @Test
    fun `remove with older timestamp is ignored`() {
        val set = LWWElementSet<String>()
            .remove("alice", timestamp = 5, clock = clock)
            .remove("alice", timestamp = 3, clock = clock) // stale remove

        assertEquals(5L, set.removeSet["alice"]?.timestamp, "Should keep the newer timestamp")
    }

    @Test
    fun `operations do not mutate the original set`() {
        val original = LWWElementSet<String>()
        val afterAdd = original.add("alice", 1, clock)
        val afterRemove = afterAdd.remove("alice", 2, clock)

        assertTrue(original.addSet.isEmpty(), "Original add-set should remain empty")
        assertTrue(original.removeSet.isEmpty(), "Original remove-set should remain empty")
        assertEquals(1, afterAdd.addSet.size)
        assertTrue(afterAdd.removeSet.isEmpty())
        assertEquals(1, afterRemove.removeSet.size)
    }

    @Test
    fun `toString shows current elements`() {
        val set = LWWElementSet<String>()
            .add("alice", 1, clock)
            .add("bob", 2, clock)

        val str = set.toString()
        assert(str.contains("alice")) { "toString should mention alice, got: $str" }
        assert(str.contains("bob")) { "toString should mention bob, got: $str" }
    }
}

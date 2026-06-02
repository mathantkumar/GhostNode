package com.ghostnode.core.clock

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class VectorClockTest {

    @Test
    fun `empty clock has zero for any node`() {
        val clock = VectorClock()
        assertEquals(0L, clock["node-a"])
        assertEquals(0L, clock["nonexistent"])
    }

    @Test
    fun `increment bumps the counter for the given node`() {
        val clock = VectorClock()
            .increment("node-a")
            .increment("node-a")
            .increment("node-b")

        assertEquals(2L, clock["node-a"])
        assertEquals(1L, clock["node-b"])
        assertEquals(0L, clock["node-c"])
    }

    @Test
    fun `increment does not mutate the original clock`() {
        val original = VectorClock()
        val incremented = original.increment("node-a")

        assertEquals(0L, original["node-a"], "Original clock should remain unchanged")
        assertEquals(1L, incremented["node-a"])
    }

    @Test
    fun `merge picks element-wise maximum`() {
        val clockA = VectorClock()
            .increment("node-a") // a:1
            .increment("node-a") // a:2
            .increment("node-b") // b:1

        val clockB = VectorClock()
            .increment("node-b") // b:1
            .increment("node-b") // b:2
            .increment("node-c") // c:1

        val merged = clockA.merge(clockB)

        assertEquals(2L, merged["node-a"], "Should keep max(2, 0) = 2 for node-a")
        assertEquals(2L, merged["node-b"], "Should keep max(1, 2) = 2 for node-b")
        assertEquals(1L, merged["node-c"], "Should keep max(0, 1) = 1 for node-c")
    }

    @Test
    fun `merge is commutative`() {
        val a = VectorClock().increment("x").increment("y")
        val b = VectorClock().increment("y").increment("z")

        assertEquals(a.merge(b), b.merge(a))
    }

    @Test
    fun `merge is idempotent`() {
        val clock = VectorClock().increment("a").increment("b")

        assertEquals(clock, clock.merge(clock))
    }

    @Test
    fun `merge is associative`() {
        val a = VectorClock().increment("x")
        val b = VectorClock().increment("y")
        val c = VectorClock().increment("z")

        assertEquals(a.merge(b).merge(c), a.merge(b.merge(c)))
    }

    @Test
    fun `relationTo returns EQUAL for identical clocks`() {
        val clock = VectorClock().increment("a").increment("b")

        assertEquals(CausalOrder.EQUAL, clock.relationTo(clock))
        assertEquals(CausalOrder.EQUAL, clock.relationTo(clock.merge(clock)))
    }

    @Test
    fun `relationTo returns BEFORE when this is causally before other`() {
        val before = VectorClock().increment("a")          // a:1
        val after = before.increment("a").increment("b")   // a:2, b:1

        assertEquals(CausalOrder.BEFORE, before.relationTo(after))
    }

    @Test
    fun `relationTo returns AFTER when this is causally after other`() {
        val before = VectorClock().increment("a")          // a:1
        val after = before.increment("a").increment("b")   // a:2, b:1

        assertEquals(CausalOrder.AFTER, after.relationTo(before))
    }

    @Test
    fun `relationTo returns CONCURRENT when neither dominates`() {
        val a = VectorClock().increment("node-a")  // a:1, b:0
        val b = VectorClock().increment("node-b")  // a:0, b:1

        assertEquals(CausalOrder.CONCURRENT, a.relationTo(b))
        assertEquals(CausalOrder.CONCURRENT, b.relationTo(a))
    }

    @Test
    fun `compareTo returns negative for BEFORE`() {
        val before = VectorClock().increment("a")
        val after = before.increment("a")

        assert(before < after)
    }

    @Test
    fun `compareTo returns positive for AFTER`() {
        val before = VectorClock().increment("a")
        val after = before.increment("a")

        assert(after > before)
    }

    @Test
    fun `compareTo returns zero for EQUAL`() {
        val clock = VectorClock().increment("a")

        assertEquals(0, clock.compareTo(VectorClock().increment("a")))
    }

    @Test
    fun `compareTo throws on CONCURRENT clocks`() {
        val a = VectorClock().increment("node-a")
        val b = VectorClock().increment("node-b")

        assertFailsWith<IllegalStateException> {
            a.compareTo(b)
        }
    }

    @Test
    fun `toString produces readable output`() {
        val clock = VectorClock().increment("a").increment("b")
        val str = clock.toString()

        assert(str.contains("a:1")) { "toString should include a:1, got: $str" }
        assert(str.contains("b:1")) { "toString should include b:1, got: $str" }
    }
}

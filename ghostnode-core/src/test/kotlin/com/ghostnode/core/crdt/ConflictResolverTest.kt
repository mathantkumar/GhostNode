package com.ghostnode.core.crdt

import com.ghostnode.core.clock.VectorClock
import kotlin.test.Test
import kotlin.test.assertEquals

class ConflictResolverTest {

    @Test
    fun `LWWElementSetResolver is commutative`() {
        val resolver = LWWElementSetResolver<String>()
        val clockA = VectorClock().increment("a")
        val clockB = VectorClock().increment("b")

        val setA = LWWElementSet<String>()
            .add("x", 1, clockA)
            .add("y", 2, clockA)

        val setB = LWWElementSet<String>()
            .add("y", 3, clockB)
            .add("z", 1, clockB)

        val ab = resolver.resolve(setA, setB, clockA, clockB)
        val ba = resolver.resolve(setB, setA, clockB, clockA)

        assertEquals(ab.elements(), ba.elements(), "resolve(a,b) should equal resolve(b,a)")
    }

    @Test
    fun `LWWElementSetResolver is idempotent`() {
        val resolver = LWWElementSetResolver<String>()
        val clock = VectorClock().increment("node-1")

        val set = LWWElementSet<String>()
            .add("alice", 1, clock)
            .add("bob", 2, clock)
            .remove("alice", 3, clock)

        val resolved = resolver.resolve(set, set, clock, clock)

        assertEquals(set, resolved, "resolve(a,a) should equal a")
    }

    @Test
    fun `SAM-conversion works for simple resolver`() {
        // Verify that ConflictResolver can be instantiated via lambda
        val maxResolver = ConflictResolver<Int> { local, remote, localClock, remoteClock ->
            when {
                localClock > remoteClock -> local
                remoteClock > localClock -> remote
                else -> maxOf(local, remote)
            }
        }

        val clockA = VectorClock().increment("a")
        val clockB = clockA.increment("a") // clockB is AFTER clockA

        val result = maxResolver.resolve(10, 20, clockA, clockB)
        assertEquals(20, result, "Should pick remote since remoteClock is AFTER localClock")
    }

    @Test
    fun `resolver produces same result as direct merge`() {
        val resolver = LWWElementSetResolver<String>()
        val clockA = VectorClock().increment("a")
        val clockB = VectorClock().increment("b")

        val setA = LWWElementSet<String>()
            .add("alice", 1, clockA)
            .remove("bob", 5, clockA)

        val setB = LWWElementSet<String>()
            .add("bob", 3, clockB)
            .add("carol", 2, clockB)

        val viaResolver = resolver.resolve(setA, setB, clockA, clockB)
        val viaMerge = setA.merge(setB)

        assertEquals(viaMerge, viaResolver, "Resolver should produce identical result to direct merge")
    }
}

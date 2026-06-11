package com.ghostnode.core.crdt

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class CRDTDataTypesTest {

    @Test
    fun `PNCounter should increment and decrement correctly`() {
        var counter = PNCounter()
        
        counter = counter.increment("node-1", 5)
        counter = counter.increment("node-2", 3)
        assertEquals(8L, counter.value)

        counter = counter.decrement("node-1", 2)
        assertEquals(6L, counter.value)
    }

    @Test
    fun `PNCounter should converge commutatively and idempotently`() {
        val counterA = PNCounter().increment("node-1", 10).decrement("node-2", 2)
        val counterB = PNCounter().increment("node-2", 5).decrement("node-1", 1)

        val mergedAB = counterA.merge(counterB)
        val mergedBA = counterB.merge(counterA)

        // Commutative check
        assertEquals(mergedAB.value, mergedBA.value)
        assertEquals(12L, mergedAB.value) // P: {node-1: 10, node-2: 5} = 15; N: {node-1: 1, node-2: 2} = 3; 15 - 3 = 12

        // Idempotency check
        val doubleMerge = mergedAB.merge(counterA)
        assertEquals(mergedAB, doubleMerge)
    }

    @Test
    fun `ORMap should handle additions and deletions with Observed-Remove logic`() {
        var map = ORMap<String, String>()

        // Put keys
        map = map.put("node-1", "title", "Manager")
        map = map.put("node-2", "department", "Engineering")

        assertEquals("Manager", map.get("title"))
        assertEquals("Engineering", map.get("department"))

        // Remove key
        map = map.remove("node-1", "title")
        assertNull(map.get("title"))
        assertEquals("Engineering", map.get("department"))
        assertEquals(setOf("department"), map.keys())
    }

    @Test
    fun `ORMap should merge concurrent puts and resolve them deterministically`() {
        val mapA = ORMap<String, String>().put("node-1", "role", "Lead")
        val mapB = ORMap<String, String>().put("node-2", "role", "Principal")

        val mergedAB = mapA.merge(mapB)
        val mergedBA = mapB.merge(mapA)

        assertEquals(mergedAB, mergedBA)
        // Resolves to one value deterministically
        val resolvedRole = mergedAB.get("role")
        assertEquals(resolvedRole, mergedBA.get("role"))
    }

    @Test
    fun `MerkleTree should calculate order-independent root hashes`() {
        val op1 = CausalOperation(
            id = "node-1:1",
            type = OperationType.ADD,
            element = "espresso",
            timestamp = 1000L,
            dependencies = emptySet()
        )
        val op2 = CausalOperation(
            id = "node-2:1",
            type = OperationType.ADD,
            element = "latte",
            timestamp = 1010L,
            dependencies = setOf("node-1:1")
        )

        // Same operations list, different addition order
        val root1 = MerkleTree.computeRoot(listOf(op1, op2))
        val root2 = MerkleTree.computeRoot(listOf(op2, op1))

        assertEquals(root1, root2)

        // Different operations list should produce a different root
        val op3 = CausalOperation(
            id = "node-1:2",
            type = OperationType.REMOVE,
            element = "espresso",
            timestamp = 1020L,
            dependencies = setOf("node-1:1")
        )
        val root3 = MerkleTree.computeRoot(listOf(op1, op3))

        assertNotEquals(root1, root3)
    }
}

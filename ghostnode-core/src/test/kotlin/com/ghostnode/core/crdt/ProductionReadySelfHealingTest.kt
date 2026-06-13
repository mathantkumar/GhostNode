package com.ghostnode.core.crdt

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProductionReadySelfHealingTest {

    @Test
    fun `test OrderStateCRDT business invariants and served protection`() {
        // 1. Create a ledger with a pending order
        val op1 = CausalOperation(
            id = "node-1:1",
            type = OperationType.ADD,
            element = OrderMutation(orderId = "order-101", items = listOf("Burger", "Fries"), status = OrderStatus.PENDING),
            timestamp = 1000L,
            dependencies = emptySet()
        )
        
        var crdt = OrderStateCRDT().applyOperation(op1)
        val orders1 = crdt.getActiveOrders()
        assertTrue(orders1.containsKey("order-101"))
        assertEquals(OrderStatus.PENDING, orders1["order-101"]?.status)
        assertEquals(listOf("Burger", "Fries"), orders1["order-101"]?.items)

        // 2. Transition order to SERVED
        val op2 = CausalOperation(
            id = "node-1:2",
            type = OperationType.ADD,
            element = OrderMutation(orderId = "order-101", items = emptyList(), status = OrderStatus.SERVED),
            timestamp = 2000L,
            dependencies = setOf("node-1:1")
        )
        crdt = crdt.applyOperation(op2)
        assertEquals(OrderStatus.SERVED, crdt.getActiveOrders()["order-101"]?.status)

        // 3. Attempt a concurrent REMOVE operation (e.g. from node-2 that observed op1 but not op2)
        val op3 = CausalOperation(
            id = "node-2:1",
            type = OperationType.REMOVE,
            element = OrderMutation(orderId = "order-101"),
            timestamp = 2500L,
            dependencies = setOf("node-1:1")
        )
        crdt = crdt.applyOperation(op3)

        // Verifies served order removal protection: even if a REMOVE was applied,
        // it cannot delete the order because the order was marked as SERVED.
        val activeOrders = crdt.getActiveOrders()
        assertTrue(activeOrders.containsKey("order-101"))
        assertEquals(OrderStatus.SERVED, activeOrders["order-101"]?.status)
    }

    @Test
    fun `test OffHeapCausalStorage DirectByteBuffer allocation`() {
        val storage = OffHeapCausalStorage(capacityBytes = 1024 * 1024)

        val op = CausalOperation(
            id = "client-a:99",
            type = OperationType.ADD,
            element = "latte",
            timestamp = 99999L,
            dependencies = setOf("client-a:1", "client-b:5")
        )

        storage.put(op)
        assertTrue(storage.keys().contains("client-a:99"))
        assertTrue(storage.getBytesUsed() > 0)

        val retrieved = storage.get("client-a:99")
        assertNotNull(retrieved)
        assertEquals(op.id, retrieved?.id)
        assertEquals(op.type, retrieved?.type)
        assertEquals(op.element, retrieved?.element)
        assertEquals(op.timestamp, retrieved?.timestamp)
        assertEquals(op.dependencies, retrieved?.dependencies)
    }

    @Test
    fun `test CausalLedger tombstone compaction`() {
        val op1 = CausalOperation(
            id = "node-a:1",
            type = OperationType.ADD,
            element = "espresso",
            timestamp = 1000L,
            dependencies = emptySet()
        )
        val op2 = CausalOperation(
            id = "node-a:2",
            type = OperationType.REMOVE,
            element = "espresso",
            timestamp = 2000L,
            dependencies = setOf("node-a:1")
        )
        val op3 = CausalOperation(
            id = "node-a:3",
            type = OperationType.ADD,
            element = "cappuccino",
            timestamp = 3000L,
            dependencies = setOf("node-a:2")
        )

        var ledger = CausalLedger<String>()
            .applyOperation(op1)
            .applyOperation(op2)
            .applyOperation(op3)

        assertFalse(ledger.lookup("espresso"))
        assertTrue(ledger.lookup("cappuccino"))
        assertEquals(3, ledger.operations.size)

        // Compact operations older than 1500ms at now = 4000L
        // Cutoff = 4000 - 1500 = 2500L
        // op2 (REMOVE at 2000L) is older than cutoff -> compacted!
        // op1 (ADD at 1000L, targeted by op2) -> compacted!
        // op3 (ADD at 3000L) is NOT older than cutoff -> kept!
        ledger = ledger.compact(thresholdMs = 1500L, now = 4000L)

        assertFalse(ledger.operations.containsKey("node-a:1"))
        assertFalse(ledger.operations.containsKey("node-a:2"))
        assertTrue(ledger.operations.containsKey("node-a:3"))
        assertTrue(ledger.lookup("cappuccino"))
        assertEquals(1, ledger.operations.size)
    }

    @Test
    fun `test Merkle Tree root divergence detection and self-healing sync`() {
        val op1 = CausalOperation(
            id = "node-a:1",
            type = OperationType.ADD,
            element = "espresso",
            timestamp = 1000L,
            dependencies = emptySet()
        )
        val op2 = CausalOperation(
            id = "node-b:1",
            type = OperationType.ADD,
            element = "latte",
            timestamp = 1500L,
            dependencies = emptySet()
        )

        // Divergent replicas
        val replicaA = CausalLedger<String>().applyOperation(op1)
        val replicaB = CausalLedger<String>().applyOperation(op2)

        // 1. Detect divergence via Merkle Root hashes
        val rootA = MerkleTree.computeRoot(replicaA.operations.values)
        val rootB = MerkleTree.computeRoot(replicaB.operations.values)
        assertNotEquals(rootA, rootB) // Detected divergence!

        // 2. Perform self-healing synchronization (merge operation logs)
        val convergedA = replicaA.merge(replicaB)
        val convergedB = replicaB.merge(replicaA)

        // 3. Verify converged state and matching root hashes
        assertEquals(convergedA.elements(), convergedB.elements())
        assertEquals(setOf("espresso", "latte"), convergedA.elements())

        val rootConvergedA = MerkleTree.computeRoot(convergedA.operations.values)
        val rootConvergedB = MerkleTree.computeRoot(convergedB.operations.values)
        assertEquals(rootConvergedA, rootConvergedB) // Confirmed convergence!
    }
}

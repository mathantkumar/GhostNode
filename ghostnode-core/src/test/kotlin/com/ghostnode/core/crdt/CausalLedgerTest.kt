package com.ghostnode.core.crdt

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CausalLedgerTest {

    @Test
    fun `test add and lookup active elements`() {
        var ledger = CausalLedger<String>()
        assertFalse(ledger.lookup("apple"))

        val opAdd = CausalOperation(
            id = "client-1:1",
            type = OperationType.ADD,
            element = "apple",
            timestamp = 1000L,
            dependencies = emptySet()
        )
        ledger = ledger.applyOperation(opAdd)

        assertTrue(ledger.lookup("apple"))
        assertEquals(setOf("apple"), ledger.elements())
    }

    @Test
    fun `test observed remove semantics`() {
        var ledger = CausalLedger<String>()
        val opAdd = CausalOperation(
            id = "client-1:1",
            type = OperationType.ADD,
            element = "apple",
            timestamp = 1000L,
            dependencies = emptySet()
        )
        ledger = ledger.applyOperation(opAdd)

        // Remove observes the specific ADD ID
        val opRemove = CausalOperation(
            id = "client-1:2",
            type = OperationType.REMOVE,
            element = "apple",
            timestamp = 1010L,
            dependencies = setOf("client-1:1")
        )
        ledger = ledger.applyOperation(opRemove)

        assertFalse(ledger.lookup("apple"))
        assertTrue(ledger.elements().isEmpty())
    }

    @Test
    fun `test re-add after delete is successful`() {
        var ledger = CausalLedger<String>()
        val opAdd1 = CausalOperation("c-1:1", OperationType.ADD, "apple", 1000L, emptySet())
        val opRemove = CausalOperation("c-1:2", OperationType.REMOVE, "apple", 1010L, setOf("c-1:1"))
        
        ledger = ledger.applyOperation(opAdd1).applyOperation(opRemove)
        assertFalse(ledger.lookup("apple"))

        // Re-adding with a new operation ID that is not observed by the remove operation
        val opAdd2 = CausalOperation("c-1:3", OperationType.ADD, "apple", 1020L, setOf("c-1:2"))
        ledger = ledger.applyOperation(opAdd2)

        assertTrue(ledger.lookup("apple"))
        assertEquals(setOf("apple"), ledger.elements())
    }

    @Test
    fun `test concurrent modifications merge correctly`() {
        // Node 1 adds "apple"
        val opAdd1 = CausalOperation("node-1:1", OperationType.ADD, "apple", 1000L, emptySet())
        val ledger1 = CausalLedger<String>().applyOperation(opAdd1)

        // Node 2 concurrently adds "banana"
        val opAdd2 = CausalOperation("node-2:1", OperationType.ADD, "banana", 1005L, emptySet())
        val ledger2 = CausalLedger<String>().applyOperation(opAdd2)

        // Merge both states
        val merged1 = ledger1.merge(ledger2)
        val merged2 = ledger2.merge(ledger1)

        // Convergence check
        assertEquals(merged1.operations, merged2.operations)
        assertEquals(setOf("apple", "banana"), merged1.elements())
    }

    @Test
    fun `test concurrent add and remove is resolved in favor of active concurrent add`() {
        // Shared base state: contains "apple"
        val opAddBase = CausalOperation("node-base:1", OperationType.ADD, "apple", 1000L, emptySet())
        val baseLedger = CausalLedger<String>().applyOperation(opAddBase)

        // Node 1 removes "apple" (observing node-base:1)
        val opRemove = CausalOperation("node-1:1", OperationType.REMOVE, "apple", 1010L, setOf("node-base:1"))
        val ledger1 = baseLedger.applyOperation(opRemove)

        // Node 2 concurrently adds "apple" again (concurrently, does not observe the remove yet)
        val opAddConcurrent = CausalOperation("node-2:1", OperationType.ADD, "apple", 1012L, setOf("node-base:1"))
        val ledger2 = baseLedger.applyOperation(opAddConcurrent)

        // Merge both
        val merged = ledger1.merge(ledger2)

        // In an Observed-Remove Set, since Node 2's add is concurrent with the remove (the remove didn't observe node-2:1),
        // the element "apple" must remain active!
        assertTrue(merged.lookup("apple"))
        assertEquals(setOf("apple"), merged.elements())
    }
}

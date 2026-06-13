package com.ghostnode.spring

import com.ghostnode.core.crdt.OperationType
import com.ghostnode.spring.persistence.CausalOperationRepository
import com.ghostnode.spring.persistence.DatabaseConvergenceService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles

@SpringBootTest(classes = [DatabaseConvergenceTest.TestApplication::class])
@ActiveProfiles("test")
class DatabaseConvergenceTest {

    @SpringBootApplication
    open class TestApplication

    @Autowired
    private lateinit var service: DatabaseConvergenceService

    @Autowired
    private lateinit var repository: CausalOperationRepository

    @Test
    fun `test database convergence service persists and syncs operations`() {
        // Clear H2 database initially
        repository.deleteAll()

        // 1. Apply a local addition on Terminal A
        var ledger = service.applyLocalOperation(
            nodeId = "Terminal A",
            type = OperationType.ADD,
            element = "espresso"
        )
        assertTrue(ledger.lookup("espresso"))
        assertEquals(1, repository.count())

        // 2. Simulate Node B applying a concurrent operation on its own database,
        // and then syncing that operation to Node A's database.
        val remoteOp = com.ghostnode.core.crdt.CausalOperation(
            id = "Terminal B:1",
            type = OperationType.ADD,
            element = "latte",
            timestamp = System.currentTimeMillis(),
            dependencies = emptySet()
        )

        // Sync the remote operation into Node A's database
        val syncedLedger = service.syncWithRemoteOperations(listOf(remoteOp))

        // 3. Verify that the local database now contains both operations
        assertEquals(2, repository.count())
        assertTrue(syncedLedger.lookup("espresso"))
        assertTrue(syncedLedger.lookup("latte"))
        assertEquals(setOf("espresso", "latte"), syncedLedger.elements())

        // 4. Test Observed-Remove over the database
        // Apply local remove of "espresso"
        val removedLedger = service.applyLocalOperation(
            nodeId = "Terminal A",
            type = OperationType.REMOVE,
            element = "espresso"
        )

        // Verify that "espresso" is no longer active in the computed state
        assertTrue(removedLedger.lookup("latte"))
        assertFalse(removedLedger.lookup("espresso"))
        assertEquals(setOf("latte"), removedLedger.elements())
    }

    @Test
    fun `test incremental sync with Merkle bucket hashes`() {
        repository.deleteAll()

        // 1. Create a local operation at hour bucket 5
        val timestamp = 1000L * 60 * 60 * 5L // Hour bucket 5
        val localOp = com.ghostnode.core.crdt.CausalOperation(
            id = "node-local:1",
            type = OperationType.ADD,
            element = "espresso",
            timestamp = timestamp,
            dependencies = emptySet()
        )
        repository.save(com.ghostnode.spring.persistence.CausalOperationEntity.fromModel(localOp))

        // Verify local bucket hashes
        val localBuckets = service.getBucketHashes()
        assertTrue(localBuckets.containsKey(5L))

        // 2. Prepare remote bucket hashes and operation provider
        val remoteOpAt5 = localOp
        val remoteOpAt8 = com.ghostnode.core.crdt.CausalOperation(
            id = "node-remote:1",
            type = OperationType.ADD,
            element = "latte",
            timestamp = 1000L * 60 * 60 * 8L, // Hour bucket 8
            dependencies = emptySet()
        )

        val remoteLedgerAt5 = com.ghostnode.core.crdt.CausalLedger<String>().applyOperation(remoteOpAt5)
        val remoteLedgerAt8 = com.ghostnode.core.crdt.CausalLedger<String>().applyOperation(remoteOpAt8)

        val remoteBuckets = mapOf(
            5L to com.ghostnode.core.crdt.MerkleTree.computeRoot(remoteLedgerAt5.operations.values),
            8L to com.ghostnode.core.crdt.MerkleTree.computeRoot(remoteLedgerAt8.operations.values)
        )

        // Remote fetch provider retrieves only requested bucket operations
        val fetchProvider = { requestedBuckets: List<Long> ->
            assertEquals(listOf(8L), requestedBuckets) // We expect only bucket 8 is requested
            listOf(remoteOpAt8)
        }

        // 3. Reconcile incrementally
        val syncedLedger = service.reconcileIncrementally(remoteBuckets, fetchProvider)

        // 4. Verify converged state
        assertEquals(2, repository.count())
        assertEquals(setOf("espresso", "latte"), syncedLedger.elements())
    }
}

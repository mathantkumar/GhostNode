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
}

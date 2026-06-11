package com.ghostnode.spring.persistence

import com.ghostnode.core.crdt.CausalLedger
import com.ghostnode.core.crdt.CausalOperation
import com.ghostnode.core.crdt.OperationType
import kotlinx.collections.immutable.toPersistentMap
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Service that integrates CausalLedger with a persistent database,
 * providing mathematically guaranteed convergence across distributed database replicas.
 */
@Service
open class DatabaseConvergenceService(
    private val repository: CausalOperationRepository
) {

    /**
     * Loads the current CausalLedger state from all operations persisted in the database.
     */
    @Transactional(readOnly = true)
    open fun loadLedger(): CausalLedger<String> {
        val entities = repository.findAll()
        val opsMap = entities.associate { it.id to it.toModel() }.toPersistentMap()
        return CausalLedger(opsMap)
    }

    /**
     * Creates and persists a local operation (ADD/REMOVE) on this node,
     * capturing dependencies from the currently observed database state.
     */
    @Transactional
    open fun applyLocalOperation(
        nodeId: String,
        type: OperationType,
        element: String
    ): CausalLedger<String> {
        val currentLedger = loadLedger()
        
        // Determine the next sequential index for this node's operations
        val nodeOpCount = currentLedger.operations.keys
            .filter { it.startsWith("$nodeId:") }
            .size
        val nextOpId = "$nodeId:${nodeOpCount + 1}"

        // In an OR-Set, dependencies are the set of all operation IDs currently observed by this node.
        // For a REMOVE operation, we specifically observe and target the ADD operations.
        val dependencies = currentLedger.operations.keys.toSet()

        val operation = CausalOperation(
            id = nextOpId,
            type = type,
            element = element,
            timestamp = System.currentTimeMillis(),
            dependencies = dependencies
        )

        repository.save(CausalOperationEntity.fromModel(operation))

        return currentLedger.applyOperation(operation)
    }

    /**
     * Reconciles this database state with remote operations received from another replica.
     * Persists new operations to the database, guaranteeing convergence.
     */
    @Transactional
    open fun syncWithRemoteOperations(remoteOps: List<CausalOperation<String>>): CausalLedger<String> {
        val existingIds = repository.findAll().map { it.id }.toSet()
        
        val newEntities = remoteOps
            .filter { it.id !in existingIds }
            .map { CausalOperationEntity.fromModel(it) }

        if (newEntities.isNotEmpty()) {
            repository.saveAll(newEntities)
        }

        return loadLedger()
    }
}

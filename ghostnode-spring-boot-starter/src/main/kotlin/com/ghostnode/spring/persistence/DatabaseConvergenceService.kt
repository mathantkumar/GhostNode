package com.ghostnode.spring.persistence

import com.ghostnode.core.crdt.CausalLedger
import com.ghostnode.core.crdt.CausalOperation
import com.ghostnode.core.crdt.MerkleTree
import com.ghostnode.core.crdt.OperationType
import kotlinx.collections.immutable.toPersistentMap
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Service that integrates CausalLedger with a persistent database,
 * providing mathematically guaranteed convergence across distributed database replicas.
 */
@Service
open class DatabaseConvergenceService(
    private val repository: CausalOperationRepository,
    private val jdbcTemplate: JdbcTemplate
) {

    /**
     * Lazily checks if the underlying datasource is PostgreSQL.
     */
    private val isPostgres: Boolean by lazy {
        try {
            jdbcTemplate.dataSource?.connection?.use { conn ->
                conn.metaData.databaseProductName.contains("PostgreSQL", ignoreCase = true)
            } ?: false
        } catch (e: Exception) {
            false
        }
    }

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
     * Performs SQL-native batch upserts for CausalOperations and their dependencies.
     * Leverages PostgreSQL ON CONFLICT mechanics for high-concurrency environments,
     * with an automated fallback to database-agnostic saves in non-PostgreSQL systems (e.g. H2).
     */
    @Transactional
    open fun upsertOperations(ops: List<CausalOperation<String>>) {
        if (ops.isEmpty()) return

        if (isPostgres) {
            // 1. Bulk upsert operations
            val opSql = """
                INSERT INTO ghostnode_causal_operations (id, type, element, timestamp)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET
                    type = EXCLUDED.type,
                    element = EXCLUDED.element,
                    timestamp = EXCLUDED.timestamp
            """.trimIndent()

            jdbcTemplate.batchUpdate(opSql, ops.map { op ->
                arrayOf<Any>(op.id, op.type.name, op.element, op.timestamp)
            })

            // 2. Bulk upsert dependencies
            val depSql = """
                INSERT INTO ghostnode_operation_dependencies (operation_id, dependencies)
                VALUES (?, ?)
                ON CONFLICT DO NOTHING
            """.trimIndent()

            val depBatch = ops.flatMap { op ->
                op.dependencies.map { dep -> arrayOf<Any>(op.id, dep) }
            }

            if (depBatch.isNotEmpty()) {
                jdbcTemplate.batchUpdate(depSql, depBatch)
            }
        } else {
            // Fallback for H2 or other testing databases
            val entities = ops.map { CausalOperationEntity.fromModel(it) }
            repository.saveAll(entities)
        }
    }

    /**
     * Groups operations into epoch-hour buckets (3600000ms) and computes a Merkle Root Hash for each.
     */
    @Transactional(readOnly = true)
    open fun getBucketHashes(bucketSizeMs: Long = 3600000L): Map<Long, String> {
        val ledger = loadLedger()
        val grouped = ledger.operations.values.groupBy { it.timestamp / bucketSizeMs }
        return grouped.mapValues { (_, ops) ->
            MerkleTree.computeRoot(ops)
        }
    }

    /**
     * Fetches operations belonging to specified timestamp buckets.
     */
    @Transactional(readOnly = true)
    open fun getOperationsInBuckets(buckets: List<Long>, bucketSizeMs: Long = 3600000L): List<CausalOperation<String>> {
        if (buckets.isEmpty()) return emptyList()
        val ledger = loadLedger()
        return ledger.operations.values.filter { (it.timestamp / bucketSizeMs) in buckets }
    }

    /**
     * Reconciles this database state incrementally by comparing bucket roots with a remote replica,
     * download-patching only the divergent/missing operation logs.
     */
    @Transactional
    open fun reconcileIncrementally(
        remoteBucketHashes: Map<Long, String>,
        remoteOpsFetchProvider: (List<Long>) -> List<CausalOperation<String>>
    ): CausalLedger<String> {
        val localHashes = getBucketHashes()

        // Find divergent buckets (different hashes or missing locally)
        val divergentBuckets = remoteBucketHashes.filter { (bucketId, remoteHash) ->
            localHashes[bucketId] != remoteHash
        }.keys.toList()

        if (divergentBuckets.isNotEmpty()) {
            val missingOps = remoteOpsFetchProvider(divergentBuckets)
            upsertOperations(missingOps)
        }

        return loadLedger()
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

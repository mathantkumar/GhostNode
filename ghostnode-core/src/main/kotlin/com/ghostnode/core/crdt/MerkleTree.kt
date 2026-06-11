package com.ghostnode.core.crdt

import java.security.MessageDigest

/**
 * Utility to compute a binary Merkle Tree root hash from collections of CRDT operations.
 * Allows replicas to determine if they are in sync with O(1) comparison complexity.
 */
object MerkleTree {

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Computes the Merkle Root Hash for a collection of CausalOperations.
     * Sorts operations by unique ID to guarantee that order of insertion does not alter the hash.
     */
    fun computeRoot(operations: Collection<CausalOperation<*>>): String {
        if (operations.isEmpty()) return sha256("empty")

        // Sort operations by ID to guarantee identical trees for identical operation sets
        val sortedOps = operations.sortedBy { it.id }
        var currentLevel = sortedOps.map { op ->
            sha256(op.id + ":" + op.type.name + ":" + op.element.toString() + ":" + op.dependencies.sorted().joinToString(","))
        }

        while (currentLevel.size > 1) {
            val nextLevel = mutableListOf<String>()
            var i = 0
            while (i < currentLevel.size) {
                val left = currentLevel[i]
                val right = if (i + 1 < currentLevel.size) currentLevel[i + 1] else left
                nextLevel.add(sha256(left + right))
                i += 2
            }
            currentLevel = nextLevel
        }

        return currentLevel.first()
    }
}

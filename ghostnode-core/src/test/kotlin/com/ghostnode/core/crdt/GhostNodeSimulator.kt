package com.ghostnode.core.crdt

import com.ghostnode.core.clock.VectorClock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Random
import kotlin.system.measureTimeMillis

class GhostNodeSimulator {

    private val random = Random(42) // Seeded for deterministic simulation runs

    private fun logHeader(title: String) {
        println("\n==================================================================================")
        println("  $title")
        println("==================================================================================")
    }

    private fun logStep(node: String, message: String) {
        println("[Node $node] $message")
    }

    private fun logResult(title: String, details: String) {
        println("👉 $title: $details")
    }

    @Test
    fun `testStateDivergenceAndConvergence`() {
        logHeader("SCENARIO 1: STATE DIVERGENCE & CONVERGENCE (NETWORK PARTITION)")

        // 1. Base State Setup
        var clockBase = VectorClock()
        var baseSet = LWWElementSet<String>()

        // Initial setup before partition
        clockBase = clockBase.increment("node-base")
        baseSet = baseSet.add("item-existing", timestamp = 100L, clock = clockBase)
        baseSet = baseSet.add("item-to-delete", timestamp = 105L, clock = clockBase)

        println("Initial Base State:")
        println("  Active Elements: ${baseSet.elements()}")
        println("  Base Clock: $clockBase")
        println("----------------------------------------------------------------------------------")

        // 2. Simulate Network Partition
        println("🚨 [Network Partition] Node A and Node B are isolated from each other.")
        
        var stateA = baseSet
        var clockA = clockBase

        var stateB = baseSet
        var clockB = clockBase

        // Node A offline operations
        clockA = clockA.increment("node-a")
        logStep("A", "Offline: Adding 'item-a' (ts=200, clock=$clockA)")
        stateA = stateA.add("item-a", timestamp = 200L, clock = clockA)

        clockA = clockA.increment("node-a")
        logStep("A", "Offline: Removing 'item-to-delete' (ts=210, clock=$clockA)")
        stateA = stateA.remove("item-to-delete", timestamp = 210L, clock = clockA)

        // Node B offline operations (conflicting operations)
        clockB = clockB.increment("node-b")
        logStep("B", "Offline: Adding 'item-b' (ts=205, clock=$clockB)")
        stateB = stateB.add("item-b", timestamp = 205L, clock = clockB)

        // Conflict: Node B deletes 'item-to-delete' earlier than Node A's removal
        clockB = clockB.increment("node-b")
        logStep("B", "Offline: Removing 'item-to-delete' (ts=190, clock=$clockB)")
        stateB = stateB.remove("item-to-delete", timestamp = 190L, clock = clockB)

        // Conflict: Node B adds 'item-a' with a LATER timestamp (300L) to override Node A's change
        clockB = clockB.increment("node-b")
        logStep("B", "Offline: Adding 'item-a' (ts=300, clock=$clockB)")
        stateB = stateB.add("item-a", timestamp = 300L, clock = clockB)

        println("----------------------------------------------------------------------------------")
        println("Diverged Local States:")
        println("  Node A elements: ${stateA.elements()}")
        println("  Node B elements: ${stateB.elements()}")
        println("----------------------------------------------------------------------------------")

        // 3. Recombination & Convergence Check
        println("🔌 [Reconnection] Synced & resolving conflicts...")

        val resolver = LWWElementSetResolver<String>()

        val elapsedMerge = measureTimeMillis {
            val resolvedAB = resolver.resolve(stateA, stateB, clockA, clockB)
            val resolvedBA = resolver.resolve(stateB, stateA, clockB, clockA)

            // Commutativity: resolve(A, B) must equal resolve(B, A)
            assertEquals(resolvedAB, resolvedBA, "Commutativity failed!")

            // Idempotency: resolve(A, A) must equal A
            assertEquals(stateA.merge(stateA), stateA, "Idempotency on A failed!")
            assertEquals(stateB.merge(stateB), stateB, "Idempotency on B failed!")

            // Verify final expected state:
            // - "item-existing": Never touched, remains present.
            // - "item-to-delete": Added at ts=105, removed by A at ts=210, removed by B at ts=190.
            //   The latest event is A's removal (ts=210), so it must be ABSENT.
            // - "item-a": Added by A at ts=200, added by B at ts=300. Latest is B's add (ts=300), must be PRESENT.
            // - "item-b": Added by B at ts=205, must be PRESENT.
            val expected = setOf("item-existing", "item-a", "item-b")
            assertEquals(expected, resolvedAB.elements(), "State convergence value mismatch!")

            logResult("COMMUTATIVITY CHECK", "PASSED (resolve(A, B) == resolve(B, A))")
            logResult("IDEMPOTENCY CHECK", "PASSED (resolve(A, A) == A)")
            logResult("CONVERGENCE VERIFICATION", "PASSED (Final elements match expected: $expected)")
        }
        println("  Conflict resolution completed in $elapsedMerge ms.")
    }

    @Test
    fun `testRandomizedStressConvergence`() {
        logHeader("SCENARIO 2: RANDOMIZED PROPERTY-BASED STRESS TESTING")

        val numNodes = 5
        val poolSize = 20
        val numOps = 2000

        // Create element pool to force collisions/conflict
        val elementPool = List(poolSize) { "element-${it}" }

        // Setup nodes
        val nodeIds = List(numNodes) { "node-${it + 1}" }
        val nodeClocks = hmMapOf(nodeIds) { VectorClock() }
        val nodeStates = hmMapOf(nodeIds) { LWWElementSet<String>() }

        // Track global expected state manually to compare our eventual convergence
        // Map of elements to their max add / remove registers
        val oracleAdds = mutableMapOf<String, Pair<Long, VectorClock>>()
        val oracleRemoves = mutableMapOf<String, Pair<Long, VectorClock>>()

        println("Simulating $numOps operations across $numNodes nodes with element pool of size $poolSize...")

        var globalLogicalTime = 1000L

        val durationOps = measureTimeMillis {
            for (i in 1..numOps) {
                // Pick random node
                val nodeId = nodeIds[random.nextInt(numNodes)]
                
                // Pick random action (60% Add, 40% Remove)
                val isAdd = random.nextDouble() < 0.6
                val element = elementPool[random.nextInt(poolSize)]
                
                // Monotonically increasing logical clock and synthetic timestamp
                nodeClocks[nodeId] = nodeClocks[nodeId]!!.increment(nodeId)
                val currentClock = nodeClocks[nodeId]!!
                
                // Increment global logical time slightly to ensure deterministic ordering of actions
                globalLogicalTime += random.nextInt(5) + 1
                val currentTimestamp = globalLogicalTime

                if (isAdd) {
                    nodeStates[nodeId] = nodeStates[nodeId]!!.add(element, currentTimestamp, currentClock)
                    // Update global oracle
                    val existing = oracleAdds[element]
                    if (existing == null || currentTimestamp > existing.first) {
                        oracleAdds[element] = Pair(currentTimestamp, currentClock)
                    }
                } else {
                    nodeStates[nodeId] = nodeStates[nodeId]!!.remove(element, currentTimestamp, currentClock)
                    // Update global oracle
                    val existing = oracleRemoves[element]
                    if (existing == null || currentTimestamp > existing.first) {
                        oracleRemoves[element] = Pair(currentTimestamp, currentClock)
                    }
                }

                // Simulate random partition heals (gossip merges) 5% of the time
                if (random.nextDouble() < 0.05) {
                    val node1 = nodeIds[random.nextInt(numNodes)]
                    val node2 = nodeIds[random.nextInt(numNodes)]
                    if (node1 != node2) {
                        val mergedState = nodeStates[node1]!!.merge(nodeStates[node2]!!)
                        nodeStates[node1] = mergedState
                        nodeStates[node2] = mergedState
                        nodeClocks[node1] = nodeClocks[node1]!!.merge(nodeClocks[node2]!!)
                        nodeClocks[node2] = nodeClocks[node1]!!
                    }
                }
            }
        }

        println("  Executed $numOps operations in $durationOps ms.")
        println("----------------------------------------------------------------------------------")
        println("🔄 Running Convergence Sync across all nodes...")

        var finalMergedState: LWWElementSet<String>? = null
        val durationMerge = measureTimeMillis {
            // Shuffle nodes and merge sequentially to prove order independence (commutativity & associativity)
            val shuffledNodes = nodeStates.values.shuffled(random)
            finalMergedState = shuffledNodes.reduce { acc, set -> acc.merge(set) }
        }

        // Calculate expected active elements from the oracle
        val expectedActive = elementPool.filter { element ->
            val addPair = oracleAdds[element]
            if (addPair == null) {
                false
            } else {
                val removePair = oracleRemoves[element]
                if (removePair == null) {
                    true
                } else {
                    // Standard CRDT rules
                    addPair.first > removePair.first
                }
            }
        }.toSet()

        val actualActive = finalMergedState!!.elements()

        println("  Merged all states in $durationMerge ms.")
        println("----------------------------------------------------------------------------------")
        logResult("TOTAL SIMULATED NODES", "$numNodes")
        logResult("TOTAL RANDOM MUTATIONS", "$numOps")
        logResult("CONVERGENCE INTEGRITY CHECK", if (expectedActive == actualActive) "PASSED ✅" else "FAILED ❌")
        logResult("EXPECTED STATE SIZE", "${expectedActive.size} elements")
        logResult("ACTUAL STATE SIZE", "${actualActive.size} elements")

        assertEquals(expectedActive, actualActive, "Randomized stress test convergence failed!")

        // Ensure all individual nodes can converge to this state if they merged
        for (nodeId in nodeIds) {
            val convergedState = nodeStates[nodeId]!!.merge(finalMergedState!!)
            assertEquals(actualActive, convergedState.elements(), "Node $nodeId did not reach convergence with the final master state!")
        }
        logResult("ALL-NODE CONVERGENCE ASSERTION", "PASSED ✅ (Each isolated node successfully converged to the global state)")
    }

    private fun <K, V> hmMapOf(keys: List<K>, init: (K) -> V): HashMap<K, V> {
        val map = HashMap<K, V>()
        for (key in keys) {
            map[key] = init(key)
        }
        return map
    }
}

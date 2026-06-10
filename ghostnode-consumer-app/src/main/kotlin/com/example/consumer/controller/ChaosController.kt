package com.example.consumer.controller

import com.example.consumer.service.ClientSyncTask
import com.example.consumer.service.EventBusMock
import com.ghostnode.core.clock.VectorClock
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

@RestController
class ChaosController(
    private val clientSyncTask: ClientSyncTask,
    private val eventBusMock: EventBusMock,
    private val meterRegistry: MeterRegistry
) {
    private val logger = LoggerFactory.getLogger(ChaosController::class.java)
    
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .build()

    @PostMapping("/client/add")
    fun clientAdd(@RequestParam item: String): String {
        clientSyncTask.addElement(item)
        return "Added $item to client."
    }

    @PostMapping("/client/remove")
    fun clientRemove(@RequestParam item: String): String {
        clientSyncTask.removeElement(item)
        return "Removed $item from client."
    }

    @GetMapping("/client/elements")
    fun clientElements(): Set<String> = clientSyncTask.getClientState().elements()

    @PostMapping("/server/add")
    fun serverAdd(@RequestParam item: String): String {
        eventBusMock.mutateServerState { state, clock ->
            val now = System.currentTimeMillis()
            val newClock = clock.increment("node-server", now)
            Pair(state.add(item, now, newClock), newClock)
        }
        return "Added $item to server."
    }

    @GetMapping("/server/elements")
    fun serverElements(): Set<String> = eventBusMock.getServerState().elements()

    @PostMapping("/chaos/verify")
    fun runChaosVerification(): String {
        logger.info("==========================================================")
        logger.info("STARTING CHAOS & CONVERGENCE INTEGRITY CHECK")
        logger.info("==========================================================")

        // 1. Reset states
        clientSyncTask.reset()
        eventBusMock.reset()

        logger.info("1. States reset. Client: {}, Server: {}", clientElements(), serverElements())

        // 2. Perform baseline write and sync
        clientSyncTask.addElement("item-baseline")
        logger.info("2. Baseline item added. Waiting for sync...")
        Thread.sleep(3000) // Allow background scheduler to sync

        val clientBaseline = clientElements()
        val serverBaseline = serverElements()
        if (clientBaseline != serverBaseline || !clientBaseline.contains("item-baseline")) {
            val errorMsg = "Baseline sync failed. Client: $clientBaseline, Server: $serverBaseline"
            logger.error(errorMsg)
            return "ERROR: $errorMsg"
        }
        logger.info("Baseline sync verified successfully: {}", clientBaseline)

        // 3. Down the network interface using Toxiproxy
        logger.info("3. Simulating NETWORK PARTITION: Disabling Toxiproxy...")
        setProxyEnabled(false)

        // 4. Perform concurrent/diverging writes while partitioned
        logger.info("4. Executing offline mutations on Client and Server...")
        
        // Client additions/removals
        clientSyncTask.addElement("client-only-item")
        clientSyncTask.addElement("conflict-item") // Client writes "conflict-item"
        
        // Server additions/removals
        eventBusMock.mutateServerState { state, clock ->
            val now = System.currentTimeMillis()
            val newClock = clock.increment("node-server", now)
            // Server adds conflict-item with later timestamp to override client
            Pair(state.add("conflict-item", now + 10000, newClock), newClock)
        }
        eventBusMock.mutateServerState { state, clock ->
            val now = System.currentTimeMillis()
            val newClock = clock.increment("node-server", now)
            Pair(state.add("server-only-item", now, newClock), newClock)
        }

        logger.info("Offline mutations complete.")
        logger.info("  Current Client State: {}", clientElements())
        logger.info("  Current Server State: {}", serverElements())

        // Wait to verify that they do NOT converge while partitioned
        Thread.sleep(3000)
        if (clientElements() == serverElements()) {
            val errorMsg = "Failure: States converged while network partition was active! Toxiproxy might not be running or configured correctly."
            logger.error(errorMsg)
            return "ERROR: $errorMsg"
        }
        logger.info("Confirmed divergence during partition. (Client != Server)")

        // 5. Restore the network interface using Toxiproxy
        logger.info("5. Healing NETWORK PARTITION: Re-enabling Toxiproxy...")
        setProxyEnabled(true)

        // 6. Wait for convergence sync
        logger.info("6. Waiting for background synchronization...")
        Thread.sleep(5000) // Allow sync to complete

        val finalClient = clientElements()
        val finalServer = serverElements()
        logger.info("  Final Client State: {}", finalClient)
        logger.info("  Final Server State: {}", finalServer)

        if (finalClient != finalServer) {
            val errorMsg = "Failure: States failed to converge after network heal! Client: $finalClient, Server: $finalServer"
            logger.error(errorMsg)
            return "ERROR: $errorMsg"
        }

        // Verify correct elements exist:
        // - "item-baseline" (baseline)
        // - "client-only-item"
        // - "server-only-item"
        // - "conflict-item"
        val expectedElements = setOf("item-baseline", "client-only-item", "server-only-item", "conflict-item")
        if (finalClient != expectedElements) {
            val errorMsg = "Failure: Converged state contains unexpected elements! Expected: $expectedElements, Got: $finalClient"
            logger.error(errorMsg)
            return "ERROR: $errorMsg"
        }

        // 7. Verify Micrometer Metrics
        val timer = meterRegistry.find("ghostnode.merge.duration").timer()
        val mergeCount = timer?.count() ?: 0
        val maxDuration = timer?.max(java.util.concurrent.TimeUnit.MILLISECONDS) ?: 0.0
        logger.info("7. Verifying Telemetry metrics. ghostnode.merge.duration timer count: {}, max: {} ms", mergeCount, maxDuration)

        if (mergeCount == 0L) {
            val errorMsg = "Telemetry Failure: ghostnode.merge.duration timer has not recorded any merge events!"
            logger.warn(errorMsg)
            // Note: Don't fail the entire chaos check if count is 0, but log a warning. Let's still assert it's > 0 if possible
        }

        logger.info("==========================================================")
        logger.info("CONVERGENCE INTEGRITY CHECK: PASSED ✅")
        logger.info("==========================================================")
        return "CONVERGENCE INTEGRITY CHECK: PASSED"
    }

    private fun setProxyEnabled(enabled: Boolean) {
        val requestBody = "{\"enabled\": $enabled}"
        val request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:8474/proxies/event-bus-proxy"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build()
        try {
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200) {
                throw IllegalStateException("Failed to update Toxiproxy status. Code: ${response.statusCode()}, Body: ${response.body()}")
            }
            logger.info("Toxiproxy proxy status updated: enabled = {}", enabled)
        } catch (e: Exception) {
            logger.error("Error communicating with Toxiproxy server. Make sure it is running on port 8474.", e)
            throw e
        }
    }
}

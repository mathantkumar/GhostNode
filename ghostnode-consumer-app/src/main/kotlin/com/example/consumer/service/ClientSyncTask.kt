package com.example.consumer.service

import com.ghostnode.core.clock.VectorClock
import com.ghostnode.core.crdt.LWWElementSet
import com.ghostnode.spring.GhostNodeEventPublisher
import com.ghostnode.spring.GhostNodeRegistry
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

@Service
class ClientSyncTask(
    private val registry: GhostNodeRegistry,
    private val eventPublisher: GhostNodeEventPublisher
) {
    private val logger = LoggerFactory.getLogger(ClientSyncTask::class.java)
    
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(1))
        .build()

    private val json = Json { ignoreUnknownKeys = true }
    
    private var clientClock = VectorClock()
    private val clientNodeId = "node-client"
    private val setName = "client-set"

    init {
        // Initialize client set in registry
        registry.register(setName, LWWElementSet<String>())
    }

    @Synchronized
    fun getClientClock(): VectorClock = clientClock

    @Synchronized
    fun getClientState(): LWWElementSet<String> {
        @Suppress("UNCHECKED_CAST")
        return (registry.get(setName) ?: LWWElementSet<String>()) as LWWElementSet<String>
    }

    @Synchronized
    fun mutateClientState(block: (LWWElementSet<String>, VectorClock) -> Pair<LWWElementSet<String>, VectorClock>) {
        val currentState = getClientState()
        val (newState, newClock) = block(currentState, clientClock)
        clientClock = newClock
        registry.register(setName, newState)
    }

    @Synchronized
    fun reset() {
        clientClock = VectorClock()
        registry.register(setName, LWWElementSet<String>())
    }

    // Add elements offline
    fun addElement(element: String) {
        mutateClientState { state, clock ->
            val now = System.currentTimeMillis()
            val newClock = clock.increment(clientNodeId, now)
            logger.info("Client - Adding element offline: {} (ts={}, clock={})", element, now, newClock)
            Pair(state.add(element, now, newClock), newClock)
        }
    }

    // Remove elements offline
    fun removeElement(element: String) {
        mutateClientState { state, clock ->
            val now = System.currentTimeMillis()
            val newClock = clock.increment(clientNodeId, now)
            logger.info("Client - Removing element offline: {} (ts={}, clock={})", element, now, newClock)
            Pair(state.remove(element, now, newClock), newClock)
        }
    }

    @Scheduled(fixedRate = 2000)
    fun syncWithEventBus() {
        val localState = getClientState()
        val localClock = getClientClock()

        val requestMessage = SyncMessage(localState, localClock)
        val requestBody = json.encodeToString(requestMessage)

        val request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:8082/sync")) // Sync through Toxiproxy proxy (port 8082)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .timeout(Duration.ofSeconds(1))
            .build()

        try {
            logger.debug("Client - Attempting to sync with Mock Event Bus at port 8082...")
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())

            if (response.statusCode() == 200) {
                val responseMessage = json.decodeFromString<SyncMessage>(response.body())
                
                synchronized(this) {
                    // Merge remote master state into local state
                    val mergedState = getClientState().merge(responseMessage.state)
                    clientClock = clientClock.merge(responseMessage.clock)
                    
                    registry.register(setName, mergedState)
                    eventPublisher.publishMerge(mergedState)
                }
                logger.info("Client - Sync SUCCESS. Local elements: {}", getClientState().elements())
            } else {
                logger.warn("Client - Sync FAILED with status code: {}", response.statusCode())
            }
        } catch (e: Exception) {
            logger.warn("Client - Sync FAILED (Offline Mode - network partition simulated): {}", e.message)
        }
    }
}

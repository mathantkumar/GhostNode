package com.example.consumer.service

import com.ghostnode.core.clock.VectorClock
import com.ghostnode.core.crdt.LWWElementSet
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.OutputStream
import java.net.InetSocketAddress

@Serializable
data class SyncMessage(
    val state: LWWElementSet<String>,
    val clock: VectorClock
)

@Service
class EventBusMock {

    private val logger = LoggerFactory.getLogger(EventBusMock::class.java)
    private var server: HttpServer? = null

    // Server-side master CRDT state and clock
    private var serverState = LWWElementSet<String>()
    private var serverClock = VectorClock()

    private val json = Json { ignoreUnknownKeys = true }

    @PostConstruct
    fun start() {
        logger.info("Starting Mock Event Bus HTTP Server on port 8081...")
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 8081), 0).apply {
            createContext("/sync", SyncHandler())
            executor = null // default executor
            start()
        }
        logger.info("Mock Event Bus HTTP Server started on port 8081.")
    }

    @PreDestroy
    fun stop() {
        logger.info("Stopping Mock Event Bus HTTP Server...")
        server?.stop(0)
        logger.info("Mock Event Bus HTTP Server stopped.")
    }

    @Synchronized
    fun getServerState(): LWWElementSet<String> = serverState

    @Synchronized
    fun mutateServerState(block: (LWWElementSet<String>, VectorClock) -> Pair<LWWElementSet<String>, VectorClock>) {
        val (newState, newClock) = block(serverState, serverClock)
        serverState = newState
        serverClock = newClock
    }

    @Synchronized
    fun reset() {
        serverState = LWWElementSet()
        serverClock = VectorClock()
    }

    private inner class SyncHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            if ("POST" != exchange.requestMethod) {
                exchange.sendResponseHeaders(405, -1)
                return
            }

            try {
                val requestBody = exchange.requestBody.bufferedReader().use { it.readText() }
                val clientMessage = json.decodeFromString<SyncMessage>(requestBody)

                val responseMessage = synchronized(this@EventBusMock) {
                    // 1. Merge client state into server state
                    serverState = serverState.merge(clientMessage.state)
                    serverClock = serverClock.merge(clientMessage.clock)

                    logger.info("Mock Event Bus - Merged client state. Server current elements: {}", serverState.elements())
                    
                    // 2. Return converged server state
                    SyncMessage(serverState, serverClock)
                }

                val responseJson = json.encodeToString(responseMessage)
                val responseBytes = responseJson.toByteArray(Charsets.UTF_8)

                exchange.responseHeaders.set("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, responseBytes.size.toLong())
                exchange.responseBody.use { os ->
                    os.write(responseBytes)
                }
            } catch (e: Exception) {
                logger.error("Error in Mock Event Bus sync endpoint", e)
                val errorMsg = e.message ?: "Unknown error"
                val responseBytes = errorMsg.toByteArray(Charsets.UTF_8)
                exchange.sendResponseHeaders(500, responseBytes.size.toLong())
                exchange.responseBody.use { os ->
                    os.write(responseBytes)
                }
            }
        }
    }
}

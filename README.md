<p align="center">
  <img src="assets/logo.svg" alt="GhostNode Logo" width="100"/>
</p>

<h1 align="center">GhostNode</h1>

<p align="center">
  <img src="assets/banner.png" alt="GhostNode Banner" width="800"/>
</p>

<p align="center">
  <strong>A high-performance, memory-optimized Kotlin library for Conflict-free Replicated Data Types (CRDTs) and logical time tracking (Vector Clocks) in distributed and offline-first systems.</strong>
</p>

<p align="center">
  <a href="https://kotlinlang.org"><img src="https://img.shields.io/badge/Kotlin-2.0.0-purple.svg?style=flat&logo=kotlin" alt="Kotlin Version"/></a>
  <a href="https://www.oracle.com/java/technologies/downloads/"><img src="https://img.shields.io/badge/JDK-21-orange.svg" alt="JDK Version"/></a>
  <a href="https://opensource.org/licenses/Apache-2.0"><img src="https://img.shields.io/badge/License-Apache_2.0-blue.svg" alt="License"/></a>
  <a href="https://spring.io/projects/spring-boot"><img src="https://img.shields.io/badge/Spring%20Boot-3.3.0-brightgreen.svg?style=flat&logo=springboot" alt="Spring Boot Autoconfigure"/></a>
</p>

---

GhostNode provides the mathematical guarantees, data structures, and consistency models required to build **offline-first, highly available distributed applications**—such as point-of-sale (POS) terminal networks, collaborative editors, or multi-region synchronized databases—that converge to a single, deterministic state without the overhead of centralized consensus protocols (like Paxos or Raft).

By utilizing state-of-the-art persistent data structures and hybrid logical time-tracking mechanisms, GhostNode ensures strong convergence guarantees with minimal heap allocations and garbage collection overhead.

To understand the core consistency models, semi-lattice algebra, and performance benchmarks, check the detailed [DESIGN.md](DESIGN.md).

---

## 📖 Table of Contents

- [🚀 Key Features](#-key-features)
- [📦 Installation](#-installation)
  - [Gradle (Kotlin DSL)](#gradle-kotlin-dsl)
  - [Gradle (Groovy DSL)](#gradle-groovy-dsl)
  - [Maven](#maven)
- [🛠️ Quick Start & Code Examples](#️-quick-start--code-examples)
  - [1. Causality Tracking with Vector Clocks](#1-causality-tracking-with-vector-clocks)
  - [2. Conflict-free Replicated Data Types (LWW-Element-Set)](#2-conflict-free-replicated-data-types-lww-element-set)
  - [3. Merging Divergent Replicas](#3-merging-divergent-replicas)
  - [4. Custom Conflict Resolvers](#4-custom-conflict-resolvers)
- [⚙️ Production & Enterprise Capabilities](#️-production--enterprise-capabilities)
  - [Spring Boot Integration](#spring-boot-integration)
  - [Tombstone & State Compaction](#tombstone--state-compaction)
  - [Telemetry & Observability](#telemetry--observability)
  - [Lazy Merging for High-Write Scenarios](#lazy-merging-for-high-write-scenarios)
- [🔬 Fuzzing & Simulation Suite](#-fuzzing--simulation-suite)
- [🤝 Contributing](#-contributing)
- [📄 License](#-license)

---

## 🚀 Key Features

* ⏰ **Immutable Vector Clocks**: Tracks causality, concurrency, and logical time ordering across node clusters using copy-on-write persistent maps. See [VectorClock.kt](ghostnode-core/src/main/kotlin/com/ghostnode/core/clock/VectorClock.kt).
* 🔄 **LWW-Element-Set CRDT**: A Last-Writer-Wins element set implementing Strong Eventual Consistency (SEC) with hybrid fallback (real-time timestamps + vector clocks) to merge collections deterministically. See [LWWElementSet.kt](ghostnode-core/src/main/kotlin/com/ghostnode/core/crdt/LWWElementSet.kt).
* 🌳 **Zero Full-Duplication JVM GC Performance**: Built on top of `kotlinx-collections-immutable` using Radix-Trie path copying. Mutation states share memory references, avoiding heap pressure.
* 🧩 **Pluggable Conflict Resolution**: Exposes a functional interface `ConflictResolver<T>` that allows customization of value-level conflict resolution using simple lambdas. See [LWWElementSetResolver.kt](ghostnode-core/src/main/kotlin/com/ghostnode/core/crdt/LWWElementSetResolver.kt).
* 🍃 **Spring Boot Autoconfiguration**: Rapidly configure, monitor, and prune CRDT instances using `@EnableGhostNode` annotation. See [EnableGhostNode.kt](ghostnode-spring-boot-starter/src/main/kotlin/com/ghostnode/spring/EnableGhostNode.kt).

---

## 📦 Installation

To import GhostNode libraries into your build configuration, reference the following Maven Central coordinates.

### Gradle (Kotlin DSL)

Add the dependency to your subproject's `build.gradle.kts`:

```kotlin
dependencies {
    // Core CRDT and Clock Library
    implementation("com.ghostnode:ghostnode-core:0.1.0")

    // Spring Boot Starter Integration (optional)
    implementation("com.ghostnode:ghostnode-spring-boot-starter:0.1.0")
}
```

### Gradle (Groovy DSL)

Add the dependency to your `build.gradle`:

```groovy
dependencies {
    // Core CRDT and Clock Library
    implementation 'com.ghostnode:ghostnode-core:0.1.0'

    // Spring Boot Starter Integration (optional)
    implementation 'com.ghostnode:ghostnode-spring-boot-starter:0.1.0'
}
```

### Maven

Add the dependency to your `pom.xml`:

```xml
<dependencies>
    <!-- Core CRDT and Clock Library -->
    <dependency>
        <groupId>com.ghostnode</groupId>
        <artifactId>ghostnode-core</artifactId>
        <version>0.1.0</version>
    </dependency>

    <!-- Spring Boot Starter Integration (optional) -->
    <dependency>
        <groupId>com.ghostnode</groupId>
        <artifactId>ghostnode-spring-boot-starter</artifactId>
        <version>0.1.0</version>
    </dependency>
</dependencies>
```

---

## 🛠️ Quick Start & Code Examples

### 1. Causality Tracking with Vector Clocks

Vector clocks establish logical ordering to determine if an event happened before, after, or concurrently with another event.

```kotlin
import com.ghostnode.core.clock.VectorClock
import com.ghostnode.core.clock.CausalOrder

// Initialize vector clocks for individual nodes
val clockA = VectorClock().increment("node-a")
val clockB = clockA.increment("node-a") // clockB causally follows clockA

println(clockA.relationTo(clockB)) // Output: BEFORE
println(clockB.relationTo(clockA)) // Output: AFTER

// Create concurrent events (neither causally dominates)
val clockC = VectorClock().increment("node-a")
val clockD = VectorClock().increment("node-b")

println(clockC.relationTo(clockD)) // Output: CONCURRENT
```

### 2. Conflict-free Replicated Data Types (LWW-Element-Set)

Add or remove items with specific physical system timestamps and logical clocks.

```kotlin
import com.ghostnode.core.clock.VectorClock
import com.ghostnode.core.crdt.LWWElementSet

val baseClock = VectorClock().increment("node-1")

// Add elements with wall-clock time and logical clock tracking
var clientState = LWWElementSet<String>()
    .add("item-1", timestamp = 1000L, clock = baseClock)
    .add("item-2", timestamp = 1002L, clock = baseClock.increment("node-1"))

println(clientState.elements()) // Output: [item-1, item-2]

// Perform a delete operation
clientState = clientState.remove("item-1", timestamp = 1005L, clock = baseClock.increment("node-1"))

println(clientState.elements()) // Output: [item-2]
```

### 3. Merging Divergent Replicas

When two offline nodes sync, GhostNode uses a deterministic resolver to merge their states automatically.

```kotlin
import com.ghostnode.core.clock.VectorClock
import com.ghostnode.core.crdt.LWWElementSet
import com.ghostnode.core.crdt.LWWElementSetResolver

val clockNodeA = VectorClock().increment("node-a")
val clockNodeB = VectorClock().increment("node-b")

val stateA = LWWElementSet<String>().add("apples", timestamp = 10L, clock = clockNodeA)
val stateB = LWWElementSet<String>().add("oranges", timestamp = 12L, clock = clockNodeB)

// Instantiate set resolver
val resolver = LWWElementSetResolver<String>()
val mergedState = resolver.resolve(stateA, stateB, clockNodeA, clockNodeB)

println(mergedState.elements()) // Output: [apples, oranges]
```

### 4. Custom Conflict Resolvers

Easily swap in custom tie-breaker algorithms using Kotlin SAM conversion lambdas:

```kotlin
import com.ghostnode.core.clock.VectorClock
import com.ghostnode.core.crdt.ConflictResolver

// Custom "highest-value wins" resolution for conflict states
val maxWinsResolver = ConflictResolver<Int> { local, remote, localClock, remoteClock ->
    when {
        localClock > remoteClock -> local
        remoteClock > localClock -> remote
        else -> maxOf(local, remote) // Tie-breaker logic for concurrent events
    }
}
```

---

## ⚙️ Production & Enterprise Capabilities

GhostNode is architected for enterprise-scale deployments, with out-of-the-box features to manage long-term state overhead and live telemetry:

### Spring Boot Integration
Enable background auto-configuration using `@EnableGhostNode`. Auto-registers the `GhostNodeRegistry`, registers Micrometer counters, and schedules periodic state compaction:
```kotlin
@SpringBootApplication
@EnableGhostNode // Automatically bootstraps register management and metrics
class Application
```

### Tombstone & State Compaction
To prevent unbounded memory growth of deleted records (tombstones), GhostNode supports automated compaction:
```kotlin
// Compactor retains metadata and removes tombstones older than 7 days
val compactedSet = baseSet.compact(maxAgeMs = 7 * 24 * 60 * 60 * 1000L)
```

### Telemetry & Observability
Integrates directly with **Micrometer**. Exposes essential runtime metrics:
*   `ghostnode.merge.duration`: Execution time for CRDT merge resolution.
*   `ghostnode.conflicts.resolved`: Count of concurrent data conflicts resolved.
*   `ghostnode.state.size`: Element size inside LWW-Element-Sets.

### Lazy Merging for High-Write Scenarios
By wrapping states in `LazyMergeSet.kt`, updates are appended to an internal lock-free concurrent queue. Merging is delayed until reading is required, optimizing performance for high write workloads.

---

## 🔬 Fuzzing & Simulation Suite

GhostNode ensures global state convergence under real-world network turbulence (out-of-order delivery, latency spikes, and partition splits).

Our test suite includes a property-based simulator that runs **2,000+ random mutations** across a 5-node distributed cluster, verifying that nodes converge to the identical state once partition issues resolve:

```bash
# Run the complete test suite
./gradlew test

# Run the property-based fuzz simulator specifically
./gradlew test --info --tests com.ghostnode.core.crdt.GhostNodeSimulator
```

---

## 🤝 Contributing

Contributions are welcome! Please see [CONTRIBUTING.md](CONTRIBUTING.md) for local environment setup, style guides, and PR verification rules.

---

## 📄 License

GhostNode is licensed under the Apache License 2.0. See [LICENSE](LICENSE) for more details.

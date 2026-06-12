<p align="center">
  <img src="assets/logo.svg" alt="GhostNode Logo" width="100"/>
</p>

<h1 align="center">GhostNode</h1>

<p align="center">
  <img src="assets/banner.png" alt="GhostNode Banner" width="800"/>
</p>

<p align="center">
  <strong>A high-performance, memory-optimized Kotlin library for Causal History Operation Logs (OR-Set CRDT) and database-level convergence guarantees in distributed and offline-first systems.</strong>
</p>

<p align="center">
  <a href="https://kotlinlang.org"><img src="https://img.shields.io/badge/Kotlin-2.1.21-purple.svg?style=flat&logo=kotlin" alt="Kotlin Version"/></a>
  <a href="https://www.oracle.com/java/technologies/downloads/"><img src="https://img.shields.io/badge/JDK-21-orange.svg" alt="JDK Version"/></a>
  <a href="https://opensource.org/licenses/Apache-2.0"><img src="https://img.shields.io/badge/License-Apache_2.0-blue.svg" alt="License"/></a>
  <a href="https://spring.io/projects/spring-boot"><img src="https://img.shields.io/badge/Spring%20Boot-3.4.2-brightgreen.svg?style=flat&logo=springboot" alt="Spring Boot Autoconfigure"/></a>
</p>

---

GhostNode provides the mathematical guarantees, data structures, and consistency models required to build **offline-first, highly available distributed applications**—such as point-of-sale (POS) terminal networks, collaborative editors, or multi-region synchronized databases—that converge to a single, deterministic state without the overhead of centralized consensus protocols (like Paxos or Raft).

By utilizing a Causal History Operation Log (OR-Set) consistency model, GhostNode tracks explicit causal relationships (dependencies) between mutations. This preserves history, tracks causality, and enables database-level convergence guarantees.

---

## 📖 Table of Contents

- [🚀 Key Features](#-key-features)
- [📦 Installation](#-installation)
  - [Gradle (Kotlin DSL)](#gradle-kotlin-dsl)
  - [Gradle (Groovy DSL)](#gradle-groovy-dsl)
  - [Maven](#maven)
- [🛠️ Quick Start & Code Examples](#️-quick-start--code-examples)
  - [1. Causality Tracking with Vector Clocks](#1-causality-tracking-with-vector-clocks)
  - [2. Causal Ledger Operation Log (OR-Set)](#2-causal-ledger-operation-log-or-set)
  - [3. Event Sourcing & Database Convergence](#3-event-sourcing--database-convergence)
  - [4. Multi-Model CRDTs (PN-Counter & OR-Map)](#4-multi-model-crdts-pn-counter--or-map)
  - [5. State Verification with Merkle Trees](#5-state-verification-with-merkle-trees)
- [⚙️ Production & Enterprise Capabilities](#️-production--enterprise-capabilities)
  - [Spring Boot Persistence Auto-Configuration](#spring-boot-persistence-auto-configuration)
  - [Telemetry & Observability](#telemetry--observability)
- [🤝 Contributing](#-contributing)
- [📄 License](#-license)

---

## 🚀 Key Features

* ⏰ **Immutable Vector Clocks**: Tracks causality, concurrency, and logical time ordering across node clusters using copy-on-write persistent maps. See [VectorClock.kt](ghostnode-core/src/main/kotlin/com/ghostnode/core/clock/VectorClock.kt).
* 🔄 **Causal History Log (OR-Set CRDT)**: Implements an Observed-Remove Set (OR-Set) logic. Mutations are modeled as `CausalOperation`s tracking explicit dependencies. Merging is a simple log union: `merge(other) = operations + other.operations`. See [CausalLedger.kt](ghostnode-core/src/main/kotlin/com/ghostnode/core/crdt/CausalLedger.kt).
* 🔢 **Multi-Model CRDTs (PN-Counter & OR-Map)**: Extends target edge states using state-based **Positive-Negative Counters** for inventory and **Observed-Remove Maps** for concurrent key-value order checklist states. See [PNCounter.kt](ghostnode-core/src/main/kotlin/com/ghostnode/core/crdt/PNCounter.kt) and [ORMap.kt](ghostnode-core/src/main/kotlin/com/ghostnode/core/crdt/ORMap.kt).
* 🌲 **Merkle Tree State Verification**: Automatically computes binary hash trees over local logs for instant $O(1)$ state convergence verification and highly efficient delta transfers. See [MerkleTree.kt](ghostnode-core/src/main/kotlin/com/ghostnode/core/crdt/MerkleTree.kt).
* 🌳 **Zero Full-Duplication JVM GC Performance**: Built on top of `kotlinx-collections-immutable` using Radix-Trie path copying. Mutation states share memory references, avoiding heap pressure.
* 💾 **Database Convergence Guarantees**: A transactional synchronization service that performs upserts of missing causal logs on relational databases, guaranteeing identical converged application states on all replicas. See [DatabaseConvergenceService.kt](ghostnode-spring-boot-starter/src/main/kotlin/com/ghostnode/spring/persistence/DatabaseConvergenceService.kt).
* 🧩 **Conditional Spring Boot Starter**: Auto-configures and boot-straps JPA scanning and database convergence repositories only when a `DataSource` is present. See [GhostNodeJpaAutoConfiguration.kt](ghostnode-spring-boot-starter/src/main/kotlin/com/ghostnode/spring/GhostNodeJpaAutoConfiguration.kt).

---

## 📦 Installation

To import GhostNode libraries into your build configuration, reference the following coordinates.

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

### 2. Causal Ledger Operation Log (OR-Set)

Add or remove items as explicit operations, linking observed causal dependency identifiers.

```kotlin
import com.ghostnode.core.crdt.CausalLedger
import com.ghostnode.core.crdt.CausalOperation
import com.ghostnode.core.crdt.OperationType

// 1. Initial State
var ledger = CausalLedger<String>()

// 2. Perform addition of 'espresso'
val op1 = CausalOperation(
    id = "client-a:1",
    type = OperationType.ADD,
    element = "espresso",
    timestamp = System.currentTimeMillis(),
    dependencies = emptySet()
)
ledger = ledger.applyOperation(op1)
println(ledger.lookup("espresso")) // Output: true

// 3. Remove 'espresso', explicitly referencing the addition it observed
val op2 = CausalOperation(
    id = "client-a:2",
    type = OperationType.REMOVE,
    element = "espresso",
    timestamp = System.currentTimeMillis(),
    dependencies = setOf("client-a:1")
)
ledger = ledger.applyOperation(op2)
println(ledger.lookup("espresso")) // Output: false
```

### 3. Event Sourcing & Database Convergence

Reconciliation of divergent nodes is mathematically guaranteed to converge by computing the union of local and remote database operations.

```kotlin
import com.ghostnode.core.crdt.CausalLedger

val ledgerA = CausalLedger<String>().applyOperation(op1)
val ledgerB = CausalLedger<String>().applyOperation(op3) // concurrent addition

// Event logs merge commutatively and deterministically
val converged = ledgerA.merge(ledgerB)
println(converged.elements()) // Converges to union of both additions
```

### 4. Multi-Model CRDTs (PN-Counter & OR-Map)

Track inventory changes using PN-Counters and check lists or options using OR-Maps.

```kotlin
import com.ghostnode.core.crdt.PNCounter
import com.ghostnode.core.crdt.ORMap

// --- Positive-Negative Counter for Inventory ---
var inventory = PNCounter()
inventory = inventory.increment("node-a", 10)
inventory = inventory.decrement("node-b", 3)

println(inventory.value) // Output: 7

// --- Observed-Remove Map for Table Orders ---
var orders = ORMap<String, String>()
orders = orders.put("node-a", "table-1", "espresso")
orders = orders.remove("node-a", "table-1")

println(orders.get("table-1")) // Output: null
```

### 5. State Verification with Merkle Trees

Validate cluster convergence efficiently using binary Merkle Root checks.

```kotlin
import com.ghostnode.core.crdt.MerkleTree

// Build Merkle Trees representing local logs
val treeA = MerkleTree.build(ledgerA.operations.values)
val treeB = MerkleTree.build(converged.operations.values)

// If roots are identical, states are identical
println(treeA.rootHash == treeB.rootHash) // Output: false (before sync)
```

---

## ⚙️ Production & Enterprise Capabilities

### Spring Boot Persistence Auto-Configuration

GhostNode provides conditional configuration to bootstrap JPA convergence services. Just enable `@EnableGhostNode` inside your application classes.

```kotlin
@SpringBootApplication
class Application
```

When a database connection pool (`DataSource`) is detected on the classpath, Spring Boot boots the transactional `DatabaseConvergenceService` bean. Database-free minimal applications are configured seamlessly without database scans.

### Telemetry & Observability

GhostNode is pre-instrumented with **Micrometer** telemetry. When a `MeterRegistry` bean is available, metrics such as CRDT state sizes, merge duration timers, and conflict-resolution counters are registered automatically.

---

## 🤝 Contributing

Contributions are welcome! Please see [CONTRIBUTING.md](CONTRIBUTING.md) for local environment setup, style guides, and PR verification rules.

---

## 📄 License

GhostNode is licensed under the Apache License 2.0. See [LICENSE](LICENSE) for more details.

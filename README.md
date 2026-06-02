# GhostNode

GhostNode is a lightweight, immutable Kotlin library designed for logical time tracking, causality analysis, and state-based Conflict-free Replicated Data Type (CRDT) operations. 

It provides the mathematical guarantees required to build offline-first, distributed applications (such as POS terminals, collaborative editors, or multi-region synchronized state containers) that converge to a single truth without central coordination.

For details on the architecture, consistency models, and structural sharing designs, see [DESIGN.md](DESIGN.md).

---

## Key Features

- **Immutable Vector Clocks**: Tracks causality and logical ordering across distributed nodes using memory-efficient copy-on-write persistent structures.
- **LWW-Element-Set CRDT**: A Last-Writer-Wins element set that uses hybrid fallback (real-time timestamps + vector clocks) to merge divergent collections deterministically.
- **Generic Conflict Resolution**: Functional interface `ConflictResolver<T>` allows you to write custom convergence algorithms using simple Kotlin lambdas (via SAM conversion).
- **Optimized for JVM Heap**: Utilizes `kotlinx-collections-immutable` to implement path-copying (Radix Trie structural sharing) for zero full-duplication garbage-collection pressure.

---

## Code Examples

### 1. Causality Tracking with Vector Clocks

```kotlin
import com.ghostnode.core.clock.VectorClock
import com.ghostnode.core.clock.CausalOrder

// Initialize clocks for different nodes
val clockA = VectorClock().increment("node-a")
val clockB = clockA.increment("node-a") // clockB is AFTER clockA

println(clockA.relationTo(clockB)) // Output: BEFORE
println(clockB.relationTo(clockA)) // Output: AFTER

// Concurrent changes (neither causally dominates the other)
val clockC = VectorClock().increment("node-a")
val clockD = VectorClock().increment("node-b")

println(clockC.relationTo(clockD)) // Output: CONCURRENT
```

### 2. Working with the LWW-Element-Set

```kotlin
import com.ghostnode.core.clock.VectorClock
import com.ghostnode.core.crdt.LWWElementSet

val clock = VectorClock().increment("node-1")

// Add elements with physical wall-clock timestamps and causal clock context
var set = LWWElementSet<String>()
    .add("apples", timestamp = 1000L, clock = clock)
    .add("bananas", timestamp = 1002L, clock = clock.increment("node-1"))

println(set.elements()) // Output: [apples, bananas]

// Remove an element
set = set.remove("apples", timestamp = 1005L, clock = clock.increment("node-1"))

println(set.elements()) // Output: [bananas]
```

### 3. Merging Divergent Sets

```kotlin
import com.ghostnode.core.clock.VectorClock
import com.ghostnode.core.crdt.LWWElementSet
import com.ghostnode.core.crdt.LWWElementSetResolver

val clockA = VectorClock().increment("node-a")
val clockB = VectorClock().increment("node-b")

val setA = LWWElementSet<String>().add("apples", timestamp = 10L, clock = clockA)
val setB = LWWElementSet<String>().add("oranges", timestamp = 12L, clock = clockB)

// Resolve state conflicts (automatically combines additions and removals)
val resolver = LWWElementSetResolver<String>()
val merged = resolver.resolve(setA, setB, clockA, clockB)

println(merged.elements()) // Output: [apples, oranges]
```

### 4. Implementing Custom Resolvers

```kotlin
import com.ghostnode.core.clock.VectorClock
import com.ghostnode.core.crdt.ConflictResolver

// Custom max-wins integer resolver using Kotlin lambda SAM conversion
val maxResolver = ConflictResolver<Int> { local, remote, localClock, remoteClock ->
    when {
        localClock > remoteClock -> local
        remoteClock > localClock -> remote
        else -> maxOf(local, remote) // Tie-break
    }
}
```

---

## Build & Run Tests

This project is built using Gradle and requires **JDK 17** or higher.

To build the project and execute all unit tests, run the following command in the root directory:

```bash
./gradlew test
```

To run the property-based verification simulation suite and check the log outputs:

```bash
./gradlew test --info --tests com.ghostnode.core.crdt.GhostNodeSimulator
```

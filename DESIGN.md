# GhostNode Design & Architecture

This document outlines the core architecture, consistency guarantees, and memory optimization strategies implemented in **GhostNode**.

---

## Architectural Diagram

The diagram below maps the synchronization flow between Edge Devices and Cloud Storage. It illustrates how the system manages state transitions, utilizes Vector Clocks to trace the causal history, and resolves conflicting edits.

```mermaid
flowchart TB
    %% Swimlane Definitions
    subgraph DeviceA ["Edge Device A"]
        A_Start(["Initial State\nClock: A:0, B:0"])
        A_Offline["Goes Offline\nAdds 'Apple'"]
        A_Clock["Increment Clock\nClock: A:1, B:0"]
        
        A_Start --> A_Offline --> A_Clock
    end

    subgraph DeviceB ["Edge Device B"]
        B_Start(["Initial State\nClock: A:0, B:0"])
        B_Offline["Goes Offline\nAdds 'Banana'"]
        B_Clock["Increment Clock\nClock: A:0, B:1"]
        
        B_Start --> B_Offline --> B_Clock
    end

    subgraph Cloud ["Cloud Storage (Sync & Resolution)"]
        Sync{"Offline Sync\nConflict Detected!"}
        
        %% Consistency Model Note
        ConsistencyNote["Consistency Model:\nEventual Consistency\n(Strong Convergence via LWW-Element-Set)"]
        style ConsistencyNote fill:#f9f,stroke:#333,stroke-width:2px
        
        %% Decision Points
        DecisionTS{"Decision Point:\nWhich timestamp is newer?"}
        DecisionClock{"Decision Point:\nAre Vector Clocks causally ordered?"}
        Deterministic["Fallback:\nDeterministic Tie-breaker"]
        
        %% Resolved states
        ResolveA["Resolved State:\nDevice A's edit wins"]
        ResolveB["Resolved State:\nDevice B's edit wins"]
        
        FinalSync["Final State Merged\nClock: A:1, B:1"]
        
        Sync --- ConsistencyNote
    end

    %% Flow transitions between Swimlanes
    A_Clock -->|Push state A:1, B:0| Sync
    B_Clock -->|Push state A:0, B:1| Sync
    
    Sync --> DecisionTS
    
    %% Decision Flow
    DecisionTS -->|"Timestamp A > Timestamp B"| ResolveA
    DecisionTS -->|"Timestamp B > Timestamp A"| ResolveB
    DecisionTS -->|"Timestamps Equal"| DecisionClock
    
    DecisionClock -->|"Clock A > Clock B (A is newer)"| ResolveA
    DecisionClock -->|"Clock B > Clock A (B is newer)"| ResolveB
    DecisionClock -->|"Clocks Concurrent (Tie)"| Deterministic
    
    Deterministic -->|"Deterministic Choice"| ResolveA
    
    ResolveA --> FinalSync
    ResolveB --> FinalSync
    
    %% Sync back to Edge
    FinalSync -->|Sync back state| A_End(["Device A Updated"])
    FinalSync -->|Sync back state| B_End(["Device B Updated"])
```

> [!IMPORTANT]
> **Offline-First Resilience**
> This diagram illustrates how GhostNode ensures that even if a POS terminal is disconnected for the duration of a dinner rush, it will converge to the global truth without human intervention upon reconnection.

---

## Technical Specifications

### 1. Consistency Model: Strong Eventual Consistency (SEC)

GhostNode implements the **LWW-Element-Set** (Last-Writer-Wins) CRDT to achieve **Strong Eventual Consistency (SEC)**. 

Unlike traditional eventual consistency (where replicas can temporarily diverge and require manual conflict resolution or custom rules), SEC guarantees that any two replicas that have received the same set of updates will converge to the **exact same state automatically**. This process does not require expensive consensus coordination protocols (like Paxos or Raft).

This convergence is guaranteed by design because the merge operation (`LWWElementSet.merge`) behaves as a mathematical semi-lattice. In simple terms, merging state satisfies three key algebraic properties:

1. **Commutativity** (Order doesn't matter): 
   * `Merge(State A, State B) == Merge(State B, State A)`
   * Replicas can receive updates in different orders and still end up with the same final state.
   
2. **Associativity** (Grouping doesn't matter): 
   * `Merge(Merge(State A, State B), State C) == Merge(State A, Merge(State B, State C))`
   * The grouping of state merges across network segments has no impact on the final outcome.
   
3. **Idempotency** (Duplicates don't matter): 
   * `Merge(State A, State A) == State A`
   * Receiving the same update multiple times (e.g., due to network retries) does not alter the state.

---

### 2. JVM Memory Optimization: Structural Sharing

Because distributed systems generate a high volume of mutation states, naive copy-on-write strategies would put severe pressure on the JVM garbage collector (GC) by allocating full copies of sets for every addition or removal.

GhostNode mitigates this using `kotlinx-collections-immutable`'s **`PersistentMap`** under the hood:
*   **Path Copying (Trie-based)**: When an element is added to `addSet` or `removeSet`, only the affected path of the underlying Radix Trie is copied. The rest of the tree is shared by reference with the previous version.
*   **Zero Full Duplication**: Merges and updates do not require duplicating the entire data set, avoiding massive object allocations. This optimization is critical for memory-constrained platforms, such as Android edge terminals or high-throughput JVM backend nodes.

---

### 3. Verification & Simulation Suite

To ensure GhostNode is production-ready for high-scale, distributed environments, we have implemented a rigorous simulation suite ([GhostNodeSimulator.kt](file:///Users/mathan/Projects/GhostNode/ghostnode-core/src/test/kotlin/com/ghostnode/core/crdt/GhostNodeSimulator.kt)).

#### Key Verification Metrics:
- **Idempotency & Commutativity**: Verified via randomized, sequence-independent merge operations.
- **Property-Based Fuzz Testing**: Executed 2,000+ random mutations across a 5-node cluster to ensure global state convergence regardless of network jitter, partitioning, or out-of-order packet delivery.
- **Deterministic Convergence**: The simulation suite uses a seeded random generator, ensuring that the resolution logic is fully reproducible and verifiable.

To run the verification suite and inspect the simulation logs:
```bash
./gradlew test --info --tests com.ghostnode.core.crdt.GhostNodeSimulator
```

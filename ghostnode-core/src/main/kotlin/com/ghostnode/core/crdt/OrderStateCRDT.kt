package com.ghostnode.core.crdt

import kotlinx.serialization.Serializable

@Serializable
enum class OrderStatus {
    PENDING, PREPARING, SERVED, CANCELLED
}

@Serializable
data class OrderMutation(
    val orderId: String,
    val items: List<String> = emptyList(),
    val status: OrderStatus = OrderStatus.PENDING
)

data class Order(
    val id: String,
    val items: List<String>,
    val status: OrderStatus
)

/**
 * A Restaurant POS domain-specific CRDT that enforces business invariants
 * on top of a Causal History Log (OR-Set).
 */
class OrderStateCRDT(
    val ledger: CausalLedger<OrderMutation> = CausalLedger()
) {
    fun applyOperation(op: CausalOperation<OrderMutation>): OrderStateCRDT {
        return OrderStateCRDT(ledger.applyOperation(op))
    }

    fun merge(other: OrderStateCRDT): OrderStateCRDT {
        return OrderStateCRDT(ledger.merge(other.ledger))
    }

    /**
     * Reconstructs the state of all orders, enforcing the invariant that
     * once an order has been marked as SERVED, it cannot be REMOVED/deleted.
     */
    fun getActiveOrders(): Map<String, Order> {
        val opsByOrder = ledger.operations.values.groupBy { it.element.orderId }
        val result = mutableMapOf<String, Order>()

        for ((orderId, ops) in opsByOrder) {
            val adds = ops.filter { it.type == OperationType.ADD }
            val removes = ops.filter { it.type == OperationType.REMOVE }

            val sortedAdds = adds.sortedWith(compareBy({ it.timestamp }, { it.id }))
            if (sortedAdds.isEmpty()) continue

            // Reconstruct status and items in order
            val currentItems = mutableListOf<String>()
            var currentStatus = OrderStatus.PENDING
            var hasBeenServed = false

            for (add in sortedAdds) {
                currentItems.addAll(add.element.items)
                currentStatus = add.element.status
                if (currentStatus == OrderStatus.SERVED) {
                    hasBeenServed = true
                }
            }

            // Enforce business logic invariant: if the order has ever been marked as SERVED,
            // ignore any REMOVE causal dependencies targeting it.
            if (hasBeenServed) {
                result[orderId] = Order(orderId, currentItems, currentStatus)
            } else {
                // Standard Observed-Remove checks
                val removedAddIds = removes.flatMap { it.dependencies }.toSet()
                val activeAdds = sortedAdds.filter { it.id !in removedAddIds }
                if (activeAdds.isNotEmpty()) {
                    result[orderId] = Order(orderId, currentItems, currentStatus)
                }
            }
        }
        return result
    }
}

package com.ghostnode.spring.persistence

import com.ghostnode.core.crdt.CausalOperation
import com.ghostnode.core.crdt.OperationType
import jakarta.persistence.CollectionTable
import jakarta.persistence.ElementCollection
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.Table

/**
 * JPA entity representing a persisted CausalOperation.
 */
@Entity
@Table(name = "ghostnode_causal_operations")
class CausalOperationEntity(
    @Id
    var id: String = "",

    @Enumerated(EnumType.STRING)
    var type: OperationType = OperationType.ADD,

    var element: String = "",

    var timestamp: Long = 0L,

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
        name = "ghostnode_operation_dependencies",
        joinColumns = [JoinColumn(name = "operation_id")]
    )
    var dependencies: Set<String> = mutableSetOf()
) {
    fun toModel(): CausalOperation<String> {
        return CausalOperation(
            id = id,
            type = type,
            element = element,
            timestamp = timestamp,
            dependencies = dependencies
        )
    }

    companion object {
        fun fromModel(model: CausalOperation<String>): CausalOperationEntity {
            return CausalOperationEntity(
                id = model.id,
                type = model.type,
                element = model.element,
                timestamp = model.timestamp,
                dependencies = model.dependencies.toMutableSet()
            )
        }
    }
}

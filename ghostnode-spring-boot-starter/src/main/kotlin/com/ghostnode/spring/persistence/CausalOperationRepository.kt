package com.ghostnode.spring.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

/**
 * Spring Data JPA Repository for persisting and retrieving CausalOperationEntity.
 */
@Repository
interface CausalOperationRepository : JpaRepository<CausalOperationEntity, String>

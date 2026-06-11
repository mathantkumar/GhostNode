package com.ghostnode.spring

import com.ghostnode.spring.persistence.CausalOperationRepository
import com.ghostnode.spring.persistence.DatabaseConvergenceService
import jakarta.persistence.EntityManager
import javax.sql.DataSource
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.context.annotation.Bean
import org.springframework.data.jpa.repository.config.EnableJpaRepositories

@AutoConfiguration
@ConditionalOnClass(EntityManager::class, EnableJpaRepositories::class)
@ConditionalOnBean(DataSource::class)
@EntityScan("com.ghostnode.spring.persistence")
@EnableJpaRepositories("com.ghostnode.spring.persistence")
open class GhostNodeJpaAutoConfiguration {

    @Bean
    open fun databaseConvergenceService(
        repository: CausalOperationRepository
    ): DatabaseConvergenceService {
        return DatabaseConvergenceService(repository)
    }
}

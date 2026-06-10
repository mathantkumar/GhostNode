package com.ghostnode.spring

import org.springframework.context.annotation.Import
import java.lang.annotation.Inherited

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
@Inherited
@Import(GhostNodeAutoConfiguration::class)
annotation class EnableGhostNode

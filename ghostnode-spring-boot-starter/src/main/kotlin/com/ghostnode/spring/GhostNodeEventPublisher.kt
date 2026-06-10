package com.ghostnode.spring

import com.ghostnode.core.crdt.LWWElementSet
import org.springframework.context.ApplicationEvent
import org.springframework.context.ApplicationEventPublisher

/**
 * Native Spring ApplicationEvent published whenever a merge operation occurs.
 */
class GhostNodeMergeEvent<E>(
    source: Any,
    val resolvedState: LWWElementSet<E>
) : ApplicationEvent(source)

/**
 * Event publisher helper that hooks into Spring's Event Bus.
 */
class GhostNodeEventPublisher(private val publisher: ApplicationEventPublisher) {
    fun <E> publishMerge(resolvedState: LWWElementSet<E>) {
        publisher.publishEvent(GhostNodeMergeEvent(this, resolvedState))
    }
}

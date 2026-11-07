package io.github.vlsergey.recommend4me.tag

import org.jooq.exception.DataAccessException
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("io.github.vlsergey.recommend4me.tag.MergedAnswers")

/**
 * The user's answers of every merge not done moved to the tag kept, in every source — the earliest merge first, a chain
 * of merges in its order — and the merge done once they all are. A source whose database fails leaves its merge to the
 * next start: moving again what was moved is no change.
 */
fun moveMergedAnswers(scope: TagScope, facet: String) {
    scope.tags.relations.pendingMerges().forEach { (from, into) ->
        try {
            scope.sources.forEach { s ->
                s.corrections.moveFacetValues(facet, listOf(TagRepository.key(from)), TagRepository.key(into))
            }
            scope.tags.relations.answersMoved(from)
        } catch (e: DataAccessException) {
            log.warn(
                "{}: the answers on tag {} merged into {} were not moved, left to the next start: {}",
                scope.id,
                from,
                into,
                e.message,
                e,
            )
        }
    }
}

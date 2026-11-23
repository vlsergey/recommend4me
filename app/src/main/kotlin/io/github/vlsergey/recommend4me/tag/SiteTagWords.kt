package io.github.vlsergey.recommend4me.tag

import io.github.vlsergey.recommend4me.api.model.TaskKind
import io.github.vlsergey.recommend4me.item.SiteTagParentChangeEvent
import io.github.vlsergey.recommend4me.item.SiteTagSynonymChangeEvent
import io.github.vlsergey.recommend4me.task.Task
import io.github.vlsergey.recommend4me.task.Tasks
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

private val log = LoggerFactory.getLogger(SiteTagWords::class.java)

/**
 * WHAT A SITE SAYS OF ITS TAGS BETWEEN THEM, of a tag's own page — the tags of AO3 (owner, 2026-11-23): a synonym of a
 * tag, another name the site wrangled into it, is of the tag's normalized tag — its own of it alone merged into that
 * one —, and a tag the site puts under another — a metatag, a subtag — has its normalized tag under the other's. Done
 * of what the site tells anew and of a site's tag bound anew alone, once: what the user says after it stands, a page
 * read again changing nothing. A synonym of no tag of its own yet is bound with its tag ([TagNormalization]).
 *
 * Done in the queue: the user's word on two tags reads their works ([TagEditing]), seconds of a large one, which the
 * reading of a page does not wait for. What is to be done is kept with the work ([Tasks.keys]): a site, a kind and the
 * two keys of its tags.
 */
@Component
class SiteTagWords(
    private val tags: Tags,
    private val normalization: TagNormalization,
    private val editing: TagEditing,
    private val tasks: Tasks,
) {

    // What the last run of the application left undone of it: planned again by the queue at its start
    init {
        tasks.resumption.resumes(WORK.what) { _, reason -> plan(reason) }
    }

    @EventListener
    fun synonymsTold(event: SiteTagSynonymChangeEvent) =
        want(event.told.map { (synonym, key) -> Said(SYNONYM, event.source, synonym, key) }, "synonyms")

    @EventListener
    fun parentsTold(event: SiteTagParentChangeEvent) =
        want(event.told.map { (key, parent) -> Said(UNDER, event.source, key, parent) }, "tagParents")

    /** The sites' tags bound anew: what their site told before of the tags above and under them. */
    @EventListener
    fun bound(event: SiteTagBindingChangeEvent) {
        val scope = tags.scope()
        val s = scope?.source(event.source)
        val facet = s?.let { normalization.siteFacetOf(it, scope.facet) }
        if (s == null || facet == null) return
        val above = s.items.parentsOf(facet, event.keys).flatMap { (key, of) -> of.map { key to it } }
        val below = s.items.childrenOf(facet, event.keys).flatMap { (key, of) -> of.map { it to key } }
        want((above + below).toSet().map { (key, parent) -> Said(UNDER, event.source, key, parent) }, "tags")
    }

    private fun want(said: List<Said>, reason: String) {
        if (said.isEmpty()) return
        tasks.keys.want(WORK, said.map(Said::toString))
        plan(reason)
    }

    private fun plan(reason: String) =
        tasks.plan(WORK, Instant.now(), reason) {
            val taken = tasks.keys.wanted(WORK)
            val scope = tags.scope()
            if (scope != null) taken.mapNotNull(Said::parse).forEach { done(scope, it) }
            tasks.keys.done(WORK, taken)
        }

    private fun done(scope: TagScope, said: Said) {
        val bound = scope.tags.bindings()
        val ref = SiteTagRef(said.source, said.key)
        val tag = bound[ref]
        val other = bound[SiteTagRef(said.source, said.other)]
        if (tag == null || other == null || tag == other) return
        if (said.kind == SYNONYM) synonym(scope, said, ref, tag, other, alone = bound.count { it.value == tag } == 1)
        else under(scope, said, tag, other)
    }

    /** The synonym's tag of it [alone] merged into the tag, all the user said of it with it; else it alone moved. */
    private fun synonym(scope: TagScope, said: Said, ref: SiteTagRef, tag: Long, other: Long, alone: Boolean) {
        if (alone)
            editing.relate(scope, scope.facet, TagRepository.key(other), TagRepository.key(tag), TagRelation.MERGED)
        else editing.bind(scope, scope.facet, ref, TagRepository.key(other))
        log.info("{} of {}, a synonym of {}: of tag {} now, was {}", said.key, said.source, said.other, other, tag)
    }

    /** The [tag] put under [other]: of tags not one above or under the other already, not told apart by the user. */
    private fun under(scope: TagScope, said: Said, tag: Long, other: Long) {
        val key = TagRepository.key(tag)
        val otherKey = TagRepository.key(other)
        val hierarchy = TagHierarchy(scope.tags.relations.parents())
        val apart = TagRepository.pair(tag, other) in scope.tags.relations.distinct()
        if (apart || otherKey in hierarchy.ancestors(key) || key in hierarchy.ancestors(otherKey)) return
        editing.relate(scope, scope.facet, otherKey, key, TagRelation.CHILD)
        log.info("{} of {} under {}: tag {} under {}", said.key, said.source, said.other, tag, other)
    }

    /** What a site said of its tags [key] and [other]: [key] a synonym of [other], or under it. */
    private data class Said(val kind: String, val source: String, val key: String, val other: String) {
        override fun toString() = listOf(kind, source, key, other).joinToString(SEPARATOR)

        companion object {
            fun parse(text: String): Said? {
                val parts = text.split(SEPARATOR).takeIf { it.size == PARTS } ?: return null
                val (kind, source, key) = parts
                return Said(kind, source, key, parts.last())
            }
        }
    }

    private companion object {
        val WORK = Task(TaskKind.RELATE_TAGS_BY_SITES)
        const val SYNONYM = "synonym"
        const val UNDER = "under"
        const val SEPARATOR = "\t"
        const val PARTS = 4
    }
}

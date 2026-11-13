package io.github.vlsergey.recommend4me.tag

import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.item.ItemRemoval
import io.github.vlsergey.recommend4me.source.FacetValue
import io.github.vlsergey.recommend4me.source.ItemStore
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.workload.Work
import io.github.vlsergey.recommend4me.workload.Workload
import jakarta.annotation.PreDestroy
import java.util.concurrent.Executors
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component

private val log = LoggerFactory.getLogger(KeptOutWorks::class.java)

/** The works kept now a tag keeping its works out would remove, and the graded among them. */
class KeptOutCount(val works: Int, val graded: Int)

/**
 * WORKS KEPT OUT OF THE LIBRARY BY THEIR TAGS (user, 2026-11-13), by the user's word alone, never by the code: a
 * normalized tag the user says keeps its works out ([TagKeepOut.set]) — and every tag under it — of every site, and a
 * site's own tags the user's setting of the site forbids to import ([SiteTagsKeptOut]). One path for every site: the
 * moment any site writes a work's tags — a page read in the browser, a work downloaded, a catalogue crawled — and one
 * of them is such, the work is removed with everything of it ([ItemRemoval]), and the site's other writes of it are not
 * kept ([keepsOut]). Said so, every work of them kept now is removed in the background ([removeKept],
 * [removeForbidden]). Of the sites' own tags alone: a tag the user or the model gave a work keeps nothing out.
 */
@Component
class KeptOutWorks(
    private val normalization: TagNormalization,
    /** Got when asked for: the removal reads the sources, and the sources' writes ask this. */
    private val removal: ObjectProvider<ItemRemoval>,
    private val workload: Workload,
    private val forbidden: SiteTagsKeptOut,
) {
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "kept-out").apply { isDaemon = true } }

    /**
     * Whether the values of the [facet] the source [store] just wrote keep their work out: the facet the source's tags
     * are, and a value of it bound to a tag that keeps its works out, or to one under such. Its tags new are bound by
     * then: the write's own event binds them first.
     */
    fun keepsOut(store: SourceStore, facet: String, values: List<FacetValue>): Boolean {
        val scope = normalization.scope()
        val own = scope?.let { normalization.siteFacetOf(store, it.facet) }
        if (scope == null || own != facet || values.isEmpty()) return false
        val site = forbidden.of(store.id)
        val out = keptOut(scope)
        val siteTags = if (out.isEmpty()) emptySet() else scope.tags.keepOut.siteTags(store.id, out)
        return values.any { it.key in siteTags || forbidden.forbids(site, it) }
    }

    /** Whether the source [sourceId] has tags of its own: its setting of the tags forbidden to import is there. */
    fun hasTags(sourceId: String): Boolean {
        val scope = normalization.scope()
        val store = scope?.source(sourceId)
        return store != null && normalization.siteFacetOf(store, scope.facet) != null
    }

    /** The writes of the source [store] [items], the works their tags keep out not kept ([KeptOutItems]). */
    fun guard(store: SourceStore, items: ItemStore): ItemStore =
        KeptOutItems(items) { facet, values -> keepsOut(store, facet, values) }

    /** Every tag that keeps its works out, and every tag under one: the keys. */
    private fun keptOut(scope: TagScope): Set<String> {
        val roots = scope.tags.keepOut.tags()
        if (roots.isEmpty()) return roots
        val hierarchy = TagHierarchy(scope.tags.relations.parents())
        return roots + roots.flatMap { hierarchy.descendants(it) }
    }

    /**
     * The works of every site kept now that the tag [tag] and the tags under it keep out: of every source, its items.
     */
    private fun keptOfTag(scope: TagScope, tag: String): Map<SourceStore, Set<String>> {
        val keys = setOf(tag) + TagHierarchy(scope.tags.relations.parents()).descendants(tag)
        return scope.sources.associateWith { s ->
            val facet = normalization.siteFacetOf(s, scope.facet)
            val siteTags = scope.tags.keepOut.siteTags(s.id, keys)
            if (facet == null || siteTags.isEmpty()) emptySet()
            else s.items.itemsWith(facet, siteTags).values.flatten().toSet()
        }
    }

    /** How many works the tag [tag] keeping its works out would remove, and how many of them the user graded. */
    fun count(scope: TagScope, tag: String): KeptOutCount {
        val kept = keptOfTag(scope, tag)
        return KeptOutCount(
            kept.values.sumOf { it.size },
            kept.entries.sumOf { (s, ids) -> s.ratings.gradedItems().count { it in ids } },
        )
    }

    /**
     * Every work kept now that the tag [tag] keeps out removed, in the background — in the queue of the computations,
     * what the models made of the works going with them.
     */
    fun removeKept(scope: TagScope, tag: String) = removed("tag $tag", "tagKeptOut") { keptOfTag(scope, tag) }

    /** Every work of the source [sourceId] kept now that its setting of the tags forbidden to import names removed. */
    fun removeForbidden(sourceId: String) =
        removed("the tags of $sourceId forbidden", "tagsForbidden") {
            val scope = normalization.scope()
            val store = scope?.source(sourceId)
            val facet = store?.let { normalization.siteFacetOf(it, scope.facet) }
            val keys = if (facet == null) emptySet() else forbidden.keysOf(store, facet, forbidden.of(sourceId))
            if (store == null || keys.isEmpty()) emptyMap()
            else mapOf(store to store.items.itemsWith(facet!!, keys).values.flatten().toSet())
        }

    /**
     * The works [kept] finds removed in the background, in the queue of the computations — what the models made of them
     * going with them — for the [reason]; [what] in the log.
     */
    private fun removed(what: String, reason: String, kept: () -> Map<SourceStore, Set<String>>) {
        worker.execute {
            // The worker's task: whatever fails is told in the log, and the word said again removes what is left
            @Suppress("TooGenericExceptionCaught")
            try {
                workload.run(Work("worksKeptOut"), mapOf(reason to 1)) {
                    val found = kept()
                    found.forEach { (s, ids) -> ids.forEach { removal.getObject().remove(ItemKey(s.id, it)) } }
                    log.info("{} keep their works out: {} works removed", what, found.values.sumOf { it.size })
                }
            } catch (e: Exception) {
                log.error("the works of {} were not removed", what, e)
            }
        }
    }

    @PreDestroy fun shutdown() = worker.shutdownNow()
}

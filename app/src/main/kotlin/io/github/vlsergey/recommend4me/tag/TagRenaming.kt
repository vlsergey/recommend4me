package io.github.vlsergey.recommend4me.tag

import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.item.ItemsChanged
import io.github.vlsergey.recommend4me.item.SynonymsSet
import io.github.vlsergey.recommend4me.rating.GradesChanged
import io.github.vlsergey.recommend4me.workload.Work
import io.github.vlsergey.recommend4me.workload.Workload
import jakarta.annotation.PreDestroy
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Service
import tools.jackson.databind.json.JsonMapper

private val log = LoggerFactory.getLogger(TagRenaming::class.java)

/**
 * THE NAMES WORKED OUT, IN THE BACKGROUND: every tag counted — every work of every source — and what is shown of it
 * kept: the names worked out, the works of every tag and of every site's tag, the sources of its sites' tags, and the
 * offers of the tags' graph. The screen of the tags reads them ([Tags.stats], [HierarchyOffers]), not the works.
 *
 * Worked out after the works and their grades change and after the user's word on the tags, once the changes have
 * paused: the marking waits for nothing that computes (user, 2026-10-16).
 */
@Service
class TagRenaming(
    private val tags: Tags,
    private val normalization: TagNormalization,
    private val above: EntriesAbove,
    private val workload: Workload,
    private val json: JsonMapper,
    /** The languages a tag's entry is asked in, the user's first. */
    @Value("\${recommend4me.languages:ru,en}") private val languages: List<String>,
) {
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "tag-names").apply { isDaemon = true }
    }
    private val pending = ConcurrentHashMap<String, ScheduledFuture<*>>()

    /** Every tag counted now and what is shown of it kept. */
    fun rename(scope: TagScope, facet: String) = workload.run(NAMING) { renamed(scope, facet) }

    private fun renamed(scope: TagScope, facet: String) {
        val started = System.currentTimeMillis()
        val counted = workload.run(Work("tagsCounted")) { TagCounting(scope, facet, normalization).counted() }
        val n = tags.naming(scope, counted)
        val stats = n.stats
        // Of the application's working: the user's own name is shown over it, not kept in it
        scope.tags.stats.replaceCalculated(
            stats.values.associate { t -> t.id to TagNaming.name(t, null, n.cataloguedOf(t.key)) }
        )
        scope.tags.stats.keepStats(
            sites =
                stats.values
                    .flatMap { t -> t.variants.values }
                    .associate { v -> v.site to (v.name to Counts(v.uses.works, v.uses.graded, v.uses.liked)) },
            tags = stats.values.associate { t -> t.id to TagCount(t.counts, t.sources) },
        )
        val offers = HierarchyOffersWorkedOut(scope, n, languages).all()
        scope.tags.relations.keepOffers(json.writeValueAsString(offers), Instant.now())
        log.info(
            "{}: names, works and offers of {} tags counted and kept in {} ms",
            scope.id,
            stats.size,
            System.currentTimeMillis() - started,
        )
    }

    /** Whether the names, the works and the offers of the tags are planned, waiting or worked out now. */
    fun due(): Boolean = workload.due(NAMING)

    /**
     * The tags changed by the user's word: THE WORD WRITTEN AND ANSWERED AT ONCE — the marking waits for nothing that
     * computes (user, 2026-10-16): the names and the counts of every tag worked out again in the queue of the
     * computations after the quiet ([schedule]), shown on the counts kept till then; their works read again — their
     * chances, their search — and the model of the grades trained again.
     */
    fun changed(scope: TagScope, items: Collection<ItemKey>) {
        schedule("tags")
        tags.told(scope, items)
    }

    /**
     * The sites' new tags bound, and the names worked out again, shortly after the last of a quick series of changes.
     */
    fun schedule(reason: String) {
        pending.compute(RENAMING) { _, old ->
            old?.cancel(false)
            workload.plan(NAMING, Instant.now().plusSeconds(RENAME_DELAY_SECONDS), reason)
            scheduler.schedule({ bindAndRename() }, RENAME_DELAY_SECONDS, TimeUnit.SECONDS)
        }
    }

    /** The sites' new tags bound and the names worked out: a task of the background, which logs what fails. */
    // The background's loop: whatever fails is told in the log, and the next change plans it again
    @Suppress("TooGenericExceptionCaught")
    private fun bindAndRename() {
        try {
            val scope = tags.scope() ?: return
            normalization.bindNew()
            // Planned till it begins: the offers read meanwhile are told to be worked out anew
            workload.run(NAMING) { renamed(scope, scope.facet) }
        } catch (e: Exception) {
            log.warn("The tags were not bound and named: {}", e.message, e)
        }
    }

    @EventListener(GradesChanged::class) fun gradesChanged() = schedule("grade")

    @EventListener(ItemsChanged::class) fun itemsChanged() = schedule("items")

    /**
     * A synonym is a site's tag of its own, bound to the tag of the value it is a synonym of ([TagNormalization]),
     * shown as a variant of it.
     */
    @EventListener(SynonymsSet::class) fun synonymsSet() = schedule("synonyms")

    /** After the tags are normalized on start ([TagNormalization.start]). */
    @Order(TagNormalization.AFTER_NORMALIZATION)
    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        val scope = tags.scope() ?: return
        // The answers of a merge a stop or a failure left unmoved: moved before anything reads them
        moveMergedAnswers(scope, scope.facet)
        schedule("start")
        // The bindings made before what their entries are a kind of was read: read once, in the background
        scheduler.execute { above.readAboveOfBound(scope) }
    }

    @PreDestroy
    fun shutdown() {
        scheduler.shutdownNow()
    }

    private companion object {
        /** The one renaming waited for, of every type's tags. */
        const val RENAMING = "tags"

        /** The names of the tags worked out and their works counted, as the queue of the works tells it. */
        val NAMING = Work("tagsNamed")

        /**
         * The quiet after the last change before the names are worked out again: a page is sent several times as it
         * grows.
         */
        const val RENAME_DELAY_SECONDS = 10L
    }
}

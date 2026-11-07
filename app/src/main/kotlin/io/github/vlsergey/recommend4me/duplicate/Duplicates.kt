package io.github.vlsergey.recommend4me.duplicate

import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.matrix.CardSource
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.work.WorkEvents
import io.github.vlsergey.recommend4me.work.Works
import io.github.vlsergey.recommend4me.workload.Work
import io.github.vlsergey.recommend4me.workload.Workload
import jakarta.annotation.PreDestroy
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

private val log = LoggerFactory.getLogger(Duplicates::class.java)

/**
 * The user's word on a pair: one work; different works; or related works ([related]) — one the TRANSLATION of the
 * other, one holding a PARTIAL of the other's text (a site's fewer chapters, a collection), two works of one SERIES
 * (the volumes of a cycle) — not linked, each its own grade and interest. A translation and a part are left out of the
 * model of one work ([PairModels.fit]): a translation's authors differ while its tags and description are the other's —
 * neither one work nor two; two works of one series are two works, and what the model of a series learns from.
 */
enum class DuplicateAnswer {
    SAME,
    DIFFERENT,
    TRANSLATION,
    PARTIAL,
    SERIES;

    /**
     * Related works, kept by their kind ([io.github.vlsergey.recommend4me.correction.TypeLinkRepository.relate]): not
     * linked.
     */
    val related: Boolean
        get() = this == TRANSLATION || this == PARTIAL || this == SERIES
}

/**
 * THE WORKS OF DIFFERENT SITES THAT MAY BE ONE WORK, and the user's word on each pair. A search ([search]), in the
 * queue of the computations, compares every work of every site of the content type with every work of every other site
 * by every field of a work every site has alike, the text itself aside: the vectors of their titles and of their
 * descriptions (the encoder of texts reads many languages: an original on Archive of Our Own and its translation on
 * fanfics.me are near), the values of every facet the sites share, each apart ([SearchSide]), and the numbers of their
 * size — and keeps of every work the one of every other site likeliest one work with it, of the pairs the user did not
 * answer on (the next likeliest in their place: of a work linked, a translation or a part of it on the same site may be
 * there); no threshold, none hidden. It runs on start when the type was never searched, and when the user asks. The
 * pairs are answered on as the work's window links the works: one work — their normalized works merged, its grade and
 * interest one — different works, or related ones (a translation, a part: each its own grade), never offered again
 * ([io.github.vlsergey.recommend4me.correction.TypeLinkRepository.distinct],
 * [io.github.vlsergey.recommend4me.correction.TypeLinkRepository.relate]).
 *
 * THE ANSWERS TEACH THE COMPARING: how likely a pair is one work, and how likely it is one series of works, are models
 * of the user's answers over how alike its layers are — a plugin's ([PairModel], [PairModels]) — fitted at every search
 * and a few seconds after the answers ([answered]); the search keeps of every work the pair every model finds
 * likeliest, every pair scored by both at once, on the card when there is one. The pairs are shown by the score by the
 * formula of the list (the prediction times the chance of interest) of the work the pair was found for times that
 * chance: a likely pair of a work the user would read first ([DuplicatePages]); the words on pairs are kept in a
 * journal ([DuplicateJournal]).
 */
@Component
class Duplicates(
    private val stores: Stores,
    private val plugins: Plugins,
    private val workload: Workload,
    private val works: Works,
    private val workEvents: WorkEvents,
    private val models: PairModels,
    /** The card, when the launch asks for it: the search made there. */
    private val cards: ObjectProvider<CardSource>,
) {
    private val worker = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "duplicates").apply { isDaemon = true }
    }

    /** The types whose search is planned or running. */
    private val searching: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** The types whose model of the answers is to be fitted again, put off by every answer till they are quiet. */
    private val fitting = ConcurrentHashMap<String, ScheduledFuture<*>>()

    /**
     * Whether a search of the type is planned or running: the pairs are to come. A fitting after an answer is not told:
     * nothing is read for an answer.
     */
    fun isSearching(type: TypeStore) = type.id in searching

    /** Every type of several sites never searched yet: searched once, in the background. */
    @Order(START_ORDER)
    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        stores.types.filter { it.sources.size > 1 && it.duplicates.search() == null }.forEach { plan(it, "start") }
    }

    /**
     * The search of the type planned, in the background — ahead of the background's works when the user pressed for it
     * ([asked]); false when one is planned or running already.
     */
    fun plan(type: TypeStore, reason: String, asked: Boolean = false): Boolean {
        if (!searching.add(type.id)) return false
        val work = Work("duplicates", type = type.id)
        workload.plan(work, Instant.now(), reason)
        worker.execute {
            // The boundary of the worker's thread: whatever the search throws is logged, the type let go of
            @Suppress("TooGenericExceptionCaught")
            try {
                workload.run(work, asked = asked) { search(type) }
            } catch (e: Exception) {
                log.warn("{}: the duplicates were not looked for: {}", type.id, e.message, e)
            } finally {
                searching -= type.id
            }
        }
        return true
    }

    /**
     * The search of the type ([TypeSearch]) by the models as the last fitting left them — of one work, and of one
     * series when there is one —, its pairs kept in place of the last search's, and the models of the answers fitted on
     * them. None without a plugin of pairs.
     */
    private fun search(type: TypeStore) {
        val encoder = plugins.textEncoder()?.id ?: return
        val model = models.model() ?: return
        val started = System.currentTimeMillis()
        val said = said(type)
        val search = TypeSearch.read(type, encoder, said)
        val read = System.currentTimeMillis() - started
        val kept = models.of(type)
        val found =
            search.found(
                said,
                model,
                listOfNotNull(kept?.same ?: model.prior(), kept?.series),
                cards.getIfAvailable()?.cublas(),
            )
        val compared = System.currentTimeMillis() - started
        val fitted = PairModels.fit(model, found.values, said)
        type.duplicates.replace(
            found.values,
            DuplicateSearch(Instant.now(), encoder, search.works, model.id, fitted.same?.pack(), fitted.series?.pack()),
        )
        log.info(
            "{}: duplicates looked for among {} works of {} sites, by {} facets apart — {} pairs in {} ms " +
                "(reading {}, comparing {}, keeping {}); a model of one work {}, of one series {}",
            type.id,
            search.works,
            search.sides.size,
            search.search.families.size,
            found.size,
            System.currentTimeMillis() - started,
            read,
            compared - read,
            System.currentTimeMillis() - started - compared,
            fitted.same != null,
            fitted.series != null,
        )
    }

    /**
     * The user's word on every pair across the sites, the smaller key first: one work (linked, by any way), different,
     * related.
     */
    fun said(type: TypeStore): Map<Pair<ItemKey, ItemKey>, DuplicateAnswer> {
        val out = HashMap<Pair<ItemKey, ItemKey>, DuplicateAnswer>()
        type.links.distincts().forEach { out[it] = DuplicateAnswer.DIFFERENT }
        type.links.relations().forEach { (pair, kind) ->
            runCatching { DuplicateAnswer.valueOf(kind) }.getOrNull()?.takeIf { it.related }?.let { out[pair] = it }
        }
        works.of(type).linkedWorks().forEach { members ->
            members.forEachIndexed { k, a ->
                members
                    .drop(k + 1)
                    .filter { it.source != a.source }
                    .forEach { b -> out[type.links.ordered(a, b)] = DuplicateAnswer.SAME }
            }
        }
        return out
    }

    /**
     * The model of the answers fitted again a few seconds after the last answer, of the pairs kept as they are: in the
     * queue of the computations, ahead of the background's works — the user's answer asked for it, and the pairs are
     * shown in its order as soon as it is kept.
     */
    private fun answered(type: TypeStore) {
        val work = Work("duplicatesModel", type = type.id)
        workload.plan(work, Instant.now().plusSeconds(QUIET_SECONDS), "answer")
        fitting.compute(type.id) { _, before ->
            before?.cancel(false)
            worker.schedule(
                {
                    // The boundary of the worker's thread: whatever the fitting throws is logged, the type let go of
                    @Suppress("TooGenericExceptionCaught")
                    try {
                        workload.run(work, asked = true) { fitAnew(type) }
                    } catch (e: Exception) {
                        log.warn("{}: the model of the duplicates was not fitted: {}", type.id, e.message, e)
                    } finally {
                        fitting.remove(type.id)
                    }
                },
                QUIET_SECONDS,
                TimeUnit.SECONDS,
            )
        }
    }

    /**
     * The models of the answers fitted anew on the pairs kept as they are, and kept: none without a plugin of pairs.
     */
    private fun fitAnew(type: TypeStore) {
        val model = models.model() ?: return
        val started = System.currentTimeMillis()
        val said = said(type)
        val pairs = type.duplicates.all()
        val fitted = PairModels.fit(model, pairs, said)
        type.duplicates.keepModels(model.id, fitted.same?.pack(), fitted.series?.pack())
        log.info(
            "{}: the models of the pairs fitted on {} pairs, {} said one work, {} one series, {} related otherwise, " +
                "{} different, in {} ms",
            type.id,
            pairs.size,
            said.values.count { it == DuplicateAnswer.SAME },
            said.values.count { it == DuplicateAnswer.SERIES },
            said.values.count { it.related && it != DuplicateAnswer.SERIES },
            said.values.count { it == DuplicateAnswer.DIFFERENT },
            System.currentTimeMillis() - started,
        )
    }

    /**
     * The user's word on the pair, in place of any before: one work — their normalized works merged, the grade and
     * interest shared — different works, or related ones (a translation, a part, one series: each its own grade and
     * interest), [b] taken out of a work holding both. A word is a word on the two normalized works: of every item of
     * them alike. False when either is no work of the type, or they are one item.
     */
    fun answer(typeId: String, a: ItemKey, b: ItemKey, verdict: DuplicateAnswer): Boolean {
        val type = stores.type(typeId)?.takeIf { isPair(it, a, b) } ?: return false
        val now = Instant.now()
        when (verdict) {
            DuplicateAnswer.SAME -> {
                type.links.link(a, b, now)
                workEvents.merged(a)
            }
            DuplicateAnswer.DIFFERENT -> type.links.distinct(a, b, now)
            else -> type.links.relate(a, b, verdict.name, now)
        }
        answered(type)
        return true
    }

    /** Whether [a] and [b] are both works of the [type], and two items. */
    private fun isPair(type: TypeStore, a: ItemKey, b: ItemKey) =
        type.source(a.source)?.items?.find(a.id) != null && type.source(b.source)?.items?.find(b.id) != null && a != b

    /** The word on the pair taken back: that they are different or related — or, of none, that they are one work. */
    fun unanswer(typeId: String, a: ItemKey, b: ItemKey) {
        val type = stores.type(typeId) ?: return
        val now = Instant.now()
        val pair = type.links.ordered(a, b)
        if (pair in type.links.relations() || pair in type.links.distincts()) type.links.takeBack(a, b, now)
        else type.links.unlink(a, b, now)
        answered(type)
    }

    @PreDestroy
    fun shutdown() {
        worker.shutdownNow()
    }

    companion object {
        /**
         * The place of [start] among the listeners of the application being ready: after those of a lower order, whose
         * data the search reads.
         */
        private const val START_ORDER = 20

        /** The quiet after the last answer before the model of the answers is fitted again. */
        private const val QUIET_SECONDS = 3L
    }
}

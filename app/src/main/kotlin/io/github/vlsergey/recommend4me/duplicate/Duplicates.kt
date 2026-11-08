package io.github.vlsergey.recommend4me.duplicate

import io.github.vlsergey.recommend4me.correction.TypeLinkRepository
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.matrix.CardSource
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.work.WorkEvents
import io.github.vlsergey.recommend4me.workload.Work
import io.github.vlsergey.recommend4me.workload.Workload
import jakarta.annotation.PreDestroy
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

private val log = LoggerFactory.getLogger(Duplicates::class.java)

/**
 * THE WORKS THAT MAY BE ONE WORK, OR ONE SERIES, and the user's word on each pair. A search ([search]), in the queue of
 * the computations, compares every work of every site of the content type with every work of every site, its own too —
 * never with itself — by every field of a work every site has alike, the text itself aside: the vectors of their titles
 * and of their descriptions (the encoder of texts reads many languages: an original on Archive of Our Own and its
 * translation on fanfics.me are near), the values of every facet the sites share, each apart ([SearchSide]), and the
 * numbers of their size — and keeps of every work the one likeliest one work with it and the one likeliest one series
 * with it, of the pairs the user did not answer on (the next likeliest in their place): a pair or two a work, no
 * threshold, none hidden. It runs on start when the type was never searched, and when the user asks. The pairs are
 * answered on as the work's window links the works: one work — their normalized works merged, its grade and interest
 * one — different works, or related ones (a translation, a part: each its own grade), never offered again
 * ([io.github.vlsergey.recommend4me.correction.TypeLinkRepository.distinct],
 * [io.github.vlsergey.recommend4me.correction.TypeLinkRepository.relate]).
 *
 * THE ANSWERS TEACH THE COMPARING: how likely a pair is one work, and how likely it is one series of works, are models
 * of the user's answers over how alike its layers are — a plugin's ([PairModel], [PairModels]) — fitted at every search
 * and a few seconds after the answers ([PairFitting]); the search keeps of every work the pair every model finds
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
    private val workEvents: WorkEvents,
    private val models: PairModels,
    private val answers: PairAnswers,
    private val fitting: PairFitting,
    /** The card, when the launch asks for it: the search made there. */
    private val cards: ObjectProvider<CardSource>,
) {
    private val worker = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "duplicates").apply { isDaemon = true }
    }

    /** The types whose search is planned or running. */
    private val searching: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Whether a search of the type is planned or running: the pairs are to come. A fitting after an answer is not told:
     * nothing is read for an answer.
     */
    fun isSearching(type: TypeStore) = type.id in searching

    /**
     * The plugin of pairs [id] made the type's, and the pairs looked for anew by it, its models fitted with them: the
     * models another plugin packed it reads none of. False of no such plugin.
     */
    fun chooseModel(type: TypeStore, id: String): Boolean {
        if (!models.choose(type.id, id)) return false
        plan(type, "model chosen", asked = true)
        return true
    }

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
        val model = models.model(type.id) ?: return
        val started = System.currentTimeMillis()
        val said = answers.said(type)
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
        val now = Instant.now()
        type.duplicates.replace(
            found.values,
            DuplicateSearch(
                now,
                encoder,
                search.works,
                model.id,
                fitted.same?.pack(),
                fitted.series?.pack(),
                now,
                System.currentTimeMillis() - started - compared,
            ),
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
        fitting.afterAnswer(type)
        return true
    }

    /** Whether [a] and [b] are both works of the [type], and two items. */
    private fun isPair(type: TypeStore, a: ItemKey, b: ItemKey) =
        type.source(a.source)?.items?.find(a.id) != null && type.source(b.source)?.items?.find(b.id) != null && a != b

    /**
     * The word on the pair taken back: that they are one work, or the word on their two works — of two works of one
     * series, by their word or by the chain, both taken out of it.
     */
    fun unanswer(typeId: String, a: ItemKey, b: ItemKey) {
        val type = stores.type(typeId) ?: return
        val now = Instant.now()
        if (type.links.word(a, b, now) == TypeLinkRepository.SAME) type.links.unlink(a, b, now)
        else type.links.takeBack(a, b, now)
        fitting.afterAnswer(type)
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
    }
}

package io.github.vlsergey.recommend4me.duplicate

import io.github.vlsergey.recommend4me.api.model.TaskKind
import io.github.vlsergey.recommend4me.api.model.TaskPriority
import io.github.vlsergey.recommend4me.correction.TypeLinkRepository
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.matrix.CardSource
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.series.SeriesDifferences
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.task.Task
import io.github.vlsergey.recommend4me.task.Tasks
import io.github.vlsergey.recommend4me.work.WorkEvents
import io.github.vlsergey.recommend4me.worklist.ListIndexes
import java.time.Instant
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
    private val tasks: Tasks,
    private val workEvents: WorkEvents,
    private val models: PairModels,
    private val answers: PairAnswers,
    private val fitting: PairFitting,
    /** The card, when the launch asks for it: the search made there. */
    private val cards: ObjectProvider<CardSource>,
    /** The index of the list: what a work's values compared are read of. */
    private val indexes: ListIndexes,
) {
    /**
     * Whether a search of the type is planned or running: the pairs are to come. A fitting after an answer is not told:
     * nothing is read for an answer.
     */
    fun isSearching(type: TypeStore) = tasks.due(searching(type))

    /**
     * The plugin of pairs [id] made the type's, and the pairs looked for anew by it, its models fitted with them: the
     * models another plugin packed it reads none of. False of no such plugin.
     */
    fun chooseModel(type: TypeStore, id: String): Boolean {
        if (!models.choose(type.id, id)) return false
        plan(type, "model chosen", TaskPriority.ASKED)
        return true
    }

    // What the last run of the application left undone of it: planned again by the queue at its start
    init {
        tasks.resumption.resumes(TaskKind.SEARCH_DUPLICATES) { left, reason ->
            left.work.type?.let(stores::type)?.let { plan(it, reason, left.priority) }
        }
    }

    /** Every type of several sites never searched yet: searched once, in the background. */
    @Order(START_ORDER)
    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        stores.types.filter { it.sources.size > 1 && it.duplicates.search() == null }.forEach { plan(it, "start") }
    }

    /**
     * The search of the type planned, in the background — ahead of the background's works when the user pressed for it
     * ([asked]); false when one is planned or waiting its turn already — one running is done again after it.
     */
    fun plan(type: TypeStore, reason: String, priority: TaskPriority? = null): Boolean {
        val work = searching(type)
        // A search running read the works before: asked again, it is done again after it
        if (tasks.due(work, running = false)) return false
        tasks.plan(work, Instant.now(), reason, priority = priority) { search(type) }
        return true
    }

    private fun searching(type: TypeStore) = Task(TaskKind.SEARCH_DUPLICATES, type = type.id)

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
        val search = TypeSearch.read(type, encoder, said, indexes.of(type.id))
        val read = System.currentTimeMillis() - started
        tasks.progress(READ)
        val kept = models.of(type)
        val found =
            search.found(
                said,
                model,
                listOfNotNull(kept?.same ?: model.prior(), kept?.series),
                cards.getIfAvailable()?.cublas(),
            ) { share ->
                tasks.progress(READ + share * (COMPARED - READ))
            }
        val compared = System.currentTimeMillis() - started
        tasks.progress(COMPARED)
        ChanceSpread.of(found.values, kept)?.let { log.info("{}: {}", type.id, it) }
        val likely = likely(found.values, said, kept)
        val fitted = PairModels.fit(model, likely, said)
        model.fitted(search.search, listOfNotNull(fitted.same ?: model.prior(), fitted.series))
        val fit = System.currentTimeMillis() - started
        val now = Instant.now()
        type.duplicates.replace(
            likely,
            DuplicateSearch(
                now,
                encoder,
                search.works,
                model.id,
                fitted.same?.pack(),
                fitted.series?.pack(),
                now,
                fit - compared,
            ),
            search.rarity,
        )
        log.info(
            "{}: duplicates looked for among {} works of {} sites, by {} facets apart — {} pairs, {} of them kept, " +
                "in {} ms " +
                "(reading {}, comparing {}, fitting {}, writing {}); a model of one work {}, of one series {}",
            type.id,
            search.works,
            search.sides.size,
            search.search.families.size,
            found.size,
            likely.size,
            System.currentTimeMillis() - started,
            read,
            compared - read,
            fit - compared,
            System.currentTimeMillis() - started - fit,
            fitted.same != null,
            fitted.series != null,
        )
    }

    /**
     * Of the likeliest pair of every work [found], those a model of the answers gives a chance of 1 % at least — its
     * scale calibrated on the answers — and every pair answered on ([said]): of a million works most are plainly two
     * works (user, 2026-11-17: "even 1 % should cut off much"). Of models of no answer yet ([kept]), every pair.
     */
    private fun likely(
        found: Collection<DuplicateCandidate>,
        said: Map<Pair<ItemKey, ItemKey>, DuplicateAnswer>,
        kept: PairChances?,
    ): List<DuplicateCandidate> =
        if (kept?.learnt != true) found.toList()
        else
            found.filter { c ->
                c.pair in said || kept.same(c) >= LEAST_CHANCE || (kept.series(c) ?: 0.0) >= LEAST_CHANCE
            }

    /**
     * The user's word on the pair, in place of any before: one work — their normalized works merged, the grade and
     * interest shared — different works, or related ones (a translation, a part, one series: each its own grade and
     * interest), [b] taken out of a work holding both. A word is a word on the two normalized works: of every item of
     * them alike. False when either is no work of the type, or they are one item — or of the word "different" on two
     * works the sites hold in one series: no different works, the word refused, as it would be taken back
     * ([SeriesDifferences]).
     */
    fun answer(typeId: String, a: ItemKey, b: ItemKey, verdict: DuplicateAnswer): Boolean {
        val type =
            stores.type(typeId)?.takeIf {
                isPair(it, a, b) &&
                    (verdict != DuplicateAnswer.DIFFERENT ||
                        SeriesDifferences.heldBySites(it, listOf(a to b)).isEmpty())
            } ?: return false
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

    companion object {
        /**
         * The place of [start] among the listeners of the application being ready: after those of a lower order, whose
         * data the search reads.
         */
        private const val START_ORDER = 20

        /**
         * The shares of a search done once its works are read, and once its pairs are compared: the reading and the
         * comparing as the searches of 2026-11-18 took them, the comparing told as it goes, pair by pair.
         */
        private const val READ = 0.2
        private const val COMPARED = 0.9

        /** The least chance of a pair kept by a search: of the models calibrated on the user's answers. */
        const val LEAST_CHANCE = 0.01
    }
}

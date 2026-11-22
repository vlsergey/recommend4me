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
 * of the user's answers over how alike its layers are — a plugin's ([PairModel], [PairModels]) — fitted a few seconds
 * after the answers ([PairFitting]), never by a search; the search keeps of every work the pair every model finds
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
     * The plugin of pairs [id] made the type's, its models fitted on the answers, and the pairs looked for anew by them
     * after: the models another plugin packed it reads none of. False of no such plugin.
     */
    fun chooseModel(type: TypeStore, id: String): Boolean {
        if (!models.choose(type.id, id)) return false
        fitting.now(type)
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
     * The search of the type ([TypeSearch]) by the models as the last fitting on the user's answers left them — of one
     * work, and of one series when there is one —, its pairs kept in place of the last search's with their chances by
     * those models, the models kept as they were: a search fits nothing (owner, 2026-11-22: "the search of any
     * duplicates must go by the model built"), the fitting is of the answers ([PairFitting]). None without a plugin of
     * pairs.
     */
    private fun search(type: TypeStore) {
        val encoder = plugins.textEncoder()?.id ?: return
        val model = models.model(type.id) ?: return
        val started = System.currentTimeMillis()
        val said = answers.said(type)
        val search = TypeSearch.read(type, encoder, said, indexes.of(type.id)) { tasks.progress(it * READ) }
        val read = System.currentTimeMillis() - started
        tasks.progress(READ)
        // The models the search is led by, as the last fitting kept them with the last search
        val last = type.duplicates.search()?.takeIf { it.model == model.id }
        val kept = models.of(type) ?: PairChances(model.prior(), null, false)
        val found = found(search, said, model, kept)
        val compared = System.currentTimeMillis() - started
        tasks.progress(COMPARED)
        ChanceSpread.of(found.values, search.sides.map { it.ids.size }, kept.learnt)?.let {
            log.info("{}: {}", type.id, it)
        }
        val likely = likely(found.values, kept.learnt)
        val scores = PairScore.of(likely, WholeMatch(search.sides), PairWorth(search.sides))
        // The pairs scored before they are written: the writing told apart from what it writes
        val scored = search.inKeyOrder(likely).map { it.candidate to scores(it) }
        val score = System.currentTimeMillis() - started
        type.duplicates.replace(
            scored,
            DuplicateSearch(
                Instant.now(),
                encoder,
                search.works,
                model.id,
                last?.same,
                last?.series,
                last?.fittedAt,
                last?.fitMillis,
            ),
            search.rarity,
        )
        log.info(
            "{}: duplicates looked for among {} works of {} sites, by {} facets apart — {} pairs, {} of them kept, " +
                "in {} ms (reading {}, comparing {}, scoring {}, writing {}); by a model of one work of the " +
                "answers {}, of one series {}",
            type.id,
            search.works,
            search.sides.size,
            search.search.families.size,
            found.size,
            likely.size,
            System.currentTimeMillis() - started,
            read,
            compared - read,
            score - compared,
            System.currentTimeMillis() - started - score,
            kept.learnt,
            kept.series != null,
        )
    }

    /**
     * The pairs the [search] finds by the [model]'s models as the last fitting left them ([kept]) — of none yet, its
     * prior —, on the card when there is one, and every pair answered on ([said]), with their chances by those models;
     * its share told as it goes.
     */
    private fun found(search: TypeSearch, said: Said, model: PairModel, kept: PairChances) =
        search.found(said, model, kept, cards.getIfAvailable()?.cublas()) { share ->
            tasks.progress(READ + share * (COMPARED - READ))
        }

    /**
     * Of the likeliest pair of every work [found], those the models of the answers give a chance of 1 % at least —
     * their scale calibrated on the answers — and every pair answered on ([FoundCandidate.word]): of a million works
     * most are plainly two works (user, 2026-11-17: "even 1 % should cut off much"). Of models of no answer yet
     * ([learnt]), every pair.
     */
    private fun likely(found: Collection<FoundCandidate>, learnt: Boolean): List<FoundCandidate> =
        if (!learnt) found.toList()
        else found.filter { f -> f.word != null || f.same >= LEAST_CHANCE || (f.series ?: 0.0) >= LEAST_CHANCE }

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
        // Struck out of the open pairs at once, till the next search tells every word anew
        type.duplicates.said(type.links.ordered(a, b), verdict)
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
        type.duplicates.said(type.links.ordered(a, b), null)
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

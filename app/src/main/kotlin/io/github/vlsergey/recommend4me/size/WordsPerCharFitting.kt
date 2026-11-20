package io.github.vlsergey.recommend4me.size

import io.github.vlsergey.recommend4me.api.model.TaskKind
import io.github.vlsergey.recommend4me.contenttype.StandardLanguages
import io.github.vlsergey.recommend4me.correction.WorkFieldChangeEvent
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.item.SiteItemWriteEvent
import io.github.vlsergey.recommend4me.laps.Laps
import io.github.vlsergey.recommend4me.matrix.CardSource
import io.github.vlsergey.recommend4me.matrix.Cublas
import io.github.vlsergey.recommend4me.matrix.CudaKernels
import io.github.vlsergey.recommend4me.matrix.NoCardRoom
import io.github.vlsergey.recommend4me.part.SiteItemChapterChangeEvent
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.tag.TagChangeEvent
import io.github.vlsergey.recommend4me.task.Task
import io.github.vlsergey.recommend4me.task.Tasks
import io.github.vlsergey.recommend4me.worklist.ListIndexes
import java.time.Instant
import kotlin.math.abs
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service

private val log = LoggerFactory.getLogger(WordsPerCharFitting::class.java)

/**
 * THE MODEL OF HOW MANY WORDS A SITE ITEM HAS TO A CHARACTER ([WordsPerChar]) FITTED AND KEPT, its weights alone
 * ([KeptRatios]): what [WorkSizes] works the size a site does not state out of. Learnt in the queue of the
 * computations, after a quiet, from the items whose words and characters are both known ([SizeExamples]): their kept
 * texts, a site that states both, and the items of one work where one site states the words and another the characters,
 * when the two are not far apart — within [TRUST] of the errors of the model of the rest.
 *
 * Fitted again only when the items a change names give it something to learn of ([givesExamples]), or the tags are
 * bound anew: a change of any other item asks nothing of it (owner, 2026-11-20: "no new information appeared — there
 * must be no model fit").
 */
@Service
class WordsPerCharFitting(
    private val stores: Stores,
    private val tasks: Tasks,
    private val indexes: ListIndexes,
    private val cards: ObjectProvider<CardSource>,
) {

    /** The kernels of the ridge and the card they were compiled for: compiled once a card. */
    @Volatile private var compiled: Pair<Cublas, CudaKernels>? = null

    /**
     * The model of the type fitted of every example and kept: the ridge solved on the card when there is one with room
     * for the examples, else on the processor (owner, 2026-11-20: "on the GPU at once").
     */
    fun fit(type: TypeStore) {
        val laps = Laps()
        val features = SizeFeatures()
        val examples = SizeExamples.read(type, indexes.of(type.id), features)
        laps.lap("read")
        val sure = examples.own
        if (sure.isEmpty()) return
        val names = features.names
        log.info(
            "{}: the words to a character to learn of {} items of their own numbers and {} of two sites, {} features",
            type.id,
            sure.size,
            examples.across.size,
            names.size,
        )
        val penalties = WordsPerChar.penalties(names)
        val (crossed, where) =
            solved(SizeRows(sure, names.size), penalties) { ridge ->
                val first = ridge.fit()
                Crossed(first, ridge.outOfFold(first), ridge.meanOutOfFold())
            }
        laps.lap("fitted and cross-validated on $where")
        val first = WordsPerChar(crossed.weights)
        val error = crossed.error
        val near =
            examples.across.filter { e ->
                error.isNaN() || abs(e.logRatio - first.log(e.features)) <= TRUST * error
            }
        val model =
            if (near.isEmpty()) first
            else WordsPerChar(solved(SizeRows(sure + near, names.size), penalties) { it.fit(crossed.weights) }.first)
        laps.lap("fitted with two sites' on $where")
        val weights = model.named(names)
        type.sizes.keep(weights)
        laps.lap("kept")
        log.info(
            "{}: the words to a character learnt from {} items of their own numbers and {} of two sites of {}, " +
                "{} features, out of fold {} by a logarithm, the mean alone {}: {}",
            type.id,
            sure.size,
            near.size,
            examples.across.size,
            weights.size,
            "%.4f".format(error),
            "%.4f".format(crossed.meanError),
            laps,
        )
    }

    /** The weights fitted of every example, and the errors out of fold of the model and of the mean alone. */
    private class Crossed(val weights: DoubleArray, val error: Double, val meanError: Double)

    /**
     * [body] of the ridge of [rows] on the card when there is one with room for them — its memory had in a block of
     * work —, else on the processor; and where.
     */
    private fun <T> solved(rows: SizeRows, penalties: DoubleArray, body: (SizeRidge) -> T): Pair<T, String> {
        card()?.let { (cublas, kernels) ->
            val bytes = SizeGram.OnCard.bytes(rows)
            try {
                return cublas.withMemory(bytes, bytes) { memory ->
                    body(SizeRidge(rows, penalties, SizeGram.OnCard(rows, kernels, memory)))
                } to "the card"
            } catch (e: NoCardRoom) {
                log.info("the words to a character fitted on the processor: {}", e.message)
            }
        }
        return body(SizeRidge(rows, penalties, SizeGram.OnProcessor(rows))) to "the processor"
    }

    /** The card with the kernels of the ridge; none without a card, or of kernels that would not compile. */
    private fun card(): Pair<Cublas, CudaKernels>? {
        val cublas = cards.getIfAvailable()?.cublas() ?: return null
        return compiled?.takeIf { it.first === cublas }
            ?: SizeGram.OnCard.kernels(cublas)?.let { cublas to it }?.also { compiled = it }
    }

    /**
     * The model fitted when what waits asks it ([EVERY]), or one of the items it names gives it something to learn of;
     * else nothing.
     */
    private fun fitWhenNew(type: TypeStore) {
        val work = Task(FITTING, type = type.id)
        val taken = tasks.keys.wanted(work)
        if (taken.isEmpty()) return
        val asked = EVERY in taken || givesExamples(type, taken.filter { it != EVERY }.map(ItemKey::parse))
        if (asked) fit(type)
        tasks.keys.done(work, taken)
        if (!asked)
            log.info("{}: none of the {} items named gives the words to a character anything new", type.id, taken.size)
    }

    // --- In the background ---

    // What the last run of the application left undone of it: planned again by the queue at its start — what it was
    // to be done of the queue kept; of no model kept yet, the model fitted
    init {
        tasks.resumption.resumes(FITTING) { left, reason ->
            left.work.type?.let(stores::type)?.let {
                plan(it, if (it.sizes.model().fitted) emptyList() else listOf(EVERY), reason)
            }
        }
    }

    @EventListener fun itemsChanged(event: SiteItemWriteEvent) = named(event.source, event.ids, "items")

    @EventListener fun partsSaved(event: SiteItemChapterChangeEvent) = named(event.source, event.ids, "parts")

    @EventListener
    fun answered(event: WorkFieldChangeEvent) {
        val s = stores.source(event.source) ?: return
        if (event.facet == StandardLanguages.KEY || event.facet in s.tagFacets)
            named(event.source, listOf(event.itemId), "answer")
    }

    /** The tags bound anew: the features of any item may have changed. */
    @EventListener
    fun tagsChanged(event: TagChangeEvent) {
        stores.type(event.type)?.let { plan(it, listOf(EVERY), "tags") }
    }

    /** The items [ids] of the [source] changed, for the [reason]. */
    private fun named(source: String, ids: List<String>, reason: String) =
        plan(stores.typeOf(source), ids.map { ItemKey(source, it).toString() }, reason)

    private fun plan(type: TypeStore, keys: List<String>, reason: String) {
        if (!type.hasSizes()) return
        val work = Task(FITTING, type = type.id)
        if (keys.isNotEmpty()) tasks.keys.want(work, keys)
        // Due at once, no quiet: the items of the sites are written all the time, and a quiet put off by every write
        // never came (2026-11-20, two hours planned); planned again while it waits its turn, it is that one
        tasks.plan(work, Instant.now(), reason) { fitWhenNew(type) }
    }

    companion object {
        val FITTING = TaskKind.MEASURE_WORK_SIZES

        /** What waits asking the model fitted whatever the items: no item's key. */
        private const val EVERY = "*"

        /**
         * How many errors of the model the ratio two sites give may stand off it: nearer, they speak of one text;
         * further, one of them holds a part or another version.
         */
        private const val TRUST = 3.0
    }
}

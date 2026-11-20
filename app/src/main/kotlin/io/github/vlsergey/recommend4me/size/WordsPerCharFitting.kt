package io.github.vlsergey.recommend4me.size

import io.github.vlsergey.recommend4me.api.model.TaskKind
import io.github.vlsergey.recommend4me.contenttype.StandardLanguages
import io.github.vlsergey.recommend4me.correction.WorkFieldChangeEvent
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.item.SiteItemWriteEvent
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
class WordsPerCharFitting(private val stores: Stores, private val tasks: Tasks, private val indexes: ListIndexes) {

    /** The model of the type fitted of every example and kept. */
    fun fit(type: TypeStore) {
        val started = System.currentTimeMillis()
        val features = SizeFeatures()
        val examples = SizeExamples.read(type, indexes.of(type.id), features)
        val read = System.currentTimeMillis()
        val sure = examples.own
        if (sure.isEmpty()) return
        log.info(
            "{}: the words to a character to learn of {} items of their own numbers and {} of two sites, {} features, " +
                "read in {} ms",
            type.id,
            sure.size,
            examples.across.size,
            features.names.size,
            read - started,
        )
        val error = WordsPerChar.outOfFold(sure, features.names)
        val first = WordsPerChar.fit(sure, features.names)
        val near =
            examples.across.filter { e ->
                error.isNaN() || abs(e.logRatio - first.log(e.features)) <= TRUST * error
            }
        val model = WordsPerChar.fit(sure + near, features.names)
        val weights = model.named(features.names)
        type.sizes.keep(weights)
        log.info(
            "{}: the words to a character learnt from {} items of their own numbers and {} of two sites of {}, " +
                "{} features, out of fold {} by a logarithm, the mean alone {}, in {} ms: the examples read {}",
            type.id,
            sure.size,
            near.size,
            examples.across.size,
            weights.size,
            "%.4f".format(error),
            "%.4f"
                .format(
                    WordsPerChar.outOfFold(sure.map { SizeExample(IntArray(0), it.words, it.chars) }, features.names)
                ),
            System.currentTimeMillis() - started,
            read - started,
        )
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

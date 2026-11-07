package io.github.vlsergey.recommend4me.size

import io.github.vlsergey.recommend4me.api.model.SizeNumber
import io.github.vlsergey.recommend4me.contenttype.StandardLanguages
import io.github.vlsergey.recommend4me.correction.Corrected
import io.github.vlsergey.recommend4me.correction.FacetAnswered
import io.github.vlsergey.recommend4me.item.ItemsChanged
import io.github.vlsergey.recommend4me.part.PartsSaved
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.tag.TagsChanged
import io.github.vlsergey.recommend4me.workload.Work
import io.github.vlsergey.recommend4me.workload.Workload
import jakarta.annotation.PreDestroy
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service

private val log = LoggerFactory.getLogger(WorkSizes::class.java)

/**
 * The size of an item as it is shown: its words and characters, each stated by its site or worked out; none when
 * neither is known.
 */
class ItemSize(val words: SizeNumber?, val chars: SizeNumber?)

/**
 * HOW LONG EVERY WORK IS, IN WORDS AND IN CHARACTERS, whichever of them its site states: a site tells one of them, so
 * the other comes of how many words a work has to a character ([WordsPerChar]), kept of every normalized work. The
 * ratio is learnt in the queue of the computations, after a quiet, from the works whose words and characters are both
 * known: their kept texts, counted once ([knownSizes]), a site that states both, and the items of one work where one
 * site states the words and another the characters, when the two are not far apart — within [TRUST] of the errors of
 * the model of the rest. Of such a work the ratio is mostly its own, of any other the model's, by what the work is: its
 * site, its language, its normalized tags and authors, of all its items.
 */
@Service
class WorkSizes(private val stores: Stores, private val workload: Workload) {
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "work-sizes").apply { isDaemon = true }
    }
    private val pending = ConcurrentHashMap<String, ScheduledFuture<*>>()

    /**
     * The sizes of the items [ids] of the source, of their [numbers] with the user's word over them: what the site
     * states as it is, the other worked out of it by the ratio of the item's work. Read for the operation.
     */
    fun ofItems(
        type: TypeStore,
        s: SourceStore,
        ids: List<String>,
        numbers: Map<String, Map<String, Double>>,
        fields: Map<String, Map<String, String>>,
    ): Map<String, ItemSize> {
        if (s.wordsKey() == null && s.charsKey() == null) return emptyMap()
        val works = s.works.ofItems(s.id, ids)
        return sized(s, ids, numbers, fields, works, type.sizes.of(works.values))
    }

    /**
     * The size of every item of the source that has one, for an operation over every work (the pairs of the duplicates
     * sorted by their size): each table read whole, once — the two numbers of the size alone, every field the user
     * wrote, every item's work, every work's ratio. Asked item by item, the 82 000 works of the books took three
     * minutes.
     */
    fun ofEvery(type: TypeStore, s: SourceStore): Map<String, ItemSize> {
        val keys = listOfNotNull(s.wordsKey(), s.charsKey())
        if (keys.isEmpty()) return emptyMap()
        val numbers = HashMap<String, MutableMap<String, Double>>()
        keys.forEach { key ->
            s.items.number(key).forEach { (id, value) -> numbers.getOrPut(id) { HashMap() }[key] = value }
        }
        val fields = s.corrections.allFields()
        // An item of no number of its size may have one the user wrote
        val ids = (numbers.keys + fields.keys).toList()
        return sized(s, ids, numbers, fields, s.works.allOf(s.id), type.sizes.all())
    }

    /**
     * The sizes of the items [ids] of what is read of them: their numbers, the fields the user wrote, their works and
     * the works' ratios.
     */
    private fun sized(
        s: SourceStore,
        ids: List<String>,
        numbers: Map<String, Map<String, Double>>,
        fields: Map<String, Map<String, String>>,
        works: Map<String, Long>,
        ratios: Map<Long, Double>,
    ): Map<String, ItemSize> {
        val words = s.wordsKey()
        val chars = s.charsKey()
        return ids.associateWith { id ->
            val own = Corrected.numbers(numbers[id].orEmpty(), fields[id].orEmpty())
            val statedWords = words?.let { own[it] }?.takeIf { it > 0 }
            val statedChars = chars?.let { own[it] }?.takeIf { it > 0 }
            val ratio = works[id]?.let { ratios[it] }
            ItemSize(
                statedWords?.let { SizeNumber(it, true) }
                    ?: ratio?.let { r -> statedChars?.let { SizeNumber(it * r, false) } },
                statedChars?.let { SizeNumber(it, true) }
                    ?: ratio?.let { r -> statedWords?.let { SizeNumber(it / r, false) } },
            )
        }
    }

    /** The model of the type fitted, the works of two sites near enough to it let in, and every work's ratio kept. */
    fun fit(type: TypeStore) {
        val started = System.currentTimeMillis()
        val works = knownSizes(type)
        val sure = works.values.mapNotNull { it.own() }
        if (sure.isEmpty()) return
        val error = WordsPerChar.outOfFold(sure)
        val first = WordsPerChar.fit(sure)
        val across = works.values.filter { it.own() == null }.mapNotNull { k -> k.acrossSites()?.let { k to it } }
        val near =
            across
                .filter { (_, e) -> error.isNaN() || abs(e.logRatio - first.log(e.features)) <= TRUST * error }
                .toMap()
        val model = WordsPerChar.fit(sure + near.values)
        type.sizes.keep(works.mapValues { (_, k) -> model.of(k.features, k.own() ?: near[k]) })
        log.info(
            "{}: the words to a character of {} works, learnt from {} of their own numbers " +
                "and {} of two sites of {}, out of fold {} by a logarithm, the mean alone {}, in {} ms",
            type.id,
            works.size,
            sure.size,
            near.size,
            across.size,
            "%.4f".format(error),
            "%.4f".format(WordsPerChar.outOfFold(sure.map { SizeExample(emptyList(), it.words, it.chars) })),
            System.currentTimeMillis() - started,
        )
    }

    // --- The fitting, in the background ---

    @EventListener(ApplicationReadyEvent::class)
    fun start() = stores.types.forEach { schedule(it, START_DELAY_SECONDS, "start") }

    @EventListener fun itemsChanged(event: ItemsChanged) = schedule(stores.typeOf(event.source), QUIET_SECONDS, "items")

    @EventListener fun partsSaved(event: PartsSaved) = schedule(stores.typeOf(event.source), QUIET_SECONDS, "parts")

    @EventListener
    fun answered(event: FacetAnswered) {
        val s = stores.source(event.source) ?: return
        if (event.facet == StandardLanguages.KEY || event.facet in s.tagFacets)
            schedule(stores.typeOf(event.source), QUIET_SECONDS, "answer")
    }

    @EventListener
    fun tagsChanged(event: TagsChanged) {
        stores.type(event.type)?.let { schedule(it, QUIET_SECONDS, "tags") }
    }

    private fun schedule(type: TypeStore, delaySeconds: Long, reason: String) {
        if (!type.hasSizes()) return
        val work = Work(FITTING, type = type.id)
        workload.plan(work, Instant.now().plusSeconds(delaySeconds), reason)
        val next =
            scheduler.schedule(
                {
                    // The boundary of the scheduler's thread: whatever a fitting throws is logged here, or the
                    // future it was put in would keep it unseen
                    @Suppress("TooGenericExceptionCaught")
                    try {
                        workload.run(work) { fit(type) }
                    } catch (e: Exception) {
                        log.warn("{}: the words to a character were not worked out: {}", type.id, e.message, e)
                    }
                },
                delaySeconds,
                TimeUnit.SECONDS,
            )
        pending.put(type.id, next)?.cancel(false)
    }

    @PreDestroy
    fun shutdown() {
        scheduler.shutdownNow()
    }

    companion object {
        const val FITTING = "workSizes"

        private const val START_DELAY_SECONDS = 60L

        private const val QUIET_SECONDS = 60L

        /**
         * How many errors of the model the ratio two sites give may stand off it: nearer, they speak of one text;
         * further, one of them holds a part or another version.
         */
        private const val TRUST = 3.0
    }
}

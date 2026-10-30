package io.github.vlsergey.recommend4me.size

import io.github.vlsergey.recommend4me.api.model.SizeNumber
import io.github.vlsergey.recommend4me.contenttype.StandardLanguages
import io.github.vlsergey.recommend4me.contenttype.StandardNumbers
import io.github.vlsergey.recommend4me.correction.Corrected
import io.github.vlsergey.recommend4me.correction.FacetAnswered
import io.github.vlsergey.recommend4me.item.ItemsChanged
import io.github.vlsergey.recommend4me.part.PartsSaved
import io.github.vlsergey.recommend4me.source.FacetRole
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.tag.TagsChanged
import io.github.vlsergey.recommend4me.tag.normalizedTags
import io.github.vlsergey.recommend4me.workload.Work
import io.github.vlsergey.recommend4me.workload.Workload
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.withLock
import kotlin.math.abs

private val log = LoggerFactory.getLogger(WorkSizes::class.java)

/** The size of an item as it is shown: its words and characters, each stated by its site or worked out; none when neither is known. */
class ItemSize(val words: SizeNumber?, val chars: SizeNumber?)

/**
 * HOW LONG EVERY WORK IS, IN WORDS AND IN CHARACTERS, whichever of them its site states: a site tells
 * one of them, so the other comes of how many words a work has to a character ([WordsPerChar]), kept of
 * every normalized work. The ratio is learnt in the queue of the computations, after a quiet, from the
 * works whose words and characters are both known: their kept texts, counted once ([count]), a site that
 * states both, and the items of one work where one site states the words and another the characters,
 * when the two are not far apart — within [TRUST] of the errors of the model of the rest. Of such a work
 * the ratio is mostly its own, of any other the model's, by what the work is: its site, its language, its
 * normalized tags and authors, of all its items.
 */
@Service
class WorkSizes(private val stores: Stores, private val workload: Workload) {
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "work-sizes").apply { isDaemon = true } }
    private val pending = ConcurrentHashMap<String, ScheduledFuture<*>>()

    private fun wordsKey(s: SourceStore) = s.schema.numbers.firstOrNull { it.standard === StandardNumbers.WORDS }?.key

    private fun charsKey(s: SourceStore) = s.schema.numbers.firstOrNull { it.standard === StandardNumbers.CHARS }?.key

    private fun sized(type: TypeStore) = type.sources.any { wordsKey(it) != null || charsKey(it) != null }

    /**
     * The sizes of the items [ids] of the source, of their [numbers] with the user's word over them: what
     * the site states as it is, the other worked out of it by the ratio of the item's work. Read for the
     * operation.
     */
    fun ofItems(type: TypeStore, s: SourceStore, ids: List<String>, numbers: Map<String, Map<String, Double>>, fields: Map<String, Map<String, String>>): Map<String, ItemSize> {
        val words = wordsKey(s)
        val chars = charsKey(s)
        if (words == null && chars == null) return emptyMap()
        val works = s.works.ofItems(s.id, ids)
        val ratios = type.sizes.of(works.values)
        return ids.associateWith { id ->
            val own = Corrected.numbers(numbers[id].orEmpty(), fields[id].orEmpty())
            val statedWords = words?.let { own[it] }?.takeIf { it > 0 }
            val statedChars = chars?.let { own[it] }?.takeIf { it > 0 }
            val ratio = works[id]?.let { ratios[it] }
            ItemSize(
                statedWords?.let { SizeNumber(it, true) } ?: ratio?.let { r -> statedChars?.let { SizeNumber(it * r, false) } },
                statedChars?.let { SizeNumber(it, true) } ?: ratio?.let { r -> statedWords?.let { SizeNumber(it / r, false) } },
            )
        }
    }

    /** What is known of a work by every item of it. */
    private class Known {
        val features = HashSet<String>()

        /** The longest text kept of any of its items, counted. */
        var text: Pair<Double, Double>? = null

        /** The words and characters one item states both of. */
        var stated: Pair<Double, Double>? = null

        /** The most words and the most characters any of its items states: a work may stand unfinished on a site. */
        var words = 0.0
        var chars = 0.0

        /** Of its text or of what one site states, none of two sites. */
        fun own(): SizeExample? = (text ?: stated)?.let { SizeExample(features, it.first, it.second) }

        fun acrossSites(): SizeExample? = if (words > 0 && chars > 0) SizeExample(features, words, chars) else null
    }

    /** Every part whose text is not counted yet counted: one count of a part, kept, however often the model is fitted. */
    private fun count(s: SourceStore) {
        while (true) {
            val parts = s.parts.uncounted(COUNTED_AT_ONCE)
            if (parts.isEmpty()) return
            s.writing.withLock { parts.forEach { s.parts.setCounted(it.itemId, it.partId, it.content.length, WordCount.of(it.content)) } }
        }
    }

    /** What every source of the type knows of its works, each source read in one pass. */
    private fun known(type: TypeStore): Map<Long, Known> {
        val works = HashMap<Long, Known>()
        type.sources.forEach { s ->
            count(s)
            val bound = s.works.allOf(s.id)
            fun of(id: String) = bound[id]?.let { work -> works.getOrPut(work) { Known() } }
            bound.keys.forEach { of(it)!!.features += WordsPerChar.SOURCE + s.id }
            val corrections = s.corrections.allFacets()
            s.normalizedTags(null, corrections).forEach { (id, tags) -> of(id)?.features?.addAll(tags.map { WordsPerChar.TAG + it }) }
            s.schema.facets.firstOrNull { it.role == FacetRole.AUTHOR }?.let { f ->
                val authors = s.authors.bindingsOf(s.id)
                s.items.forEachFacet(f.key) { id, keys -> keys.forEach { k -> authors[k]?.let { of(id)?.features?.add(WordsPerChar.AUTHOR + it) } } }
            }
            s.schema.facets.firstOrNull { it.standard === StandardLanguages.LANGUAGE }?.let { f ->
                s.items.forEachFacet(f.key) { id, keys -> keys.mapNotNull(StandardLanguages::code).forEach { of(id)?.features?.add(WordsPerChar.LANGUAGE + it) } }
            }
            corrections.filter { it.facet == StandardLanguages.KEY && it.added }.forEach { of(it.itemId)?.features?.add(WordsPerChar.LANGUAGE + it.key) }
            val words = wordsKey(s)?.let(s.items::number).orEmpty()
            val chars = charsKey(s)?.let(s.items::number).orEmpty()
            words.forEach { (id, n) -> of(id)?.let { it.words = maxOf(it.words, n) } }
            chars.forEach { (id, n) ->
                val k = of(id) ?: return@forEach
                k.chars = maxOf(k.chars, n)
                val w = words[id]
                if (w != null && w > 0 && n > 0) k.stated = w to n
            }
            s.parts.counted().forEach { (id, count) ->
                val k = of(id) ?: return@forEach
                if (count.first > 0 && count.second > (k.text?.second ?: 0.0)) k.text = count
            }
        }
        return works
    }

    /** The model of the type fitted, the works of two sites near enough to it let in, and every work's ratio kept. */
    fun fit(type: TypeStore) {
        val started = System.currentTimeMillis()
        val works = known(type)
        val sure = works.values.mapNotNull { it.own() }
        if (sure.isEmpty()) return
        val error = WordsPerChar.outOfFold(sure)
        val first = WordsPerChar.fit(sure)
        val across = works.values.filter { it.own() == null }.mapNotNull { k -> k.acrossSites()?.let { k to it } }
        val near = across.filter { (_, e) -> error.isNaN() || abs(e.logRatio - first.log(e.features)) <= TRUST * error }.toMap()
        val model = WordsPerChar.fit(sure + near.values)
        type.sizes.keep(works.mapValues { (_, k) -> model.of(k.features, k.own() ?: near[k]) })
        log.info(
            "{}: the words to a character of {} works, learnt from {} of their own numbers and {} of two sites of {}, out of fold {} by a logarithm, the mean alone {}, in {} ms",
            type.id, works.size, sure.size, near.size, across.size, "%.4f".format(error),
            "%.4f".format(WordsPerChar.outOfFold(sure.map { SizeExample(emptyList(), it.words, it.chars) })), System.currentTimeMillis() - started,
        )
    }

    // --- The fitting, in the background ---

    @EventListener(ApplicationReadyEvent::class)
    fun start() = stores.types.forEach { schedule(it, START_DELAY_SECONDS, "start") }

    @EventListener
    fun itemsChanged(event: ItemsChanged) = schedule(stores.typeOf(event.source), QUIET_SECONDS, "items")

    @EventListener
    fun partsSaved(event: PartsSaved) = schedule(stores.typeOf(event.source), QUIET_SECONDS, "parts")

    @EventListener
    fun answered(event: FacetAnswered) {
        val s = stores.source(event.source) ?: return
        if (event.facet == StandardLanguages.KEY || event.facet in s.tagFacets) schedule(stores.typeOf(event.source), QUIET_SECONDS, "answer")
    }

    @EventListener
    fun tagsChanged(event: TagsChanged) {
        stores.type(event.type)?.let { schedule(it, QUIET_SECONDS, "tags") }
    }

    private fun schedule(type: TypeStore, delaySeconds: Long, reason: String) {
        if (!sized(type)) return
        val work = Work(FITTING, type = type.id)
        workload.plan(work, Instant.now().plusSeconds(delaySeconds), reason)
        val next = scheduler.schedule({
            try {
                workload.run(work) { fit(type) }
            } catch (e: Exception) {
                log.warn("{}: the words to a character were not worked out: {}", type.id, e.message, e)
            }
        }, delaySeconds, TimeUnit.SECONDS)
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

        private const val COUNTED_AT_ONCE = 100

        /** How many errors of the model the ratio two sites give may stand off it: nearer, they speak of one text; further, one of them holds a part or another version. */
        private const val TRUST = 3.0
    }
}

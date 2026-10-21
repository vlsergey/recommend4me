package io.github.vlsergey.recommend4me.tagtext

import io.github.vlsergey.recommend4me.correction.FacetAnswered
import io.github.vlsergey.recommend4me.database.Databases
import io.github.vlsergey.recommend4me.matrix.Blas
import io.github.vlsergey.recommend4me.matrix.Floats
import io.github.vlsergey.recommend4me.part.Paragraphs
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.tag.TagRepository
import io.github.vlsergey.recommend4me.vector.Vectors
import io.github.vlsergey.recommend4me.workload.Work
import io.github.vlsergey.recommend4me.workload.Workload
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

private val log = LoggerFactory.getLogger(TextTags::class.java)

/** A user's answer on a tag offered by a paragraph was kept: the model of the closeness is fitted anew after the quiet. */
data class TagTextAnswered(val source: String)

/**
 * A tag offered by the text: its key, closeness, chance when the model is fitted, the paragraph
 * quoted, and the address it is read at on the site ([url]).
 */
class TextTag(
    val key: String, val closeness: Double, val chance: Double?, val partId: String, val partTitle: String?, val paragraph: Int, val quote: String,
    val url: String,
)

/** The tags offered by a work's text, and whether its paragraphs are still to be encoded, and whether the model is fitted. */
class TextTagList(val pending: Boolean, val fitted: Boolean, val tags: List<TextTag>)

/**
 * THE TAGS A WORK'S OWN TEXT IS CLOSEST TO — a list apart from the model's chances, which come of a
 * matrix of features and ids (the user, 2026-10-21): every tag held against every paragraph of the
 * work's chapters ([Paragraphs]), at the asking, of the vectors kept — the paragraphs' encoded in the
 * background, the tags' names'. A tag's closeness is the mean of its three closest paragraphs, by
 * its closest name; its closest paragraph quoted beside it. Not offered: a tag the site gives the
 * work, one answered on, one ignored, and a paragraph answered on for that tag.
 *
 * The user's three answers on a tag and its paragraph ([TagTextVerdict]) teach the model of the
 * closeness ([TagTextModel]), fitted in the queue of the computations once they pause; fitted, it
 * gives every tag its chance by its quoted paragraph, and the tags are ordered by it.
 */
@Service
class TextTags(
    private val stores: Stores,
    private val plugins: Plugins,
    databases: Databases,
    private val events: ApplicationEventPublisher,
    private val workload: Workload,
) {
    private val models = TagTextModelRepository(databases.dictionaries.dsl)
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "tag-text-model").apply { isDaemon = true } }
    private val pending = AtomicReference<ScheduledFuture<*>?>()

    /** The tags offered by the text of the item; null when its source has no tags. */
    fun of(store: SourceStore, itemId: String): TextTagList? {
        val facet = store.tagFacets.firstOrNull() ?: return null
        val encoder = plugins.textEncoder() ?: return TextTagList(pending = false, fitted = false, tags = emptyList())
        val model = models.read()
        val paragraphs = store.parts.paragraphVectors(itemId, Paragraphs.keyOf(encoder.id))
        val waiting = store.parts.paragraphsPending(itemId, Paragraphs.keyOf(encoder.id))
        if (paragraphs.isEmpty()) return TextTagList(waiting, model != null, emptyList())
        val started = System.currentTimeMillis()
        val left = leftOut(store, facet, itemId)
        val vectors = store.tags.vectors(plugins.pluginOf(encoder), encoder.id).filterKeys { it !in left }
        if (vectors.isEmpty()) return TextTagList(waiting, model != null, emptyList())
        // A paragraph answered on for a tag, of any cutting, by the hash of its text
        val answered = store.tagTextAnswers.ofItem(itemId).mapNotNull { a -> a.hash?.let { a.tag to it } }.toSet()
        val tags = vectors.keys.toList()
        val tagIds = LongArray(tags.size) { tags[it].toLong() }
        // Every name of every tag a row, and the tag each is of
        val ownerOf = ArrayList<Int>()
        vectors.values.forEachIndexed { t, names -> names.forEach { _ -> ownerOf += t } }
        val dim = paragraphs.first().vector.size
        val names = Floats(ownerOf.size * dim)
        var row = 0
        vectors.values.forEach { list -> list.forEach { v -> for (q in 0 until dim) names[row * dim + q] = v[q]; row++ } }
        // Of every tag its three closest paragraphs not answered on for it, by its closest name: a block of paragraphs at a time
        val best = Array(tags.size) { DoubleArray(TOP) { Double.NEGATIVE_INFINITY } }
        val bestAt = Array(tags.size) { IntArray(TOP) { -1 } }
        val block = minOf(paragraphs.size, BLOCK)
        val p = Floats(block * dim)
        val s = Floats(block * ownerOf.size)
        val ofTag = DoubleArray(tags.size)
        for (from in paragraphs.indices step block) {
            val rows = minOf(block, paragraphs.size - from)
            for (k in 0 until rows) {
                val v = paragraphs[from + k].vector
                for (q in 0 until dim) p[k * dim + q] = v[q]
            }
            Blas.gemm(false, true, rows, ownerOf.size, dim, p, 0, dim, names, 0, dim, s, 0, ownerOf.size)
            for (k in 0 until rows) {
                val paragraph = paragraphs[from + k]
                ofTag.fill(Double.NEGATIVE_INFINITY)
                for (n in ownerOf.indices) {
                    val t = ownerOf[n]
                    val c = s[k * ownerOf.size + n].toDouble()
                    if (c > ofTag[t]) ofTag[t] = c
                }
                for (t in tags.indices) {
                    val c = ofTag[t]
                    if (c <= best[t][TOP - 1]) continue
                    if ((tagIds[t] to paragraph.hash) in answered) continue
                    var at = TOP - 1
                    while (at > 0 && best[t][at - 1] < c) {
                        best[t][at] = best[t][at - 1]
                        bestAt[t][at] = bestAt[t][at - 1]
                        at--
                    }
                    best[t][at] = c
                    bestAt[t][at] = from + k
                }
            }
        }
        val scored = tags.indices.filter { bestAt[it][0] >= 0 }.map { t ->
            val kept = best[t].filter { it > Double.NEGATIVE_INFINITY }
            val closeness = kept.average()
            val chance = model?.chance(tagIds[t], best[t][0])
            Triple(t, closeness, chance)
        }.sortedByDescending { (_, closeness, chance) -> chance ?: closeness }.take(SHOWN)
        // The paragraphs quoted, of their parts' texts
        val texts = HashMap<String, Pair<String?, List<String>>>()
        val out = scored.mapNotNull { (t, closeness, chance) ->
            val paragraph = paragraphs[bestAt[t][0]]
            val (title, own) = texts.getOrPut(paragraph.partId) {
                store.parts.text(itemId, paragraph.partId)?.let { (part, text) -> part.title to Paragraphs.of(text) } ?: (null to emptyList())
            }
            val quote = own.getOrNull(paragraph.number) ?: return@mapNotNull null
            TextTag(tags[t], closeness, chance, paragraph.partId, title, paragraph.number, quote, placeOf(store, itemId, paragraph.partId, quote))
        }
        log.info("{}: {} tags held against {} paragraphs of {} in {} ms", store.id, tags.size, paragraphs.size, itemId, System.currentTimeMillis() - started)
        return TextTagList(waiting, model != null, out)
    }

    /**
     * Where the paragraph [quote] is read on the site: the page of its part, with a text fragment of
     * its first words — the browser scrolls to them and marks them (`#:~:text=`).
     */
    private fun placeOf(store: SourceStore, itemId: String, partId: String, quote: String): String {
        val words = quote.lineSequence().first().split(Regex("\\s+")).filter { it.isNotEmpty() }.take(FRAGMENT_WORDS).joinToString(" ")
        // The dash, the comma and the ampersand are the fragment's own marks: escaped in the text
        val fragment = URLEncoder.encode(words, StandardCharsets.UTF_8).replace("+", "%20").replace("-", "%2D")
        return "${store.source.partUrl(itemId, partId)}#:~:text=$fragment"
    }

    /** The tags not offered for the item: the site's, those answered on, those ignored. */
    private fun leftOut(store: SourceStore, facet: String, itemId: String): Set<String> {
        val from = store.schema.facet(facet)?.examplesFrom
        val bound = store.tags.bindingsOf(store.id)
        val site = from?.let { f -> store.items.facetsOf(listOf(itemId))[itemId]?.get(f).orEmpty().mapNotNull { bound[it]?.let(TagRepository::key) } }.orEmpty()
        val answered = store.corrections.facetsOf(itemId).filter { it.facet == facet }.map { it.key }
        return (site + answered + store.ignoredTags()[facet].orEmpty()).toSet()
    }

    /**
     * The user's [verdict] on the tag [key] by the paragraph [paragraph] of the part [partId]: kept
     * with the closeness of the two now; of [TagTextVerdict.YES], the tag answered on the work as
     * added beside. False when there is no such tag or paragraph.
     */
    fun answer(store: SourceStore, itemId: String, key: String, partId: String, paragraph: Int, verdict: TagTextVerdict): Boolean {
        val facet = store.tagFacets.firstOrNull() ?: return false
        val tag = key.toLongOrNull() ?: return false
        val encoder = plugins.textEncoder() ?: return false
        val found = store.parts.paragraph(itemId, partId, paragraph, Paragraphs.keyOf(encoder.id)) ?: return false
        val vector = found.vector
        val names = store.tags.vectorsOf(tag, plugins.pluginOf(encoder), encoder.id).takeIf { it.isNotEmpty() } ?: return false
        val closeness = names.maxOf { name -> name.indices.sumOf { (name[it] * vector[it]).toDouble() } }
        val now = Instant.now()
        store.tagTextAnswers.answer(TagTextAnswer(itemId, tag, partId, paragraph, found.hash, verdict, closeness.toFloat()), now)
        if (verdict == TagTextVerdict.YES) {
            store.corrections.setFacet(itemId, facet, key, true, null, now)
            events.publishEvent(FacetAnswered(store.type, store.id, itemId, facet))
        }
        events.publishEvent(TagTextAnswered(store.id))
        return true
    }

    /**
     * At the start the answers given before they were bound to their paragraphs' hashes bound —
     * of the texts and the cutting they were given on — and the model fitted anew: of the answers
     * of the cutting in use alone, one of another kept before.
     */
    @EventListener(ApplicationReadyEvent::class)
    fun started() {
        stores.sources.forEach { s ->
            val unbound = s.tagTextAnswers.unbound()
            if (unbound.isEmpty()) return@forEach
            var bound = 0
            unbound.groupBy { it.itemId to it.partId }.forEach { (where, answers) ->
                val text = s.parts.text(where.first, where.second)?.second ?: return@forEach
                answers.forEach { a ->
                    Paragraphs.ofCutting(text, a.cutting).getOrNull(a.paragraph)?.let { s.tagTextAnswers.bind(a, Vectors.hash(it)); bound++ }
                }
            }
            log.info("{}: {} of {} answers on the tags by the text bound to their paragraphs' hashes", s.id, bound, unbound.size)
        }
        answered(TagTextAnswered("start"))
    }

    /** An answer kept: the model fitted anew once the answers pause. */
    @EventListener
    fun answered(event: TagTextAnswered) {
        workload.plan(FITTING, Instant.now().plusSeconds(QUIET_SECONDS), REASON)
        val next = scheduler.schedule({
            try {
                workload.run(FITTING) { fit() }
            } catch (e: Exception) {
                log.warn("The model of the tags' closeness to the texts was not fitted: {}", e.message, e)
            }
        }, QUIET_SECONDS, TimeUnit.SECONDS)
        pending.getAndSet(next)?.cancel(false)
    }

    /** The model fitted of every source's answers and kept; none while there are not answers of both kinds. */
    fun fit() {
        val answers = stores.sources.flatMap { it.tagTextAnswers.teaching() }
        val model = TagTextModel.fit(answers)
        models.keep(model, answers.groupingBy { it.tag }.eachCount(), Instant.now())
        log.info(
            "The model of the tags' closeness to the texts fitted of {} answers: {}", answers.size,
            model?.let { "slope %.2f, intercept %.2f, %d tags' own biases".format(it.slope, it.intercept, it.bias.size) } ?: "none, not answers of both kinds",
        )
    }

    @PreDestroy
    fun shutdown() {
        scheduler.shutdownNow()
    }

    private companion object {
        /** The paragraphs a tag's closeness is the mean of. */
        const val TOP = 3

        /** The tags offered at most. */
        const val SHOWN = 30

        /** The first words of a paragraph its address on the site scrolls to. */
        const val FRAGMENT_WORDS = 8

        /** Paragraphs held against the tags at once: memory, not meaning. */
        const val BLOCK = 256

        /** The quiet after an answer before the model is fitted: the user answers in a row. */
        const val QUIET_SECONDS = 10L

        val FITTING = Work("tagTextModel")

        /** Why the model is fitted, as the queue of the works tells it. */
        const val REASON = "tagByText"
    }
}

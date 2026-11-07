package io.github.vlsergey.recommend4me.tagtext

import io.github.vlsergey.recommend4me.correction.FacetAnswered
import io.github.vlsergey.recommend4me.database.Databases
import io.github.vlsergey.recommend4me.encoder.TextEncoder
import io.github.vlsergey.recommend4me.part.Paragraph
import io.github.vlsergey.recommend4me.part.Paragraphs
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.tag.TagRepository
import io.github.vlsergey.recommend4me.vector.Vectors
import io.github.vlsergey.recommend4me.workload.Work
import io.github.vlsergey.recommend4me.workload.Workload
import jakarta.annotation.PreDestroy
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service

private val log = LoggerFactory.getLogger(TextTags::class.java)

/**
 * A user's answer on a tag offered by a paragraph was kept: the model of the closeness is fitted anew after the quiet.
 */
data class TagTextAnswered(val source: String)

/**
 * A tag offered by the text: its key, closeness, chance when the model is fitted, the paragraph quoted, and the address
 * it is read at on the site ([url]).
 */
class TextTag(
    val key: String,
    val closeness: Double,
    val chance: Double?,
    val partId: String,
    val partTitle: String?,
    val paragraph: Int,
    val quote: String,
    val url: String,
)

/**
 * A tag of the [key] found close to a work's [paragraph]: its [closeness], and its [chance] by the model when fitted.
 */
private class TextTagFound(
    val key: String,
    val closeness: Double,
    val chance: Double?,
    val paragraph: Paragraph,
)

/** The first words of a paragraph its address on the site scrolls to. */
private const val FRAGMENT_WORDS = 8

/**
 * Where the paragraph [quote] is read on the site: the page of its part, with a text fragment of its first words — the
 * browser scrolls to them and marks them (`#:~:text=`).
 */
private fun placeOf(store: SourceStore, itemId: String, partId: String, quote: String): String {
    val words =
        quote
            .lineSequence()
            .first()
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
            .take(FRAGMENT_WORDS)
            .joinToString(" ")
    // The dash, the comma and the ampersand are the fragment's own marks: escaped in the text
    val fragment = URLEncoder.encode(words, StandardCharsets.UTF_8).replace("+", "%20").replace("-", "%2D")
    return "${store.source.partUrl(itemId, partId)}#:~:text=$fragment"
}

/**
 * The tags offered by a work's text, and whether its paragraphs are still to be encoded, and whether the model is
 * fitted.
 */
class TextTagList(val pending: Boolean, val fitted: Boolean, val tags: List<TextTag>)

/**
 * THE TAGS A WORK'S OWN TEXT IS CLOSEST TO — a list apart from the model's chances, which come of a matrix of features
 * and ids (the user, 2026-10-21): every tag held against every paragraph of the work's chapters ([Paragraphs]), at the
 * asking, of the vectors kept — the paragraphs' encoded in the background, the tags' names' ([ClosestParagraphs]). A
 * tag's closeness is the mean of its three closest paragraphs, by its closest name; its closest paragraph quoted beside
 * it. Not offered: a tag the site gives the work, one answered on, one ignored, and a paragraph answered on for that
 * tag.
 *
 * The user's three answers on a tag and its paragraph ([TagTextVerdict]) teach the model of the closeness
 * ([TagTextModel]), fitted in the queue of the computations once they pause; fitted, it gives every tag its chance by
 * its quoted paragraph, and the tags are ordered by it.
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
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "tag-text-model").apply { isDaemon = true }
    }
    private val pending = AtomicReference<ScheduledFuture<*>?>()

    /** The tags offered by the text of the item; null when its source has no tags. */
    fun of(store: SourceStore, itemId: String): TextTagList? {
        val facet = store.tagFacets.firstOrNull() ?: return null
        val encoder = plugins.textEncoder()
        return if (encoder == null) TextTagList(pending = false, fitted = false, tags = emptyList())
        else offered(store, facet, itemId, encoder)
    }

    /**
     * The tags of the [facet] offered by the text of the item, of its paragraphs as the [encoder] encoded them: none
     * while none is encoded, and none of a tag left out ([leftOut]).
     */
    private fun offered(store: SourceStore, facet: String, itemId: String, encoder: TextEncoder): TextTagList {
        val model = models.read()
        val paragraphs = store.parts.paragraphs.paragraphVectors(itemId, Paragraphs.keyOf(encoder.id))
        val waiting = store.parts.paragraphs.paragraphsPending(itemId, Paragraphs.keyOf(encoder.id))
        val started = System.currentTimeMillis()
        val vectors =
            if (paragraphs.isEmpty()) emptyMap()
            else {
                val left = leftOut(store, facet, itemId)
                store.tags.vectors.vectors(plugins.pluginOf(encoder), encoder.id).filterKeys { it !in left }
            }
        val tags = if (vectors.isEmpty()) emptyList() else closest(store, itemId, paragraphs, vectors, model, started)
        return TextTagList(waiting, model != null, tags)
    }

    /**
     * The tags of [vectors] (a tag's key to its names' vectors) closest to the work's [paragraphs], the likeliest first
     * by the [model] when it is fitted, else the closest: a tag's closeness the mean of its closest paragraphs not
     * answered on for it, its closest quoted.
     */
    private fun closest(
        store: SourceStore,
        itemId: String,
        paragraphs: List<Paragraph>,
        vectors: Map<String, List<FloatArray>>,
        model: TagTextModel?,
        started: Long,
    ): List<TextTag> {
        // A paragraph answered on for a tag, of any cutting, by the hash of its text
        val answered = store.tagTextAnswers.ofItem(itemId).mapNotNull { a -> a.hash?.let { a.tag to it } }.toSet()
        val tags = vectors.keys.toList()
        val tagIds = LongArray(tags.size) { tags[it].toLong() }
        val found = ClosestParagraphs.of(paragraphs, tagIds, vectors.values, answered)
        val scored =
            tags.indices
                .filter { found.at[it][0] >= 0 }
                .map { t ->
                    val kept = found.best[t].filter { it > Double.NEGATIVE_INFINITY }
                    val closeness = kept.average()
                    val chance = model?.chance(tagIds[t], found.best[t][0])
                    Triple(t, closeness, chance)
                }
                .sortedByDescending { (_, closeness, chance) -> chance ?: closeness }
                .take(SHOWN)
        val out =
            quoted(
                store,
                itemId,
                scored.map { (t, closeness, chance) ->
                    TextTagFound(tags[t], closeness, chance, paragraphs[found.at[t][0]])
                },
            )
        log.info(
            "{}: {} tags held against {} paragraphs of {} in {} ms",
            store.id,
            tags.size,
            paragraphs.size,
            itemId,
            System.currentTimeMillis() - started,
        )
        return out
    }

    /**
     * The tags [found] with their closest paragraphs quoted, of their parts' texts; one whose text is gone left out.
     */
    private fun quoted(store: SourceStore, itemId: String, found: List<TextTagFound>): List<TextTag> {
        val texts = HashMap<String, Pair<String?, List<String>>>()
        return found.mapNotNull { f ->
            val paragraph = f.paragraph
            val (title, own) =
                texts.getOrPut(paragraph.partId) {
                    store.parts.text(itemId, paragraph.partId)?.let { (part, text) ->
                        part.title to Paragraphs.of(text)
                    } ?: (null to emptyList())
                }
            own.getOrNull(paragraph.number)?.let { quote ->
                TextTag(
                    f.key,
                    f.closeness,
                    f.chance,
                    paragraph.partId,
                    title,
                    paragraph.number,
                    quote,
                    placeOf(store, itemId, paragraph.partId, quote),
                )
            }
        }
    }

    /**
     * The tags not offered for the item: the site's, those answered on, those ignored — and of the tags' hierarchy,
     * those its answers tell already: above a tag it has, under a tag it has not.
     */
    private fun leftOut(store: SourceStore, facet: String, itemId: String): Set<String> {
        val from = store.schema.facet(facet)?.examplesFrom
        val bound = store.tags.bindingsOf(store.id)
        val site =
            from
                ?.let { f ->
                    store.items.facetsOf(listOf(itemId))[itemId]?.get(f).orEmpty().mapNotNull {
                        bound[it]?.let(TagRepository::key)
                    }
                }
                .orEmpty()
        val answers = store.corrections.facetsOf(itemId).filter { it.facet == facet }
        val rejected = answers.filter { !it.added }.map { it.key }.toSet()
        // Of the tags' hierarchy: a tag above one the work has — the site's or the user's yes — it has
        // too; a tag under one the user said it has not, it has not either. Neither is asked again
        val hierarchy = store.hierarchies()[facet]
        val has = (site.filter { it !in rejected } + answers.filter { it.added }.map { it.key }).toSet()
        val implied = hierarchy?.let { h -> has.flatMap(h::ancestors) }.orEmpty()
        val denied = hierarchy?.let { h -> rejected.flatMap(h::descendants) }.orEmpty()
        return (site + answers.map { it.key } + store.ignoredTags()[facet].orEmpty() + implied + denied).toSet()
    }

    /**
     * The user's [verdict] on the tag [key] by the paragraph [paragraph] of the part [partId]: kept with the closeness
     * of the two now; of [TagTextVerdict.YES], the tag answered on the work as added beside. False when there is no
     * such tag or paragraph.
     */
    fun answer(
        store: SourceStore,
        itemId: String,
        key: String,
        partId: String,
        paragraph: Int,
        verdict: TagTextVerdict,
    ): Boolean {
        val facet = store.tagFacets.firstOrNull()
        val tag = key.toLongOrNull()
        val held = if (facet != null && tag != null) heldAgainst(store, itemId, tag, partId, paragraph) else null
        if (facet != null && tag != null && held != null) {
            val (found, closeness) = held
            val now = Instant.now()
            store.tagTextAnswers.answer(
                TagTextAnswer(itemId, tag, partId, paragraph, found.hash, verdict, closeness.toFloat()),
                now,
            )
            if (verdict == TagTextVerdict.YES) {
                store.corrections.setFacet(itemId, facet, key, true, null, now)
                events.publishEvent(FacetAnswered(store.type, store.id, itemId, facet))
            }
            events.publishEvent(TagTextAnswered(store.id))
        }
        return held != null
    }

    /**
     * The paragraph [paragraph] of the part [partId] and its closeness to the [tag] now, by the tag's closest name;
     * null when there is no encoder of texts, no such paragraph encoded, or no name of the tag encoded.
     */
    private fun heldAgainst(
        store: SourceStore,
        itemId: String,
        tag: Long,
        partId: String,
        paragraph: Int,
    ): Pair<Paragraph, Double>? {
        val encoder = plugins.textEncoder() ?: return null
        val found = store.parts.paragraphs.paragraph(itemId, partId, paragraph, Paragraphs.keyOf(encoder.id))
        val names = found?.let { store.tags.vectors.vectorsOf(tag, plugins.pluginOf(encoder), encoder.id) }.orEmpty()
        return if (found == null || names.isEmpty()) null
        else found to names.maxOf { name -> name.indices.sumOf { (name[it] * found.vector[it]).toDouble() } }
    }

    /**
     * At the start the answers given before they were bound to their paragraphs' hashes bound — of the texts and the
     * cutting they were given on — and the model fitted anew: of the answers of the cutting in use alone, one of
     * another kept before.
     */
    @EventListener(ApplicationReadyEvent::class)
    fun started() {
        stores.sources.forEach { s ->
            val unbound = s.tagTextAnswers.unbound()
            if (unbound.isEmpty()) return@forEach
            var bound = 0
            unbound
                .groupBy { it.itemId to it.partId }
                .forEach { (where, answers) ->
                    val text = s.parts.text(where.first, where.second)?.second ?: return@forEach
                    answers.forEach { a ->
                        Paragraphs.ofCutting(text, a.cutting).getOrNull(a.paragraph)?.let {
                            s.tagTextAnswers.bind(a, Vectors.hash(it))
                            bound++
                        }
                    }
                }
            log.info(
                "{}: {} of {} answers on the tags by the text bound to their paragraphs' hashes",
                s.id,
                bound,
                unbound.size,
            )
        }
        answered()
    }

    /** An answer kept ([TagTextAnswered]): the model fitted anew once the answers pause. */
    @Suppress("TooGenericExceptionCaught")
    @EventListener(TagTextAnswered::class)
    fun answered() {
        workload.plan(FITTING, Instant.now().plusSeconds(QUIET_SECONDS), REASON)
        val next =
            scheduler.schedule(
                {
                    try {
                        workload.run(FITTING) { fit() }
                    } catch (e: Exception) {
                        // A BOUNDARY: this runs on the scheduler's thread, where nothing would hear of a failure of
                        // the queue or of the fitting; it is logged
                        log.warn("The model of the tags' closeness to the texts was not fitted: {}", e.message, e)
                    }
                },
                QUIET_SECONDS,
                TimeUnit.SECONDS,
            )
        pending.getAndSet(next)?.cancel(false)
    }

    /** The model fitted of every source's answers and kept; none while there are not answers of both kinds. */
    fun fit() {
        val answers = stores.sources.flatMap { it.tagTextAnswers.teaching() }
        val model = TagTextModel.fit(answers)
        models.keep(model, answers.groupingBy { it.tag }.eachCount(), Instant.now())
        log.info(
            "The model of the tags' closeness to the texts fitted of {} answers: {}",
            answers.size,
            model?.let {
                "slope %.2f, intercept %.2f, %d tags' own biases".format(it.slope, it.intercept, it.bias.size)
            } ?: "none, not answers of both kinds",
        )
    }

    @PreDestroy
    fun shutdown() {
        scheduler.shutdownNow()
    }

    private companion object {
        /** The tags offered at most. */
        const val SHOWN = 30

        /** The quiet after an answer before the model is fitted: the user answers in a row. */
        const val QUIET_SECONDS = 10L

        val FITTING = Work("tagTextModel")

        /** Why the model is fitted, as the queue of the works tells it. */
        const val REASON = "tagByText"
    }
}

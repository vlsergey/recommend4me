package io.github.vlsergey.recommend4me.agerating

import io.github.vlsergey.recommend4me.contenttype.AgeRating
import io.github.vlsergey.recommend4me.correction.FacetAnswered
import io.github.vlsergey.recommend4me.database.Databases
import io.github.vlsergey.recommend4me.item.EveryItemFacets
import io.github.vlsergey.recommend4me.item.ItemsChanged
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.suggestion.Chance
import io.github.vlsergey.recommend4me.suggestion.FacetModel
import io.github.vlsergey.recommend4me.suggestion.FacetModelRead
import io.github.vlsergey.recommend4me.tag.TagsChanged
import io.github.vlsergey.recommend4me.workload.Work
import io.github.vlsergey.recommend4me.workload.Workload
import jakarta.annotation.PreDestroy
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service

private val log = LoggerFactory.getLogger(AgeRatings::class.java)

/**
 * THE AGE RATING OF EVERY WORK ([AgeRating]), worked out of its tags by one model of every source of every type at once
 * ([AgeRatingModel]) — the sites that state a rating teach the others: learnt from every work whose rating is known to
 * lie between two steps of the scale — the user's answer, the rating the site states, the bound it tells ("18+", a game
 * of F95zone) — fitted in the queue of the computations after a quiet, and kept. A work's chances are of the ratings
 * its site lets it have alone; the likeliest is given it
 * ([FacetDef.required][io.github.vlsergey.recommend4me.source.FacetDef.required]), the site's when it lets one alone.
 */
@Service
class AgeRatings(private val stores: Stores, private val workload: Workload, databases: Databases) : FacetModel {
    override val facet: String = AgeRating.KEY

    private val models = AgeRatingRepository(databases.dictionaries.dsl)
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "age-rating").apply { isDaemon = true }
    }
    private val pending = AtomicReference<ScheduledFuture<*>?>()

    private val keys = AgeRating.VALUES.map { it.key }

    private val evidence = RatingEvidence(keys)

    /** The sources whose layer has the age rating. */
    private fun sources(): List<SourceStore> = stores.sources.filter { it.schema.facet(AgeRating.KEY) != null }

    override fun read(store: SourceStore, ids: Collection<String>?, every: EveryItemFacets?): FacetModelRead {
        // No model read for a source without the age rating
        val model =
            models.takeIf { store.schema.facet(AgeRating.KEY) != null }?.read() ?: return FacetModelRead { emptyMap() }
        val given = every?.takeIf { ids == null }
        val answers = given?.allCorrections ?: evidence.answersOf(store, ids)
        val features = evidence.featuresOf(store, ids, answers, given?.site)
        val told = evidence.told(store, ids, answers, given?.site)
        return FacetModelRead { chancesOf(model, features, told) }
    }

    /** The chances of every work [told] of by the [model], of its [features]. */
    private fun chancesOf(
        model: AgeRatingModel,
        features: Map<String, List<String>>,
        told: Map<String, RatingEvidence.Told>,
    ): Map<String, Map<String, Chance>> = told.mapValues { (id, t) ->
        // Of the ratings the site lets the work have alone, their chances made one in all
        val allowed = t.allowed.map { keys.indexOf(it) }.filter { it >= 0 }.ifEmpty { keys.indices.toList() }
        val p = model.chances(features[id].orEmpty())
        val sum = allowed.sumOf { p[it] }.takeIf { it > 0 } ?: 1.0
        allowed.associate { k ->
            keys[k] to Chance(id, AgeRating.KEY, keys[k], had = keys[k] in t.stated, chance = p[k] / sum)
        }
    }

    // --- The fitting, in the background ---

    @EventListener(ApplicationReadyEvent::class) fun start() = schedule(START_DELAY_SECONDS, "start")

    @EventListener
    fun itemsChanged(event: ItemsChanged) {
        if (stores.source(event.source)?.schema?.facet(AgeRating.KEY) != null) schedule(QUIET_SECONDS, "items")
    }

    @EventListener
    fun answered(event: FacetAnswered) {
        val s = stores.source(event.source) ?: return
        if (event.facet == AgeRating.KEY || event.facet in s.tagFacets) schedule(QUIET_SECONDS, "answer")
    }

    @EventListener(TagsChanged::class) fun tagsChanged() = schedule(QUIET_SECONDS, "tags")

    private fun schedule(delaySeconds: Long, reason: String) {
        workload.plan(FITTING, Instant.now().plusSeconds(delaySeconds), reason)
        val next =
            scheduler.schedule(
                {
                    // The boundary of the scheduler's thread: whatever a fitting throws is logged here, or the
                    // future it was put in would keep it unseen
                    @Suppress("TooGenericExceptionCaught")
                    try {
                        workload.run(FITTING) { fit() }
                    } catch (e: Exception) {
                        log.warn("The model of the age rating was not fitted: {}", e.message, e)
                    }
                },
                delaySeconds,
                TimeUnit.SECONDS,
            )
        pending.getAndSet(next)?.cancel(false)
    }

    /** The model fitted on every work of every source whose rating is known to lie between two steps, and kept. */
    fun fit() {
        val started = System.currentTimeMillis()
        val examples = sources().flatMap { evidence.examples(it) }
        val model = AgeRatingModel.fit(examples, keys.size)
        val works = HashMap<String, Int>()
        examples.forEach { e -> e.features.forEach { works.merge(it, 1, Int::plus) } }
        models.keep(model, works, examples.size, Instant.now())
        log.info(
            "The model of the age rating fitted on {} works ({} of one rating), {} features — the sites {} — " +
                "loss {} in {} ms",
            examples.size,
            examples.count { it.low == it.high },
            model?.weights?.size ?: 0,
            model?.weights?.filterKeys { it.startsWith("source:") }?.mapValues { "%.2f".format(it.value) },
            model?.let { "%.4f".format(AgeRatingModel.loss(it, examples)) },
            System.currentTimeMillis() - started,
        )
    }

    @PreDestroy
    fun shutdown() {
        scheduler.shutdownNow()
    }

    companion object {
        val FITTING = Work("ageRatingModel")

        private const val START_DELAY_SECONDS = 60L
        private const val QUIET_SECONDS = 60L
    }
}

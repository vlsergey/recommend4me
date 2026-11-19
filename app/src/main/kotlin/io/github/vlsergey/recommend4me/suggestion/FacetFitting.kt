package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.encoder.TextEncoder
import io.github.vlsergey.recommend4me.source.FacetDef
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.universe.CharactersRead
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(FacetFitting::class.java)

/**
 * ONE FACET'S MODEL FITTED AGAIN when what it learns from changed, and every work's chances of it made and their values
 * kept — the context of the facets after it in the chain. Every walk of it over the works a portion at a time
 * ([FacetPass]): the works' values, of which its value side is made ([ValueSide]); the fitting's own, as many as it
 * needs ([FacetPortions]); the chances made ([FacetScoring]). One fitting of a facet at a time; a fitting of one facet
 * does not wait for a long fitting of another.
 */
internal class FacetFitting(private val reading: FacetReading, private val scoring: FacetScoring) {
    /** One lock a facet, by "source/facet". */
    private val locks = ConcurrentHashMap<String, ReentrantLock>()

    /**
     * The model of the [k]-th of the [facets] fitted again when what it learns from changed — every setting tried anew
     * when [anew], the user asked for it ([FacetRefits.searchAnew]) — and of a calibrated one its cutoff made of every
     * work's chances ([FacetScoring]); the values it gives the works filled after ([FacetModelsFitted]). [stop] is
     * asked between the portions and the stages, and by the fitting between its steps: the application stops, and the
     * work is given up with a [CancellationException] before anything half made is kept. [characters]: the dictionary's
     * characters, read once for the pass.
     */
    fun fit(
        store: SourceStore,
        facets: List<FacetDef>,
        k: Int,
        suggester: FacetSuggester?,
        encoder: TextEncoder,
        characters: CharactersRead,
        stop: () -> Boolean,
        anew: Boolean = false,
    ) {
        val facet = facets[k]
        locks
            .computeIfAbsent("${store.id}/${facet.key}") { ReentrantLock() }
            .withLock {
                val checkpoint = {
                    if (stop()) throw CancellationException("${store.id}: ${facet.key} given up: the application stops")
                }
                val pass = FacetPass(store, facet, k, encoder, characters, reading, checkpoint)
                // Of no suggester, the drafts and the models of their own alone
                scoring.walk(pass, suggester?.let { modelled(pass, it, stop, anew, checkpoint) })
            }
    }

    /**
     * The facet's model of the [pass] by the [suggester]: its value side read of a walk over the works' values, the
     * model kept or fitted again ([modelOf]); null when the facet has no value to choose from or the suggester fits
     * none.
     */
    private fun modelled(
        pass: FacetPass,
        suggester: FacetSuggester,
        stop: () -> Boolean,
        anew: Boolean,
        checkpoint: () -> Unit,
    ): PassModel? {
        val store = pass.store
        val facet = pass.facet
        val started = System.currentTimeMillis()
        val works = FacetPortions.answered(store, facet.key)
        // The values a model learns of are the dictionary's, and what the works it learns of have and were answered
        // of: no walk over every work to count them (owner, 2026-11-19). A value no work it learnt of had — a pairing
        // of a work's own characters — is scored by its features as the works are read ([KeptValues])
        val counted = pass.counted(works)
        val side = pass.side(counted, store.corrections.allFacets())
        val read = System.currentTimeMillis() - started
        checkpoint()
        val portions = FacetPortions(pass, side, works)
        // The task kept in files when asked for, a portion a file: its fitting made again and measured apart
        System.getenv(TASKS_FOLDER)?.let { dir -> keepTasks(store, facet, portions, dir) }
        // Of no work answered on, nothing to learn of: the works' values are the site's and the drafts' alone
        val model =
            if (side.values.isEmpty() || works.isEmpty()) null
            else modelOf(store, facet, suggester, portions, side, stop, anew)
        checkpoint()
        log.info(
            "{}: {} of {} works, {} of them answered on, and {} values read in {} ms, its model {} in {} ms",
            store.id,
            facet.key,
            counted.works,
            works.size,
            side.values.size,
            read,
            if (model == null) "none" else "made",
            System.currentTimeMillis() - started - read,
        )
        // The calibration kept with the model as it is now
        return model?.let { PassModel(suggester, it, side, store.suggestions.scoring(facet.key)?.calibration) }
    }

    /** Every portion of the facet's task kept in a file of the folder [dir] ([SuggestionTaskFile]). */
    private fun keepTasks(store: SourceStore, facet: FacetDef, portions: FacetPortions, dir: String) {
        var n = 0
        portions.forEach { SuggestionTaskFile.write(it, Path.of(dir, "${store.id}-${facet.key}-${n++}.task.gz")) }
    }

    /**
     * The facet's model of the value [side]: the one kept when it was fitted on the same, else fitted again on the
     * [portions] — from the state fitted last unless [anew]. Null when the suggester fits none.
     */
    private fun modelOf(
        store: SourceStore,
        facet: FacetDef,
        suggester: FacetSuggester,
        portions: FacetPortions,
        side: ValueSide,
        stop: () -> Boolean,
        anew: Boolean,
    ): ByteArray? {
        val stored = store.suggestions.suggester(facet.key)?.takeIf { it.suggester == suggester.id }
        val before = store.suggestions.scoring(facet.key)?.takeIf { it.suggester == suggester.id }
        val kept = stored?.let { suggester.unpack(it.content, portions.values) }
        val current = !anew && kept != null && stored.basis == side.basis
        return if (current) keepValues(store, facet, suggester, side, stored, before, kept)
        else refit(store, facet, suggester, portions, side, if (anew) null else kept, stop)
    }

    /**
     * Of a model kept current: its model as it is kept ([before]); or, fitted before the values it chooses from or its
     * model as it is now were kept — the model of another suggester's making, the values' weights not in it — made of
     * the state kept and kept now, not fitted again.
     */
    private fun keepValues(
        store: SourceStore,
        facet: FacetDef,
        suggester: FacetSuggester,
        side: ValueSide,
        stored: StoredSuggester,
        before: KeptScoring?,
        kept: FittedSuggester,
    ): ByteArray {
        if (stored.values == side.values && before != null && suggester.weights(before.bytes) != null) {
            return before.bytes
        }
        return kept.model(side.task()).also { store.suggestions.keepValues(facet.key, side.values, it) }
    }

    /**
     * The model fitted again on the [portions] and kept, from the state fitted last when there is one ([previous]): the
     * data moved a little since. Null when the suggester fits none.
     */
    private fun refit(
        store: SourceStore,
        facet: FacetDef,
        suggester: FacetSuggester,
        portions: FacetPortions,
        side: ValueSide,
        previous: FittedSuggester?,
        stop: () -> Boolean,
    ): ByteArray? {
        val started = System.currentTimeMillis()
        val fresh = suggester.fit(portions, previous, stop) ?: return null
        if (stop()) throw CancellationException("${store.id}: ${facet.key} given up: the application stops")
        val millis = System.currentTimeMillis() - started
        val fit = SuggesterFit(fresh.report, millis, portions.items, side.values.size)
        val model = fresh.model(side.task())
        // Of what its folds said of the values the user answered on; of a fitting that held none out, the one kept
        val kept = store.suggestions.scoring(facet.key)?.calibration
        val calibration =
            fresh.heldOut?.let { held ->
                val answered = portions.answered(held)
                // The cutoff of the last pass over every work kept till the next works it out anew
                kept?.threshold?.let { Calibration.fit(answered, it) } ?: Calibration.fit(answered)
            } ?: kept
        store.suggestions.saveSuggester(
            facet.key,
            suggester.id,
            Instant.now(),
            side.basis,
            fresh.pack(),
            side.values,
            model,
            fit,
            calibration,
        )
        log.info(
            "{}: the suggester of {} fitted on {} items, {} values in {} ms; calibrated of {} answers, likely from {}",
            store.id,
            facet.key,
            portions.items,
            side.values.size,
            millis,
            calibration?.answers ?: 0,
            calibration?.threshold?.let { "%.3f".format(it) },
        )
        return model
    }

    companion object {
        /**
         * The environment variable naming the folder every task of a whole facet is kept in, a portion a file
         * ([SuggestionTaskFile]); unset, none is.
         */
        private const val TASKS_FOLDER = "RECOMMEND4ME_TASKS"
    }
}

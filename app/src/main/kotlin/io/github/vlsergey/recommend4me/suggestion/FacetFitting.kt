package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.database.DatabaseRefused
import io.github.vlsergey.recommend4me.encoder.TextEncoder
import io.github.vlsergey.recommend4me.source.FacetDef
import io.github.vlsergey.recommend4me.source.SourceStore
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(FacetFitting::class.java)

/**
 * ONE FACET'S MODEL FITTED AGAIN when what it learns from changed, and every work's chances of it made into those of
 * the pass — the context of the facets after it in the chain. One fitting of a facet at a time; a fitting of one facet
 * does not wait for a long fitting of another.
 */
internal class FacetFitting(private val chain: FacetChain, private val vocabularies: FacetVocabularies) {
    /** One lock a facet, by "source/facet". */
    private val locks = ConcurrentHashMap<String, ReentrantLock>()

    /**
     * The milliseconds from a fitting's start at which each of its stages ended, as its log tells them: the dictionary,
     * the reading, the weights kept unpacked, the fitting.
     */
    private class Stages {
        private val started = System.currentTimeMillis()
        var vocabulary = 0L
        var read = 0L
        var unpacked = 0L
        var fitted = 0L

        /** The milliseconds since the fitting began. */
        fun now() = System.currentTimeMillis() - started
    }

    /**
     * The model a fitting ends with, kept ([after], [FittedSuggester.model]), the one kept before it ([before]; null
     * when there was none of the suggester), and whether that one was [current]: fitted on the same and kept.
     */
    private class Model(val after: ByteArray, val before: ByteArray?, val current: Boolean)

    /**
     * The model of the [k]-th of the [facets] fitted again when what it learns from changed — every setting tried anew
     * when [anew], the user asked for it ([FacetRefits.searchAnew]) — and every work's chances of it made into [made],
     * the context of the facets after it. [stop] is asked between the stages, and by the fitting between its steps:
     * newer data came, and the work is given up with a [CancellationException] before anything made of the old is kept.
     * Answers the works whose given values a refit changed: every work scored by the model before and after it.
     *
     * [works] — every work read once for the whole pass, its inputs on the card: the facet's chances given to its
     * context ([WorksRead.given]) for the facets after it.
     */
    fun fit(
        store: SourceStore,
        facets: List<FacetDef>,
        k: Int,
        suggester: FacetSuggester,
        encoder: TextEncoder,
        made: MadeChances,
        stop: () -> Boolean,
        works: WorksRead,
        anew: Boolean = false,
    ): List<String> =
        lockOf(store, facets[k]).withLock {
            fit(store, facets[k], suggester, encoder, made, stop, anew, works).also {
                works.given(store, facets[k].key, made)
            }
        }

    /** One lock a facet: a fitting of one facet does not wait for a long fitting of another. */
    private fun lockOf(store: SourceStore, facet: FacetDef) =
        locks.computeIfAbsent("${store.id}/${facet.key}") { ReentrantLock() }

    /** [fit] of the works read for it, [works]: their inputs on the card for the fitting and the scoring after it. */
    private fun fit(
        store: SourceStore,
        facet: FacetDef,
        suggester: FacetSuggester,
        encoder: TextEncoder,
        made: MadeChances,
        stop: () -> Boolean,
        anew: Boolean,
        works: WorksRead,
    ): List<String> {
        val checkpoint = {
            if (stop()) throw CancellationException("${store.id}: ${facet.key} given up for newer changes")
        }
        val stages = Stages()
        val vocabulary =
            vocabularies.vocabularyOf(store, facet, null, made, works.characters, works.answers, site = works.site)
        stages.vocabulary = stages.now()
        checkpoint()
        val data = chain.load(store, facet, encoder, vocabulary, made, null, works)
        stages.read = stages.now()
        checkpoint()
        // The whole task kept in a file when asked for: its fitting made again and measured apart from the application
        System.getenv(TASKS_FOLDER)?.let { dir ->
            SuggestionTaskFile.write(data.task, Path.of(dir, "${store.id}-${facet.key}.task.gz"))
        }
        val model =
            if (data.values.isEmpty()) null else modelOf(store, facet, suggester, data, stop, anew, stages, checkpoint)
        return model?.let { madeOf(store, facet, suggester, data, made, it, stages) }.orEmpty()
    }

    /**
     * The facet's model for the task [data]: the one kept when it was fitted on the same, else fitted again — from the
     * state fitted last unless [anew]. Null when the suggester fits none.
     */
    private fun modelOf(
        store: SourceStore,
        facet: FacetDef,
        suggester: FacetSuggester,
        data: FacetData,
        stop: () -> Boolean,
        anew: Boolean,
        stages: Stages,
        checkpoint: () -> Unit,
    ): Model? {
        val stored = store.suggestions.suggester(facet.key)?.takeIf { it.suggester == suggester.id }
        val before = store.suggestions.scoring(facet.key)?.takeIf { it.suggester == suggester.id }
        val kept = stored?.let { suggester.unpack(it.content, data.task) }
        stages.unpacked = stages.now()
        checkpoint()
        val current = !anew && kept != null && stored.basis == data.basis
        val after =
            if (current) keepValues(store, facet, suggester, data, stored, before, kept)
            else refit(store, facet, suggester, data, if (anew) null else kept, stop, stages, checkpoint)
        return after?.let { Model(it, before?.bytes, current) }
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
        data: FacetData,
        stored: StoredSuggester,
        before: KeptScoring?,
        kept: FittedSuggester,
    ): ByteArray {
        if (stored.values == data.values && before != null && suggester.weights(before.bytes) != null) {
            return before.bytes
        }
        return kept.model(data.task).also { store.suggestions.keepValues(facet.key, data.values, it) }
    }

    /**
     * The model fitted again and kept, from the state fitted last when there is one ([previous]): the data moved a
     * little since. Null when the suggester fits none.
     */
    private fun refit(
        store: SourceStore,
        facet: FacetDef,
        suggester: FacetSuggester,
        data: FacetData,
        previous: FittedSuggester?,
        stop: () -> Boolean,
        stages: Stages,
        checkpoint: () -> Unit,
    ): ByteArray? {
        val fitStarted = System.currentTimeMillis()
        val fresh = suggester.fit(data.task, previous, stop) ?: return null
        checkpoint()
        val fit = SuggesterFit(fresh.report, System.currentTimeMillis() - fitStarted, data.ids.size, data.values.size)
        val model = fresh.model(data.task)
        store.suggestions.saveSuggester(
            facet.key,
            suggester.id,
            Instant.now(),
            data.basis,
            fresh.pack(),
            data.values,
            model,
            fit,
        )
        log.info(
            "{}: the suggester of {} fitted on {} items, {} values in {} ms, {} of them reading",
            store.id,
            facet.key,
            data.ids.size,
            data.values.size,
            stages.now(),
            stages.read,
        )
        return model
    }

    /**
     * Every work's chances of the facet by the [model] kept made into [made] — scored by the model as every work is
     * ([FacetSuggester.scoring]), the database refused; answers the works whose given values a refit changed — what the
     * model before the refit gave alone scored again, nothing of it kept. None made of a model the suggester cannot
     * read.
     */
    private fun madeOf(
        store: SourceStore,
        facet: FacetDef,
        suggester: FacetSuggester,
        data: FacetData,
        made: MadeChances,
        model: Model,
        stages: Stages,
    ): List<String> {
        stages.fitted = stages.now()
        fun shown(bytes: ByteArray, into: MadeChances) =
            DatabaseRefused.during("the chances of ${store.id}/${facet.key} of every work") {
                suggester.scoring(bytes, data.task)?.use { ShownChances.keep(into, facet, data, it, compare = true) }
            }
        val after = shown(model.after, made) ?: return emptyList()
        val scored = stages.now()
        // What the model before the refit gave alone: scored again, nothing of it kept
        val changedIds =
            if (model.current || !facet.infer) emptyList()
            else {
                val before = model.before?.let { shown(it, MadeChances()) }
                data.ids.filter { id -> before == null || before[id].orEmpty() != after[id].orEmpty() }
            }
        logMade(store, facet, data, after, changedIds, stages, scored)
        return changedIds
    }

    /**
     * The log of the chances made: the time of every stage, the works given other values, and how many values the model
     * gives the works — a model that gives one work dozens tells of itself.
     */
    private fun logMade(
        store: SourceStore,
        facet: FacetDef,
        data: FacetData,
        after: Map<String, Set<String>>,
        changedIds: List<String>,
        stages: Stages,
        scored: Long,
    ) {
        val most = after.maxByOrNull { it.value.size }
        log.info(
            "{}: chances of {} of {} items made in memory in {} ms — the dictionary {}, reading {}, the weights {}, " +
                "fitting {}, scoring {}; {} works given other values; " +
                "{} values given in all, {} works given more than {}, the most {} to {}",
            store.id,
            facet.key,
            data.ids.size,
            stages.now(),
            stages.vocabulary,
            stages.read - stages.vocabulary,
            stages.unpacked - stages.read,
            stages.fitted - stages.unpacked,
            scored - stages.fitted,
            changedIds.size,
            after.values.sumOf { it.size },
            after.values.count { it.size > MANY_GIVEN },
            MANY_GIVEN,
            most?.value?.size ?: 0,
            most?.key,
        )
    }

    companion object {
        /**
         * The environment variable naming the folder every task of a whole facet is kept in ([SuggestionTaskFile]);
         * unset, none is.
         */
        private const val TASKS_FOLDER = "RECOMMEND4ME_TASKS"

        /**
         * How many values given to one work the log of a refit counts the works above: a sign of a model gone wide, not
         * a rule.
         */
        private const val MANY_GIVEN = 10
    }
}

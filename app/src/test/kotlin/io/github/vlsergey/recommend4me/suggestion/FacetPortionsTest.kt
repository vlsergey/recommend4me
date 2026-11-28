package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.SiteTestBase
import io.github.vlsergey.recommend4me.api.model.TaskKind
import io.github.vlsergey.recommend4me.matrix.CardSource
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.task.Task
import io.github.vlsergey.recommend4me.task.inBackground
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Autowired

/**
 * THE PASS OVER THE FACETS A PORTION AT A TIME (user, 2026-11-17: nothing holds more than ten thousand works): the
 * works of the site cut into portions of a few works give the facets the same value sides, the same models and the same
 * values given every work as read in one portion — of a suggester whose model is sums over every work
 * ([CountingSuggester]).
 */
// More than a few seconds: in CI alone — the local build's tests take a minute at most together (user, 2026-11-18)
@Tag("ci")
class FacetPortionsTest : SiteTestBase() {
    // The suggester of counts, a setting of the site's facets taken back after
    @AfterEach fun cheapest() = learntBy(null)

    @Autowired lateinit var plugins: Plugins
    @Autowired private lateinit var chain: FacetChain
    @Autowired private lateinit var vocabularies: FacetVocabularies
    @Autowired private lateinit var modelValues: WorkModelValues
    @Autowired private lateinit var passes: WorkPasses
    @Autowired lateinit var cards: ObjectProvider<CardSource>

    /** What a pass over every facet of the site left: of every facet its basis, its model, the values it gave. */
    private data class Left(
        val bases: Map<String, Long?>,
        val models: Map<String, List<Byte>?>,
        val values: Map<String, Map<Long, Set<String>>>,
    )

    /** Every facet of the site fitted anew and its chances kept, of the works read [most] at a time. */
    private fun pass(most: Int): Left {
        val store = stores.source("site")!!
        val facets = chain.facetsOf(store)
        val portioned = WorkPasses(stores, plugins, cards, most)
        val fitting = FacetFitting(FacetReading(portioned, chain, vocabularies), FacetScoring())
        val encoder = plugins.textEncoder()!!
        inBackground {
            tasks.run(
                Task(TaskKind.FIT_FIELD_MODELS, source = store.id),
                mapOf("asked" to 1),
                joinAlike = false,
            ) {
                // What a pass over the source reads once, as the background's pass reads it
                val once = SourcePassRead(TypePassRead(vocabularies, passes.charactersRead(store)), UserWord.of(store))
                facets.indices.forEach { k ->
                    val suggester = plugins.suggester(store.id, facets[k].key)
                    fitting.fit(store, facets, k, suggester, encoder, once, { false }, true)
                }
            }
        }
        // The values the models give every work, as the filling of the index makes them of the models kept
        val given = modelValues.of(store, store.items.keys().map { it.id })
        return Left(
            facets.associate { it.key to store.suggestions.suggester(it.key)?.basis },
            facets.associate { it.key to store.suggestions.scoring(it.key)?.bytes?.toList() },
            facets
                .filter { it.infer }
                .associate { f ->
                    f.key to given.mapNotNull { (id, of) -> of[f.key]?.let { id to it.toSet() } }.toMap()
                },
        )
    }

    @Test
    fun `the facets' pass gives the same values cut into portions as read whole`() {
        learntBy(CountingSuggester.ID)
        catalogue()
        val whole = pass(TaskPortions.MOST)
        val cut = pass(PORTION)
        assertTrue(whole.models.values.any { it != null }, "no facet fitted: ${whole.models.keys}")
        assertTrue(whole.values.values.any { it.isNotEmpty() }, "no value given: ${whole.values}")
        assertEquals(whole.bases, cut.bases)
        assertEquals(whole.models, cut.models)
        assertEquals(whole.values, cut.values)
    }

    private companion object {
        /** A few works a portion: the works of the catalogue in a score of portions. */
        const val PORTION = 3
    }
}

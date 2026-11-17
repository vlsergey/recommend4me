package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.SiteTestBase
import io.github.vlsergey.recommend4me.matrix.CardSource
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.settings.Settings
import io.github.vlsergey.recommend4me.workload.Work
import io.github.vlsergey.recommend4me.workload.Workload
import io.github.vlsergey.recommend4me.workload.inBackground
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource

/**
 * THE PASS OVER THE FACETS A PORTION AT A TIME (user, 2026-11-17: nothing holds more than ten thousand works): the
 * works of the site cut into portions of a few works give the facets the same value sides, the same models and the same
 * values given every work as read in one portion — of a suggester whose model is sums over every work
 * ([CountingSuggester]).
 */
@Import(CountingSuggester.Plugin::class)
@TestPropertySource(properties = ["recommend4me.data-dir=build/test-data/facet-portions"])
class FacetPortionsTest : SiteTestBase() {
    @Autowired lateinit var settings: Settings
    @Autowired lateinit var plugins: Plugins
    @Autowired lateinit var workload: Workload
    @Autowired lateinit var events: ApplicationEventPublisher
    @Autowired private lateinit var chain: FacetChain
    @Autowired private lateinit var vocabularies: FacetVocabularies
    @Autowired private lateinit var kept: KeptModelValues
    @Autowired private lateinit var passes: WorkPasses
    @Autowired lateinit var cards: ObjectProvider<CardSource>

    /** What a pass over every facet of the site left: of every facet its basis, its model, the values it gave. */
    private data class Left(
        val bases: Map<String, Long?>,
        val models: Map<String, List<Byte>?>,
        val values: Map<String, Map<String, Set<String>>>,
    )

    /** Every facet of the site fitted anew and its chances kept, of the works read [most] at a time. */
    private fun pass(most: Int): Left {
        val store = stores.source("site")!!
        val facets = chain.facetsOf(store)
        val portioned = WorkPasses(stores, plugins, cards, most)
        val fitting = FacetFitting(FacetReading(portioned, chain, vocabularies), FacetScoring(kept, events))
        val encoder = plugins.textEncoder()!!
        inBackground {
            workload.run(Work("portions", source = store.id), mapOf("asked" to 1), joinAlike = false) {
                facets.indices.forEach { k ->
                    val suggester = plugins.suggester(store.id, facets[k].key)
                    fitting.fit(store, facets, k, suggester, encoder, passes.charactersRead(store), { false }, true)
                }
            }
        }
        return Left(
            facets.associate { it.key to store.suggestions.suggester(it.key)?.basis },
            facets.associate { it.key to store.suggestions.scoring(it.key)?.bytes?.toList() },
            facets
                .filter { it.infer }
                .associate { f -> f.key to store.modelValues.of(f.key).mapValues { it.value.toSet() } },
        )
    }

    @Test
    fun `the facets' pass gives the same values cut into portions as read whole`() {
        stores.source("site")!!.let { store ->
            chain.facetsOf(store).forEach {
                settings.set(Plugins.suggesterSetting(store.id, it.key), CountingSuggester.ID)
            }
        }
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

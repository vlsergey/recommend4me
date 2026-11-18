package io.github.vlsergey.recommend4me.duplicate

import java.util.Random
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * THE PLAN OF A SEARCH LOSES NO PAIR KEPT: of every model of random weights, every pair of every two sites any model
 * gives the least chance kept or more has a feature the plan takes as it is, or titles at least as alike as the plan
 * asks of the others — and the plan asks it of some, or it plans nothing.
 */
class SearchPlanTest {

    /** A model of random weights, its calibration ordering the scores. */
    private fun model(random: Random, search: PairSearch): Logistic {
        fun w() = random.nextGaussian() * 2
        val weights = HashMap<String, Double>()
        // As a model of the answers is: far below nought of itself, a pair kept by layers alike
        weights[Logistic.CONSTANT] = -12.0 + w()
        listOf(
                Logistic.TITLE,
                Logistic.TITLE_WITHIN,
                Logistic.TITLE_HOLDING,
                Logistic.AUTHORS,
                Logistic.has(Logistic.AUTHORS),
                Logistic.AUTHORS_WITHIN,
                Logistic.AUTHORS_HOLDING,
                Logistic.DESCRIPTION,
                Logistic.has(Logistic.DESCRIPTION),
                Logistic.SIZE,
                Logistic.has(Logistic.SIZE),
                Logistic.SAME_AUTHOR,
            )
            .forEach { weights[it] = w() }
        search.families.forEach {
            weights[Logistic.facet(it)] = w()
            weights[Logistic.has(Logistic.facet(it))] = w()
        }
        for (a in search.sites) for (b in search.sites) weights[Logistic.sites(a.source, b.source)] = w()
        return Logistic(weights, Calibration(w(), 0.5 + random.nextDouble()))
    }

    @Test
    fun `every pair a model keeps has a feature taken or titles as alike as the plan asks`() {
        var asked = 0
        for (seed in 1..20) {
            val random = Random(seed.toLong())
            val raw = RandomSearches.search(random)
            val search = PairSearch(raw.sites, raw.families, raw.familyOf, raw.sizes, raw.answered, least = 0.2)
            val models = List(2) { model(random, search) }
            val plans = assertNotNull(SearchPlan.of(search, models))
            plans
                .filter { it.title != null }
                .forEach { plan ->
                    asked++
                    assertKept(search, models, plan, seed)
                }
        }
        assertTrue(asked > 0, "no plan asked anything of the titles")
    }

    /** Every pair of the [plan]'s two sites any of the [models] keeps has a feature taken or titles alike enough. */
    private fun assertKept(search: PairSearch, models: List<Logistic>, plan: SitesPlan, seed: Int) {
        val title = plan.title!!
        for (i in 0 until search.sites[plan.a].count) for (j in 0 until search.sites[plan.b].count) {
            val layers = search.layers(plan.a, i, plan.b, j)
            val kept = (plan.a != plan.b || i != j) && models.any { it.chance(layers) >= search.least }
            val taken =
                plan.taken.any { t ->
                    if (t.family < 0) layers.sameAuthor else (layers.facets[search.families[t.family]] ?: 0f) > 0f
                }
            assertTrue(
                !kept || taken || layers.title >= title - TITLE_DIGITS,
                "seed $seed, ${plan.a}×${plan.b}, $i–$j: kept, of no feature taken and titles ${layers.title} " +
                    "below $title",
            )
        }
    }

    @Test
    fun `a model before any answer, or no least chance, plans nothing`() {
        val raw = RandomSearches.search(Random(1))
        assertNull(SearchPlan.of(raw, RandomSearches.models()))
        assertNull(SearchPlan.of(raw, listOf(model(Random(2), raw))))
    }

    private companion object {
        /** The titles' cosine of the halves the works hold: within its last digits. */
        const val TITLE_DIGITS = 2e-3f
    }
}

package io.github.vlsergey.recommend4me.duplicate

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(SearchPlan::class.java)

/**
 * THE MOST EVERY LAYER OF A PAIR CAN ADD TO THE SCORE OF A MODEL ([Logistic.Laid], of two sites): of the title, of the
 * authors' names, of the description, of the size, of one author, of every facet — each at the best value it can take,
 * whatever the pair. What a pair cannot reach, the sum of them all tells.
 */
internal class Bound(private val m: Logistic.Laid) {
    /** The most the title adds at the cosine [c] of the two titles' trigrams, its two shares as they can be at it. */
    fun title(c: Double): Double = m.title * c + shares(m.titleWithin, m.titleHolding, c)

    /** The most the title adds of any cosine. */
    val title: Double = (0..STEPS).maxOf { title(it.toDouble() / STEPS) }

    /** The most the authors' names add: none when either has none. */
    val authors: Double =
        maxOf(
            0.0,
            m.authored +
                (0..STEPS).maxOf { c ->
                    (c.toDouble() / STEPS).let { m.authors * it + shares(m.authorsWithin, m.authorsHolding, it) }
                },
        )

    /** The most the description adds: none when either has none; its cosine anywhere from -1 to 1. */
    val description: Double = maxOf(0.0, m.described + abs(m.description))

    /** The most the size adds: none when they share no number; its likeness from nought to one. */
    val size: Double = maxOf(0.0, m.sized + maxOf(0.0, m.size))

    /** The most one author adds. */
    val author: Double = maxOf(0.0, m.author)

    /** The most of every facet: none when either has none, both having it with no value shared, or sharing values. */
    val facets: DoubleArray = DoubleArray(m.facet.size) { maxOf(0.0, m.faceted[it], m.faceted[it] + m.facet[it]) }

    /** The most of every facet of both having it and no value shared: what a pair sharing none of it can have of it. */
    val facetsUnshared: DoubleArray = DoubleArray(m.facet.size) { maxOf(0.0, m.faceted[it]) }

    /** The most a pair can score: every layer at its most. */
    val most: Double = m.constant + title + authors + description + size + author + facets.sum()

    private companion object {
        /** The cosines a layer's most is looked for at: a hundredth apart. */
        const val STEPS = 100

        /**
         * The most two shares of one string's trigrams in the other ([Lexical.within], [Lexical.holding]) add, weighed
         * [a] and [b], at the cosine [c]: of lengths of any ratio, either one or less, their product the cosine's
         * square or, clipped at one, less.
         */
        fun shares(a: Double, b: Double, c: Double): Double {
            val c2 = c * c
            var best = maxOf(a + maxOf(0.0, b) * c2, b + maxOf(0.0, a) * c2)
            // Both weights below nought: the most on the curve of their product, inside it
            if (a < 0 && b < 0 && c > 0) {
                val w = c * sqrt(b / a)
                if (w in c2..1.0) best = maxOf(best, a * w + b * c2 / w)
            }
            return best
        }
    }
}

/**
 * A FEATURE OF A PAIR TAKEN AS IT IS: of the pairs of two sites, those that have it — one author, a value of a facet
 * shared — read of the works that have it, not of every pair; [pairs] how many at most, [adds] the most it adds to a
 * model's score.
 */
internal class Taken(val name: String, val family: Int, val pairs: Long, val adds: Double)

/**
 * WHAT A PAIR OF THE [a]-TH SITE'S AND THE [b]-TH SITE'S WORKS NEEDS TO REACH THE LEAST CHANCE KEPT by some model: the
 * pairs that have a feature [taken] — few of them —, and of every other the cosine of their titles at least [title]; of
 * a lower one, no model can give the pair the least chance kept, the other layers at their most. [leastScore] and
 * [mostScore] by the models; [titleOf] the least cosine of every model, the plan's the lowest. A plan of no [title]:
 * every pair compared.
 */
internal class SitesPlan(
    val a: Int,
    val b: Int,
    val all: Long,
    val leastScore: DoubleArray,
    val mostScore: DoubleArray,
    val taken: List<Taken>,
    val left: List<Taken>,
    val titleOf: DoubleArray,
) {
    /** The least cosine of the titles of a pair of no feature taken; none: every pair compared. */
    val title: Double? = titleOf.minOrNull()?.takeIf { it > 0.0 }
}

/**
 * THE PLAN OF A SEARCH — WHAT A PAIR NEEDS TO REACH THE LEAST CHANCE KEPT, worked out of the models themselves at every
 * search (user, 2026-11-18: "the optimisation itself understands by which fields what is computed"; "never read once,
 * conclude something and fix an order in the code"): of every model the least score of a pair kept ([leastScore], of
 * its calibration and [PairSearch.least]); of every two sites the most every layer can add ([Bound]); the features few
 * pairs have taken as they are ([Taken]: one author, a value shared of a facet of few works a value), the rest at their
 * most; and of a pair of none of them, the least cosine of the titles any model can keep it at ([SitesPlan.title]).
 * Told to the log in full: how it is worked out is seen at every search.
 */
internal object SearchPlan {
    /** The plan of every two sites compared; none of a model before any answer or of no calibration that orders. */
    fun of(search: PairSearch, models: List<Logistic>): List<SitesPlan>? {
        if (search.least <= 0.0 || models.any { it.weights == null || it.calibration.slope <= 0.0 }) {
            log.info("the plan of the search: none — every pair compared (a model of no answer, or no least chance)")
            return null
        }
        val least = models.map { leastScore(it.calibration, search.least) }
        val laid = models.map { it.laid(search.families) }
        val counts = search.sites.map { Counts(it, search.familyOf.size) }
        models.forEachIndexed { k, m ->
            log.info(
                "the plan of the search: model {} calibrated {} {}, a pair kept of a score of {} at least",
                k,
                fmt(m.calibration.shift),
                fmt(m.calibration.slope),
                fmt(least[k]),
            )
        }
        val plans = ArrayList<SitesPlan>()
        for (a in search.sites.indices) for (b in search.sites.indices) {
            if (!search.compared(a, b)) continue
            val bounds = laid.map { Bound(it.between(search.sites[a].source, search.sites[b].source)) }
            plans += plan(search, a, b, bounds, least.toDoubleArray(), counts)
        }
        plans.forEach(::tell)
        return plans
    }

    /** The score of a model of the [calibration] at the chance [least]. */
    fun leastScore(calibration: Calibration, least: Double): Double =
        (ln(least / (1 - least)) - calibration.shift) / calibration.slope

    private fun plan(
        search: PairSearch,
        a: Int,
        b: Int,
        bounds: List<Bound>,
        least: DoubleArray,
        counts: List<Counts>,
    ): SitesPlan {
        val x = counts[a]
        val y = counts[b]
        val all = search.sites[a].count.toLong() * search.sites[b].count
        // Every feature a pair may have or not, with how many pairs have it and the most it adds by any model
        val features =
            listOf(Taken(ONE_AUTHOR, -1, x.kinPairs(search.sites[a], y), bounds.maxOf { it.author })) +
                search.families.indices.map { f ->
                    Taken(
                        search.families[f],
                        f,
                        x.sharedPairs(y, f, search.familyOf),
                        bounds.maxOf { it.facets[f] - it.facetsUnshared[f] },
                    )
                }
        val taken = features.filter { it.adds > 0.0 && it.pairs <= all * TAKEN_SHARE }
        val left = features - taken.toSet()
        val titleOf =
            DoubleArray(bounds.size) { k ->
                val bound = bounds[k]
                // A pair of no feature taken: those layers at their least — none of one author, a facet unshared
                val without =
                    bound.most -
                        bound.title -
                        taken.sumOf { t ->
                            if (t.family < 0) bound.author else bound.facets[t.family] - bound.facetsUnshared[t.family]
                        }
                leastTitle(bound, least[k] - without)
            }
        return SitesPlan(a, b, all, least, DoubleArray(bounds.size) { bounds[it].most }, taken, left, titleOf)
    }

    /**
     * The least cosine of the titles at which the title can add [needed] — of every lower one it adds less; nought when
     * it adds so much of any; above one when it never does.
     */
    private fun leastTitle(bound: Bound, needed: Double): Double {
        // A step lower than the first that reaches it: the title's most between two steps is not looked at
        val first = (0..STEPS).firstOrNull { bound.title(it.toDouble() / STEPS) >= needed }
        return when (first) {
            null -> NEVER
            0 -> 0.0
            else -> (first - 1).toDouble() / STEPS
        }
    }

    private fun tell(p: SitesPlan) {
        log.info(
            "the plan of the search, {}: {} pairs; scores least {} most {}; taken as they are {}; at their most {}; " +
                "of the others the titles' cosine at least {} (of the models {})",
            "${p.a}×${p.b}",
            p.all,
            p.leastScore.joinToString("/", transform = ::fmt),
            p.mostScore.joinToString("/", transform = ::fmt),
            p.taken.joinToString { "${it.name} ${it.pairs} pairs +${fmt(it.adds)}" }.ifEmpty { "none" },
            p.left.filter { it.adds > 0.0 }.joinToString { "${it.name} ${it.pairs} pairs +${fmt(it.adds)}" },
            p.title?.let(::fmt) ?: "none — every pair",
            p.titleOf.joinToString("/", transform = ::fmt),
        )
    }

    private fun fmt(d: Double) = "%.3f".format(d)

    /** The cosines the least title is looked for at: a hundredth apart. */
    private const val STEPS = 100

    /** A least cosine of the titles never reached: no pair of no feature taken is kept. */
    private const val NEVER = 2.0

    /**
     * The share of the pairs of two sites a feature taken as it is has at most: few, read of the works that have it.
     */
    private const val TAKEN_SHARE = 1e-3

    private const val ONE_AUTHOR = "one author"
}

/** How many works of a site have every value, and every author: the pairs of two sites sharing them counted of it. */
internal class Counts(private val site: SiteWorks, values: Int) {
    private val ofValue = IntArray(values).also { c -> site.values.forEach { own -> own.forEach { c[it]++ } } }
    private val ofAuthor =
        HashMap<Long, Int>().also { c ->
            site.authors.forEach { own -> own.distinct().forEach { c.merge(it, 1, Int::plus) } }
        }

    /** The pairs of this site's works and the [other]'s sharing a value of the [f]-th facet, at most. */
    fun sharedPairs(other: Counts, f: Int, familyOf: IntArray): Long {
        var sum = 0L
        for (v in ofValue.indices) if (familyOf[v] == f) sum += ofValue[v].toLong() * other.ofValue[v]
        return sum
    }

    /** The pairs of the [own] site's works and the [other]'s of one author, at most: of every work, its kin's works. */
    fun kinPairs(own: SiteWorks, other: Counts): Long =
        own.kin.sumOf { kin -> kin.sumOf { (other.ofAuthor[it] ?: 0).toLong() } }
}

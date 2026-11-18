package io.github.vlsergey.recommend4me.duplicate

import java.nio.file.Files
import java.nio.file.Path
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(GraphCheck::class.java)

/**
 * HOW MUCH OF A SEARCH OF EVERY PAIR THE GRAPH FINDS, told to the log at every search by it ([GraphSearch]) — of the
 * pairs a search of every pair kept (user, 2026-11-18: "compare not with the works found but with the old model's
 * results: run it, take a backup and compare with it — some duplicates are hard to find"): of the data folder's
 * [REFERENCE], a pair a line ("source item other-source other-item", tab-separated). Of every pair of it every model's
 * chance now; of those of the least chance kept or more, whether the graph kept the pair itself, or of one of its two
 * works a pair as likely, or lost it — and of the lost, what they are like beside what the graph kept. None without the
 * file.
 */
internal object GraphCheck {
    private enum class Outcome {
        BELOW,
        ANSWERED,
        SAME,
        AS_LIKELY,
        LOST,
    }

    private class Ref(val a: Int, val i: Int, val b: Int, val j: Int)

    /** Of one model, the pairs a search found and how likely the model finds a pair. */
    private class Model(search: PairSearch, private val model: Logistic, private val k: Int, found: List<FoundPair>) {
        private val search = search
        private val kept = found.groupBy { it.site to it.item }

        fun keptOf(a: Int, i: Int) = kept[a to i].orEmpty().firstOrNull { it.kinds and (1 shl k) != 0 }

        fun chance(a: Int, i: Int, b: Int, j: Int) = model.chance(search.layers(a, i, b, j))

        fun outcome(r: Ref): Outcome {
            val c = chance(r.a, r.i, r.b, r.j)
            val mine = keptOf(r.a, r.i)
            val theirs = keptOf(r.b, r.j)
            return when {
                answered(search, r) -> Outcome.ANSWERED
                c < search.least -> Outcome.BELOW
                mine.isPair(r.b, r.j) || theirs.isPair(r.a, r.i) -> Outcome.SAME
                mine.asLikely(r.a, r.i, c) || theirs.asLikely(r.b, r.j, c) -> Outcome.AS_LIKELY
                else -> Outcome.LOST
            }
        }

        private fun FoundPair?.isPair(b: Int, j: Int) = this != null && otherSite == b && other == j

        private fun FoundPair?.asLikely(a: Int, i: Int, c: Double) =
            this != null && chance(a, i, otherSite, other) >= c - CLOSE
    }

    fun tell(search: PairSearch, models: List<Logistic>, likeliest: Likeliest, found: List<FoundPair>) {
        models.forEachIndexed { k, model -> tellScores(search, model, k, likeliest, found) }
        val file = System.getProperty("recommend4me.data-dir")?.let { Path.of(it, REFERENCE) } ?: return
        if (!Files.exists(file)) return
        val started = System.currentTimeMillis()
        val refs = refs(search, file)
        models.indices.forEach { k ->
            val model = Model(search, models[k], k, found)
            val outcomes = refs.parallelStream().map(model::outcome).toList()
            val counts = outcomes.groupingBy { it }.eachCount()
            val asked = outcomes.size - (counts[Outcome.BELOW] ?: 0) - (counts[Outcome.ANSWERED] ?: 0)
            log.info(
                "the graph against every pair's search, model {}: of its {} pairs, answered since {}, {} of the " +
                    "least chance kept or more now — kept the pair itself {}, of one of its works a pair as " +
                    "likely {}, lost {}; in {} ms",
                k,
                refs.size,
                counts[Outcome.ANSWERED] ?: 0,
                asked,
                counts[Outcome.SAME] ?: 0,
                counts[Outcome.AS_LIKELY] ?: 0,
                counts[Outcome.LOST] ?: 0,
                System.currentTimeMillis() - started,
            )
            refs.indices
                .filter { outcomes[it] == Outcome.LOST }
                .take(MOST_TOLD)
                .forEach { at ->
                    val r = refs[at]
                    log.info(
                        "the graph lost, model {}: {} of {} ; kept of it {}",
                        k,
                        told(search, r.a, r.i, r.b, r.j),
                        "%.3f".format(model.chance(r.a, r.i, r.b, r.j)),
                        model.keptOf(r.a, r.i)?.let {
                            "${told(search, r.a, r.i, it.otherSite, it.other)} of " +
                                "%.3f".format(model.chance(r.a, r.i, it.otherSite, it.other))
                        } ?: "none",
                    )
                }
        }
    }

    /**
     * Whether the card scored the pairs it kept as the processor scores them: of the [k]-th model, how many of its
     * pairs' scores differ by more than the products of half precision move them, and the first of them.
     */
    private fun tellScores(search: PairSearch, model: Logistic, k: Int, likeliest: Likeliest, found: List<FoundPair>) {
        val kept = found.filter { it.kinds and (1 shl k) != 0 }
        val apart =
            kept
                .parallelStream()
                .filter { p ->
                    val own = model.score(search.layers(p.site, p.item, p.otherSite, p.other))
                    kotlin.math.abs(own - likeliest.score(p.site, p.item, k)) > APART
                }
                .toList()
        log.info(
            "the card's scores of model {}: of {} pairs kept, {} apart from the processor's",
            k,
            kept.size,
            apart.size,
        )
        apart.take(MOST_TOLD).forEach { p ->
            log.info(
                "the card's score apart, model {}: {} — the card {}, the processor {}",
                k,
                told(search, p.site, p.item, p.otherSite, p.other),
                "%.3f".format(likeliest.score(p.site, p.item, k)),
                "%.3f".format(model.score(search.layers(p.site, p.item, p.otherSite, p.other))),
            )
        }
    }

    /** The pairs of the [file] of works the search has, of sites it compares. */
    private fun refs(search: PairSearch, file: Path): List<Ref> {
        val bySite =
            search.sites.map { s ->
                HashMap<String, Int>(s.count * 2).also { m -> s.ids.forEachIndexed { k, id -> m[id] = k } }
            }
        val siteOf = search.sites.withIndex().associate { (a, s) -> s.source to a }
        return Files.readAllLines(file).mapNotNull { line ->
            val f = line.split('\t')
            val a = siteOf[f.getOrNull(0)]
            val b = siteOf[f.getOrNull(2)]
            val i = a?.let { bySite[it][f[1]] }
            val j = b?.let { bySite[it][f[3]] }
            if (i != null && j != null && search.compared(a!!, b!!)) Ref(a, i, b, j) else null
        }
    }

    /** Whether the user answered on the pair since: a pair no search offers again. */
    private fun answered(search: PairSearch, r: Ref) =
        search.answered[r.a to r.b]?.get(r.i)?.contains(r.j) == true ||
            search.answered[r.b to r.a]?.get(r.j)?.contains(r.i) == true

    /** A pair and its layers, as the log tells it. */
    private fun told(search: PairSearch, a: Int, i: Int, b: Int, j: Int): String {
        val l = search.layers(a, i, b, j)
        val x = search.sites[a]
        val y = search.sites[b]
        return "${x.source}/${x.ids[i]}–${y.source}/${y.ids[j]}" +
            ": title %.2f (%.2f/%.2f)".format(l.title, l.titleWithin, l.titleHolding) +
            ", authors ${l.authors?.let { "%.2f".format(it.alike) } ?: "none"}, one author ${l.sameAuthor}" +
            ", description ${l.description?.let { "%.2f".format(it) } ?: "none"}" +
            ", size ${l.size?.let { "%.2f".format(it) } ?: "none"}" +
            ", facets ${l.facets.entries.joinToString { "${it.key} %.2f".format(it.value) }}"
    }

    /** The pairs of a search of every pair, of the data folder. */
    private const val REFERENCE = "duplicates-reference.tsv"

    /** The pairs lost told of every model at most. */
    private const val MOST_TOLD = 20

    /** The chances of the card: of products in half precision. */
    private const val CLOSE = 5e-3

    /** The scores of the card, of products in half precision, as far apart from the processor's as they may be. */
    private const val APART = 0.05
}

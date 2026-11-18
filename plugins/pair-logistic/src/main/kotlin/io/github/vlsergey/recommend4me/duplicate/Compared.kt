package io.github.vlsergey.recommend4me.duplicate

import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(Compared::class.java)

/**
 * HOW MUCH OF A SEARCH IS DONE: the pairs compared of every pair of the sites compared ([total]), told to [progress] as
 * a share as they are compared ([add]), and to the log every [EVERY_MILLIS] (user, 2026-11-18: "is there a report of
 * the progress in per cents? it should be made").
 */
internal class Compared(search: PairSearch, private val progress: (Double) -> Unit) {
    private val total: Double =
        search.sites.indices
            .sumOf { a ->
                search.sites.indices
                    .filter { search.compared(a, it) }
                    .sumOf { b ->
                        search.sites[a].count.toDouble() * search.sites[b].count
                    }
            }
            .coerceAtLeast(1.0)
    private val started = System.currentTimeMillis()
    private var done = 0.0
    private var told = started

    /** The time of every step on the card, by [Phase]: what a search's time goes to. */
    private val nanos = LongArray(Phase.entries.size)

    /** The [step] of the [phase] on the [card], timed to its end there. */
    fun <T> timed(phase: Phase, card: io.github.vlsergey.recommend4me.matrix.Cublas, step: () -> T): T {
        val began = System.nanoTime()
        val out = step()
        card.synchronize()
        nanos[phase.ordinal] += System.nanoTime() - began
        return out
    }

    /** The time of every step so far, in ms, by its name. */
    private fun steps(): String =
        Phase.entries.joinToString(", ") { "${it.name.lowercase()} ${nanos[it.ordinal] / NANOS_IN_MILLI} ms" }

    /** The [pairs] more compared. */
    fun add(pairs: Long) {
        done += pairs
        val share = (done / total).coerceAtMost(1.0)
        progress(share)
        val now = System.currentTimeMillis()
        if (now - told >= EVERY_MILLIS) {
            told = now
            log.info(
                "pairs searched: {} % of {} in {} s ({})",
                "%.1f".format(share * PER_CENT),
                total.toLong(),
                (now - started) / MILLIS_IN_SECOND,
                steps(),
            )
        }
    }

    /** At its end, the time of every step told. */
    fun told() =
        log.info("pairs searched in {} s: {}", (System.currentTimeMillis() - started) / MILLIS_IN_SECOND, steps())

    private companion object {
        const val NANOS_IN_MILLI = 1_000_000L
        const val EVERY_MILLIS = 30_000L
        const val PER_CENT = 100
        const val MILLIS_IN_SECOND = 1000
    }
}

/**
 * The steps of a block of rows on the card: the products of the vectors (cuBLAS), what the rare values the pairs share
 * add to every model's score (its kernel), every model's likeliest pair chosen — of the layers made of them and of the
 * frequent values of the two works' lists — and brought back (its kernel).
 */
internal enum class Phase {
    PRODUCTS,
    SHARED,
    CHOSEN,
}

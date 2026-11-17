package io.github.vlsergey.recommend4me.suggestion

/**
 * WHERE THE TIME OF A FITTING WENT ([Descent]), summed over its steps: the descent's own work (the moves of the
 * points); the two products of the items' features with what they send and take back; the pairs and the names; the rest
 * — the values' products, the penalty. Told in the log of the fitting.
 */
internal class FitTiming {
    var rounds = 0
    var moves = 0L
    var features = 0L
    var pairs = 0L
    var rest = 0L

    /** As the log tells it, in milliseconds. */
    fun told(): String =
        "%d steps: the moves %d ms, the items' features %d ms, the pairs and names %d ms, the rest %d ms"
            .format(
                rounds,
                moves / NANOS_IN_MILLI,
                features / NANOS_IN_MILLI,
                pairs / NANOS_IN_MILLI,
                rest / NANOS_IN_MILLI,
            )

    private companion object {
        /** The nanoseconds of a millisecond: the log tells the times in milliseconds. */
        const val NANOS_IN_MILLI = 1_000_000L
    }
}

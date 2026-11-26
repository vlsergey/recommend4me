package io.github.vlsergey.recommend4me.suggestion

/** A setting of naive Bayes: the smoothing [alpha] and the [scale] of the other values' sum. */
internal class BayesSetting(val alpha: Double, val scale: Double)

/**
 * EVERY SETTING TRIED, IN ONE WALK OF THE PORTIONS ([walk]), by the counts of that walk ([counted]): of every work with
 * a pair, its chances by the counts of the other folds under every setting — the held-out loss of each ([held]) — and
 * of every work the user answered on, the chance of every value answered by the counts of every other work under every
 * setting ([heldOut]): what the setting chosen hands over.
 */
internal class BayesTrials(private val counted: BayesFolds, val settings: List<BayesSetting>) {
    val held = List(settings.size) { HeldLoss() }

    private val nanos = LongArray(settings.size)

    /**
     * Of every work answered on, its id, the values answered, and their scores of every setting, setting after setting.
     */
    private val answeredIds = ArrayList<Long>()
    private val answeredValues = ArrayList<IntArray>()
    private val answeredScores = ArrayList<FloatArray>()

    /** The [portions] walked once, [stop] asked before every portion. */
    fun walk(portions: TaskPortions, stop: () -> Boolean) {
        portions.forEach { portion ->
            stopIf(stop)
            val pairs = SuggestionPairs(portion)
            val works = BayesWork.of(portion, pairs)
            for (i in 0 until portion.itemCount) {
                if (pairs.count[i] > 0) judge(pairs, i, works[i])
                val asked = portion.confirmed[i] + portion.rejected[i]
                if (asked.isNotEmpty()) answered(portion.itemIds[i], asked, works[i])
            }
        }
    }

    /** The loss of every setting of the pairs of the work [i] of [pairs], held out with its fold. */
    private fun judge(pairs: SuggestionPairs, i: Int, work: BayesWork) {
        val fold = counted.folds[pairs.foldOf[i]]
        settings.forEachIndexed { s, setting ->
            val at = System.nanoTime()
            val chances = counted.total.chances(work.contexts, null, setting.alpha, setting.scale, fold)
            pairs.forEach(i) { v, w, y -> held[s].add(w.toDouble(), SuggestionPairs.logLoss(chances[v].toDouble(), y)) }
            nanos[s] += System.nanoTime() - at
        }
    }

    /**
     * LEAVE ONE OUT: of every value [asked] of the work [id], its chance by the counts of every work with the work's
     * own pairs taken out — as the model kept scores it — under every setting.
     */
    private fun answered(id: Long, asked: IntArray, work: BayesWork) {
        val scores = FloatArray(settings.size * asked.size)
        settings.forEachIndexed { s, setting ->
            val chances = counted.total.chances(work.contexts, work, setting.alpha, setting.scale)
            asked.forEachIndexed { k, v -> scores[s * asked.size + k] = chances[v] }
        }
        answeredIds += id
        answeredValues += asked
        answeredScores += scores
    }

    /** How long the setting [s] was judged, in milliseconds. */
    fun millis(s: Int): Long = nanos[s] / NANOS_IN_MILLI

    /** Of every value the user answered on of every work — confirmed, then rejected — its score by the setting [s]. */
    fun heldOut(s: Int): HeldOutScores {
        val items = ArrayList<Long>()
        val values = ArrayList<Int>()
        val scores = ArrayList<Float>()
        answeredIds.forEachIndexed { a, id ->
            val asked = answeredValues[a]
            asked.forEachIndexed { k, v ->
                items += id
                values += v
                scores += answeredScores[a][s * asked.size + k]
            }
        }
        return HeldOutScores(items.toLongArray(), values.toIntArray(), scores.toFloatArray())
    }

    private companion object {
        const val NANOS_IN_MILLI = 1_000_000L
    }
}

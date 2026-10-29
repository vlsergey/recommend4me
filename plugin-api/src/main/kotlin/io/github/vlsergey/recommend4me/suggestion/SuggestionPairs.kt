package io.github.vlsergey.recommend4me.suggestion

import java.util.IdentityHashMap
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * THE PAIRS EVERY SUGGESTER IS JUDGED BY, ALIKE — so that the held-out losses of two methods
 * compare on the page of the models. Of every item, the values whose pair with it weighs anything,
 * in ascending order, with the weight and the label of each: when the item has any value, every
 * value it may have ([SuggestionTask.allowed], but the [SuggestionTask.barred]) weighs
 * [SuggestionTask.lackWeight] as one it lacks; one the user rejected weighs 1, labelled 0; one it
 * has weighs 1, labelled 1 — a draft the user has not confirmed [SuggestionTask.draftWeight]. The
 * items are dealt into [FOLDS] folds by their ids, the same way every time: a work one method holds
 * out, every other holds out too.
 *
 * THE PAIRS OF A VALUE MERELY LACKED ARE NOT KEPT: all alike — the lacking weight, the label
 * nought — they are the values the item may have ([may], one array for every item of the same), and
 * what an item has of its own is kept beside them ([own]). Kept one by one, the 75 000 works of
 * fanfics.me, of a universe of 4 400 characters each, were 330 million pairs — gigabytes of the same
 * two numbers. Read by [forEach], merged as they are read.
 */
class SuggestionPairs(task: SuggestionTask) {
    val n = task.itemCount
    val m = task.valueCount

    /** The fold of every item. */
    val foldOf = IntArray(n) { foldOf(task.itemIds[it]) }

    /**
     * Of every item, the weight of a value it may have and merely lacks: [SuggestionTask.lackWeight]
     * when it has any value; nought when it has none — such an item is unknown, not denied.
     */
    val lack = FloatArray(n)

    /** Of every item, the values it may have, ascending — one array for every item of the same; null: every value. */
    val may: Array<IntArray?>

    /**
     * Of every item, the values whose pair is not one merely lacked, ascending — one it has, one the
     * user rejected, a draft, one barred or out of [may] — with their weights and labels; a weight
     * nought: no pair.
     */
    val own: Array<IntArray>
    val ownWeight: Array<FloatArray>
    val ownLabel: Array<FloatArray>

    /** Of every item, how many pairs it has, and their weights summed. */
    val count = IntArray(n)
    val rowWeight = FloatArray(n)

    /** The most pairs an item has. */
    val longest: Int

    init {
        // Every array of the allowed made ascending once, however many items share it
        val ascending = IdentityHashMap<IntArray, IntArray>()
        may = Array(n) { i -> task.allowed[i]?.let { a -> ascending.getOrPut(a) { a.filter { it in 0 until m }.distinct().sorted().toIntArray() } } }
        val lackWeight = task.lackWeight.toFloat()
        val draftWeight = task.draftWeight.toFloat()
        val ownOf = arrayOfNulls<IntArray>(n)
        val weightOf = arrayOfNulls<FloatArray>(n)
        val labelOf = arrayOfNulls<FloatArray>(n)
        for (i in 0 until n) {
            val has = task.assigned[i]
            val lacking = if (has.isNotEmpty()) lackWeight else 0f
            lack[i] = lacking
            val allowed = may[i]
            fun base(v: Int) = if (lacking > 0f && (allowed == null || allowed.binarySearch(v) >= 0)) lacking else 0f
            // The item's own word over the lacking: barred, rejected, had — a draft not confirmed weighing less
            val weight = HashMap<Int, Float>()
            val labelled = HashSet<Int>()
            task.barred[i].forEach { if (it in 0 until m) weight[it] = 0f }
            task.rejected[i].forEach { weight[it] = 1f }
            has.forEach { weight[it] = 1f; labelled += it }
            val drafted = task.drafts[i]
            if (drafted.isNotEmpty()) {
                val confirmed = task.confirmed[i].toHashSet()
                drafted.forEach { if (it in labelled && it !in confirmed) weight[it] = draftWeight }
            }
            val values = weight.keys.filter { v -> v in labelled || weight.getValue(v) != base(v) }.sorted()
            ownOf[i] = values.toIntArray()
            weightOf[i] = FloatArray(values.size) { weight.getValue(values[it]) }
            labelOf[i] = FloatArray(values.size) { if (values[it] in labelled) 1f else 0f }
            val lacked = if (lacking > 0f) allowed?.size ?: m else 0
            var pairs = lacked
            var sum = lacked * lacking
            values.forEach { v ->
                val w = weight.getValue(v)
                val b = base(v)
                if (w != 0f && b == 0f) pairs++
                if (w == 0f && b != 0f) pairs--
                sum += w - b
            }
            count[i] = pairs
            rowWeight[i] = sum
        }
        own = Array(n) { ownOf[it]!! }
        ownWeight = Array(n) { weightOf[it]!! }
        ownLabel = Array(n) { labelOf[it]!! }
        longest = count.maxOrNull() ?: 0
    }

    /** The weight of the value [v] of the item [i] were it merely lacked: [lack] when the item may have it, else nought. */
    fun lacking(i: Int, v: Int): Float {
        val lacking = lack[i]
        if (lacking == 0f) return 0f
        val allowed = may[i]
        return if (allowed == null || allowed.binarySearch(v) >= 0) lacking else 0f
    }

    /** Every pair of the item [i], its values ascending: [action] of its value, weight and label. */
    inline fun forEach(i: Int, action: (value: Int, weight: Float, label: Float) -> Unit) {
        val values = own[i]
        val weights = ownWeight[i]
        val labels = ownLabel[i]
        var k = 0
        val lacking = lack[i]
        if (lacking > 0f) {
            val allowed = may[i]
            val size = allowed?.size ?: m
            for (t in 0 until size) {
                val v = allowed?.get(t) ?: t
                while (k < values.size && values[k] < v) {
                    if (weights[k] != 0f) action(values[k], weights[k], labels[k])
                    k++
                }
                if (k < values.size && values[k] == v) {
                    if (weights[k] != 0f) action(v, weights[k], labels[k])
                    k++
                } else action(v, lacking, 0f)
            }
        }
        while (k < values.size) {
            if (weights[k] != 0f) action(values[k], weights[k], labels[k])
            k++
        }
    }

    /** The pairs of the item [i] written into [values], [weights] and [labels], as long as [longest]: how many. */
    fun expand(i: Int, values: IntArray, weights: FloatArray, labels: FloatArray): Int {
        var at = 0
        forEach(i) { v, w, y ->
            values[at] = v
            weights[at] = w
            labels[at] = y
            at++
        }
        return at
    }

    /**
     * The held-out loss of a method: of every fold, the chances [chances] gives the items of the fold
     * by what it learnt of the others — of each item its chance of every value of its pairs — their
     * logistic losses weighed and summed.
     */
    fun heldOut(chances: (fold: Int, items: IntArray) -> List<FloatArray>): HeldLoss {
        val held = HeldLoss()
        for (fold in 0 until FOLDS) {
            val items = (0 until n).filter { foldOf[it] == fold && count[it] > 0 }.toIntArray()
            if (items.isEmpty()) continue
            val chancesOf = chances(fold, items)
            items.forEachIndexed { k, i ->
                val c = chancesOf[k]
                forEach(i) { v, w, y -> held.add(w.toDouble(), logLoss(c[v].toDouble(), y)) }
            }
        }
        return held
    }

    companion object {
        /** How many folds the items are dealt into. */
        const val FOLDS = 5

        /** The fold of an item by its id: the same every time, whatever else the task holds. */
        fun foldOf(id: String, folds: Int = FOLDS): Int = (id.hashCode() and Int.MAX_VALUE) % folds

        /** A chance kept off 0 and 1: a certainty proved wrong would weigh without bound. */
        private const val EDGE = 1e-6

        /** The logistic loss of a chance [p] of a pair labelled [y]. */
        fun logLoss(p: Double, y: Float): Double {
            val q = p.coerceIn(EDGE, 1 - EDGE)
            return -(y * ln(q) + (1 - y) * ln(1 - q))
        }
    }
}

/** The weighted held-out losses of pairs, summed: Σw, Σw·l, Σw·l², Σw² — the mean and its standard error. */
class HeldLoss {
    private var weight = 0.0
    private var loss = 0.0
    private var squares = 0.0
    private var weights = 0.0

    val mean: Double get() = if (weight > 0) loss / weight else Double.MAX_VALUE

    fun add(w: Double, l: Double) {
        weight += w
        loss += w * l
        squares += w * l * l
        weights += w * w
    }

    /** The standard error of [mean]: the weighted spread over the effective number of pairs. */
    val error: Double get() {
        if (weight <= 0 || weights <= 0) return Double.MAX_VALUE
        val variance = (squares / weight - mean * mean).coerceAtLeast(0.0)
        return sqrt(variance / (weight * weight / weights))
    }
}

/**
 * The one-standard-error rule (Breiman et al.): of the settings that hold out no worse than the best
 * by more than its standard error, the one [simpler] puts first — the data cannot tell them apart,
 * and the simpler guesses less where an item is unlike those it learnt from.
 */
fun <T> oneStandardError(tried: List<T>, held: (T) -> HeldLoss, simpler: Comparator<T>): T {
    val least = tried.minBy { held(it).mean }
    val bound = held(least).mean + held(least).error
    return tried.filter { held(it).mean <= bound }.sortedWith(simpler).first()
}

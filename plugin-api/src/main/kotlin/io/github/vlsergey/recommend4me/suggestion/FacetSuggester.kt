package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.matrix.Cublas
import io.github.vlsergey.recommend4me.matrix.HalfMatrix
import io.github.vlsergey.recommend4me.matrix.Matrix
import java.util.Collections
import java.util.IdentityHashMap
import java.util.stream.IntStream

/**
 * One input of the items, as the model of the grades reads them too: a vector per item, as it was encoded — a text (the
 * title, the description, the notes, a line of the site), a picture, a set of vectors made one (the reviews, the
 * windows of the chapters, the screenshots). [name]: stable, the weights learnt of the input are kept by it.
 *
 * OF THE ITEMS THAT HAVE IT ALONE, LAID OUT AS THE PRODUCTS READ THEM: [vectors] a row of every such item, [rows] the
 * item (a row of the task) each is of — every item once, in no order. An item without the input has no row: of the 75
 * 630 works of fanfics.me the notes are of 46, the cover of 10 217, and a row of zeros of every other was multiplied at
 * every product. IN HALF PRECISION, as the database keeps the texts' and the pictures' vectors: the 0.9 GB of
 * fanfics.me's single precision 0.46. [onCard]: the same rows on the card, put there by the application for the
 * operation the task is of and freed at its end; null without a card or of an operation of a few works.
 */
class InputBlock(val name: String, val vectors: HalfMatrix, val rows: IntArray, val onCard: CardRows? = null) {
    init {
        require(rows.size == vectors.rows) { "$name: ${vectors.rows} vectors of ${rows.size} items" }
        require(onCard == null || onCard.count == rows.size && onCard.dim == vectors.cols) {
            "$name: other rows on the card"
        }
    }

    val dim: Int
        get() = vectors.cols

    /**
     * Of every item of the [itemCount], its row of [vectors]; -1 of an item without the input. Made at every asking.
     */
    fun slots(itemCount: Int): IntArray =
        IntArray(itemCount) { -1 }.also { at -> rows.forEachIndexed { k, i -> at[i] = k } }

    /** The rows on the card while they are there: null once the operation that put them there freed them. */
    val card: CardRows?
        get() = onCard?.takeIf { it.open }

    companion object {
        /** A block of every item of [vectors] whose row is [present], the rest left out, in half precision. */
        fun of(name: String, vectors: Matrix, present: BooleanArray): InputBlock {
            val rows = present.indices.filter { present[it] }.toIntArray()
            return InputBlock(name, HalfMatrix.of(vectors, rows), rows)
        }
    }
}

/**
 * THE ROWS OF AN INPUT BLOCK ON THE CARD ([InputBlock.onCard]): its [count] vectors of [dim] numbers in half precision,
 * row after row, and the items they are of ([items], ints), on the card of [cublas]. Freed by the application with the
 * operation that put them there; [open] says whether they are there still. What a plugin made on the card of them
 * ([alongside]) lives no longer: freed with them.
 */
class CardRows(
    val cublas: Cublas,
    val vectors: Cublas.DeviceBytes,
    val items: Cublas.DeviceBytes,
    val count: Int,
    val dim: Int,
) : AutoCloseable {
    @Volatile
    var open: Boolean = true
        private set

    private val after = ArrayList<AutoCloseable>()

    /** [made] of these rows, freed with them if not before — at once if they are freed already. */
    @Synchronized
    fun alongside(made: AutoCloseable) {
        if (open) after += made else made.close()
    }

    @Synchronized
    override fun close() {
        if (!open) return
        open = false
        after.forEach { it.close() }
        after.clear()
        vectors.close()
        items.close()
    }

    companion object {
        /**
         * The rows [vectors] of the [items] put on the [card]; null when it has no room for them. Under the card's
         * lock.
         */
        fun put(card: Cublas, vectors: HalfMatrix, items: IntArray): CardRows? {
            val halves = card.allocateBytes(vectors.rows.toLong() * vectors.cols * 2) ?: return null
            val ints = card.allocateBytes(items.size.toLong() * Int.SIZE_BYTES) ?: null.also { halves.close() }
            return ints?.let {
                halves.upload(vectors, 0, vectors.rows)
                it.upload(items)
                CardRows(card, halves, it, items.size, vectors.cols)
            }
        }
    }
}

/**
 * A text that names a value, learnt from as a work would be: one of the names of a character, of a universe (its own,
 * its characters'), of a pairing — "Гермиона", "Hermione Granger" — read as a text of a work, the work's every text,
 * which names the [value] and none of the other values [among] (the other characters of its universe). The works the
 * model learns from are few; the names of the values tell it how a text that names one looks. The name encoded as a
 * text of a work is, is its row of the task's [SuggestionTask.hintTexts].
 *
 * [among]: the values the name is judged among, its own with them, in ascending order — one array for every name judged
 * among the same values: the names of a universe's thousands of characters share one, not thousands of copies of it.
 */
class NameHint(val value: Int, val among: IntArray)

/**
 * A way the values of a facet fall into groups alike in what goes with them — the pairings of two men, of two women, of
 * a man and a woman; the characters of either sex; the pairings of one character. What is learnt of a group holds for
 * every value in it, a value never seen with a work's tags too. [groups]: the groups by their keys — a group is the
 * same group in every task by its key, whatever other groups a task has. [of]: the groups of every value of the task,
 * indices of [groups] — none for a value of no known group, several for a value of several: a pairing is in the groups
 * of both its characters.
 */
class ValueGrouping(val name: String, val groups: List<String>, val of: List<IntArray>)

/**
 * Everything a suggester knows of one facet of one source
 * ([FacetDef.suggest][io.github.vlsergey.recommend4me.source.FacetDef.suggest],
 * [FacetDef.infer][io.github.vlsergey.recommend4me.source.FacetDef.infer]): every item — every input of it, the facet's
 * values it has, the values of its other facets — and every value that may be suggested, with the vector of its name.
 *
 * Rows of the inputs and of the lists are items, rows of [values] values; [assigned], [confirmed] and [rejected] hold
 * indices of [values] ([valueKeys]), [context] indices of [contextKeys].
 */
class SuggestionTask(
    /**
     * The id of every item, in the order of the rows: an item is held out of what is learnt by it, the same way every
     * time.
     */
    val itemIds: List<String>,
    val inputs: List<InputBlock>,
    /** One row per value: the vector of its name as a query, of unit length; zeros when not encoded. */
    val values: Matrix,
    /** The key of every value, in the order of [values]: what is learnt of a value is kept by it. */
    val valueKeys: List<String>,
    /** The values each item has now: the site's, with the user's corrections applied. */
    val assigned: List<IntArray>,
    /** The values the user said the item has: added by hand or accepted when suggested. */
    val confirmed: List<IntArray>,
    /** The values the user said the item does not have: taken from it, or rejected when suggested. */
    val rejected: List<IntArray>,
    /** The values of the item's other facets — its fandom, its tags, its author: indices of [contextKeys]. */
    val context: List<IntArray>,
    /** Every value of the other facets, as "<facet>\u0001<value>": what is learnt of one is kept by it. */
    val contextKeys: List<String>,
    /**
     * The values each item may have at all — a work's characters are of its universes — null for an item that may have
     * any. A value outside them is no example of the item: not one it lacks, one it cannot have; its chance is still
     * worked out. ONE ARRAY FOR EVERY ITEM OF THE SAME — the works of a universe its thousands of characters: what an
     * item alone may not have of them is told by [barred].
     */
    val allowed: List<IntArray?> = List(assigned.size) { null },
    /** The groupings of the values, none for a facet whose values are not known to fall into groups. */
    val groupings: List<ValueGrouping> = emptyList(),
    /**
     * How much a value an item lacks counts as one it does not have, against 1 for one it has and one the user
     * rejected: 1 when an item's values are all it has; less when they are what a site happened to write — a tag the
     * site has raises the chance of the work's tag, its absence says little of the work.
     */
    val lackWeight: Double = 1.0,
    /**
     * The values the site gives each item as the facet's examples, from a facet of its own
     * ([FacetDef.examplesFrom][io.github.vlsergey.recommend4me.source.FacetDef.examplesFrom]) — among the [assigned]
     * too — and those its line of characters and pairings names
     * ([CastRead.named][io.github.vlsergey.recommend4me.cast.CastRead.named]), which are not. Of the item's own word
     * this alone is a witness of its values: a value the site gives the work is likelier the work's, however rare;
     * empty for a facet without.
     */
    val examples: List<IntArray> = List(assigned.size) { IntArray(0) },
    /**
     * The names of the values as texts that name them ([NameHint]); none for a facet whose values are not named in
     * texts.
     */
    val hints: List<NameHint> = emptyList(),
    /**
     * One row per name of [hints], in their order: the name encoded as a text of a work is, of the length of the task's
     * text inputs. A matrix, not a vector of each: thousands of names of thousands of numbers, read into the memory a
     * suggester reads them from.
     */
    val hintTexts: Matrix = Matrix(0, 0),
    /**
     * The values the application drafted of each item from the site's tags through the dictionary
     * ([LayerDraft][io.github.vlsergey.recommend4me.universe.LayerDraft]) — among the [assigned] unless the user took
     * them away. Likely, but no one's word: one the user has not [confirmed] weighs [draftWeight] in what is learnt,
     * not 1.
     */
    val drafts: List<IntArray> = List(assigned.size) { IntArray(0) },
    /** How much a drafted value the user has not confirmed weighs, against 1 of a value the site or the user gives. */
    val draftWeight: Double = 1.0,
    /**
     * Of each item, the values of its [allowed] it may not have after all — of a work's secondary characters, its main
     * ones: as outside them.
     */
    val barred: List<IntArray> = List(assigned.size) { IntArray(0) },
) {
    val itemCount: Int
        get() = assigned.size

    val valueCount: Int
        get() = values.rows

    val contextCount: Int
        get() = contextKeys.size

    init {
        require(itemIds.size == itemCount) { "${itemIds.size} ids of $itemCount items" }
        require(inputs.all { b -> b.rows.all { it in 0 until itemCount } }) { "Inputs of items not of the $itemCount" }
        require(inputs.map { it.name }.toSet().size == inputs.size) { "Inputs named twice: ${inputs.map { it.name }}" }
        require(valueKeys.size == valueCount) { "${valueKeys.size} keys of $valueCount values" }
        require(context.all { c -> c.all { it in 0 until contextCount } }) { "A context out of the $contextCount keys" }
        require(
            confirmed.size == itemCount &&
                rejected.size == itemCount &&
                context.size == itemCount &&
                allowed.size == itemCount
        ) {
            "$itemCount assigned, ${confirmed.size} confirmed, ${rejected.size} rejected, " +
                "${context.size} contexts, ${allowed.size} allowed"
        }
        require(
            groupings.all { g ->
                g.of.size == valueCount && g.of.all { groups -> groups.all { it in g.groups.indices } }
            }
        ) {
            "A grouping not of the $valueCount values"
        }
        require(lackWeight > 0 && lackWeight <= 1) { "A lacking value weighs $lackWeight" }
        require(examples.size == itemCount) { "$itemCount items, ${examples.size} lists of examples" }
        require(drafts.size == itemCount) { "$itemCount items, ${drafts.size} lists of drafts" }
        require(barred.size == itemCount) { "$itemCount items, ${barred.size} lists of the barred" }
        require(draftWeight > 0 && draftWeight <= 1) { "A drafted value weighs $draftWeight" }
        require(hintTexts.rows == hints.size) { "${hints.size} names, ${hintTexts.rows} texts of them" }
        // Every array of values checked once, however many names share it
        val among = hints.mapTo(Collections.newSetFromMap(IdentityHashMap())) { it.among }
        require(among.all { a -> a.all { it in 0 until valueCount } && (1 until a.size).all { a[it - 1] < a[it] } }) {
            "Names judged among values not of the task, or not in order"
        }
        require(hints.all { h -> h.value in 0 until valueCount && h.among.binarySearch(h.value) >= 0 }) {
            "A name judged among values without its own"
        }
    }
}

/**
 * A way of working out the values of a facet an item should have from its texts and its other values. Learns from every
 * item of the source: the values the site and the user gave them are the examples. What the user said of a work is the
 * application's to apply over the chances.
 *
 * EVERY SUGGESTER KEEPS ITS MODEL, AND AN ITEM IS SCORED BY IT ALONE (user, 2026-11-12): a fitting ends with the model
 * ([FittedSuggester.model]), the application keeps it, and the one way to the chances of any item — of the works fitted
 * on, of a page of one work read later — is [scoring] of that model and of the items' own features. Nothing scores on
 * the task fitted on, nor reads the other works of the source: a suggester whose chances of a work are made of the
 * other works — their counts, their neighbours — keeps what it reads of them in its model.
 */
interface FacetSuggester {
    /** Stable: kept with the fitted state. */
    val id: String

    /**
     * Learns from every item of [task]; null when there is nothing to learn from — no item has a value, or every item
     * has all. [previous]: the state fitted last on the facet, unpacked against [task], to go on from — a few answers
     * or works more move the fitted state a little, and the fitting is a short walk from it rather than a search from
     * scratch; null: from scratch.
     *
     * [stop]: asked between the steps of the fitting; once it says yes the fitting is given up with a
     * [java.util.concurrent.CancellationException] — newer data came, and it is fitted on that.
     */
    fun fit(task: SuggestionTask, previous: FittedSuggester? = null, stop: () -> Boolean = { false }): FittedSuggester?

    /**
     * A state kept by [FittedSuggester.pack], to go on from in the next fitting, or to make its [FittedSuggester.model]
     * of the task fitted on; null when it does not fit tasks of [task]'s shape — it is fitted again.
     */
    fun unpack(bytes: ByteArray, task: SuggestionTask): FittedSuggester?

    /**
     * THE CHANCES OF THE ITEMS OF [task] BY THE [model] KEPT ([FittedSuggester.model]) — the one way to them: their
     * inputs, their context and examples are read, nothing else; its values are the values the model was made of, in
     * its order, without their names, groups or texts — the model knows what each is. An item the model was fitted on
     * is known by its id: it is not its own witness. Null when the bytes are not of this suggester's making, or not of
     * the task's values or inputs: the facet has no chances till it is fitted again.
     */
    fun scoring(model: ByteArray, task: SuggestionTask): SuggestionScores?

    /**
     * WHAT THE MODEL TELLS OF EVERY VALUE OVER THE WORKS AS A WHOLE, of the [model] kept ([FittedSuggester.model])
     * alone — no work read: a weight of each value, the larger the likelier a work is to have it, as the suggester's
     * mathematics tells it (a bias, a prior, a share) — the pairings ordered by it. In the order of the model's values;
     * null when the bytes are not of this suggester's making, or it tells none.
     */
    fun weights(model: ByteArray): DoubleArray? = null
}

interface FittedSuggester {
    /**
     * What the fitting that made it tried and chose, for the user to see on the page of the models; null of a state
     * unpacked — the report is the application's to keep, not packed with the state.
     */
    val report: FitReport?
        get() = null

    /**
     * THE MODEL THE ITEMS ARE SCORED BY ([FacetSuggester.scoring]), made of the [task] fitted on: what every value of
     * it is to the scoring — its coordinates, its bias, its counts — worked out once of the task's names, groups and
     * texts, with what carries an item's own features to it, and whatever of the task's works the scoring of another
     * work reads — their counts, their vectors and values as neighbours. Kept by the application: a page of one work
     * reads no dictionary, no names of every value and no other work.
     */
    fun model(task: SuggestionTask): ByteArray

    fun pack(): ByteArray
}

/**
 * What a fitting of a suggester tried and chose: every setting tried with the quality its folds held out — a loss, the
 * lower the better, with its standard error — the one chosen, and whether it was a search from scratch or a short walk
 * on from the state fitted last.
 */
class FitReport(
    /** The settings chosen, by their names as the suggester calls them ("r", "λ"). */
    val chosen: Map<String, String>,
    val loss: Double,
    val lossError: Double,
    val tried: List<FitTry>,
    val fromScratch: Boolean,
)

/** One setting tried: its values by name, the loss its folds held out with its standard error, how long it took. */
class FitTry(val settings: Map<String, String>, val loss: Double, val lossError: Double, val millis: Long)

fun interface SuggestionScores : AutoCloseable {
    /**
     * For each of [rows] (items of the task), the chance of every value — an array as long as the task has values. A
     * value the item has is judged as if the item did not say so: a low chance marks a value the site gives it that
     * does not fit.
     */
    fun of(rows: IntArray): List<FloatArray>

    /**
     * THE SCORES THE CHANCES ARE MADE OF, of each of [rows] of its values [columns] alone — in their order; null: every
     * value. A score orders a value among the others as its chance does — the higher, the likelier — and [chance] makes
     * of it the chance [of] gives. What reads a few values of thousands, or their order alone, reads these: a value an
     * item may not have is never scored, the chance of the others made of the few that are read. One array of [columns]
     * given to several rows is one set of values for them all: scored together.
     *
     * Of a suggester that tells no other: the chances themselves.
     */
    fun scores(rows: IntArray, columns: List<IntArray?>): List<FloatArray> =
        of(rows).mapIndexed { r, row -> columns[r]?.let { c -> FloatArray(c.size) { row[c[it]] } } ?: row }

    /**
     * [scores] HANDED OVER A ROW AT A TIME, for the reader of a few of each row: [each] is given the place of a row
     * among [rows] and its scores of its values [columns] (null: every value), in their order — `columns.size ==
     * rows.size`. THE ROW IS VALID DURING THE CALL ALONE: it may be a buffer the next row is made in, never to be kept;
     * the reader copies what it keeps. [each] may be called from several threads at once, every place of [rows] once,
     * in no order; a row of [rows] may be the same item as another. Of a facet of thousands of values, a row of every
     * one of them kept for every work of a pass is what a suggester making them in place saves.
     *
     * Of a suggester that tells no other: the rows of [scores], side by side on every core.
     */
    fun scores(rows: IntArray, columns: List<IntArray?>, each: (Int, FloatArray) -> Unit) {
        require(columns.size == rows.size) { "${columns.size} sets of values of ${rows.size} rows" }
        val made = scores(rows, columns)
        IntStream.range(0, rows.size).parallel().forEach { each(it, made[it]) }
    }

    /**
     * OF EACH OF [rows], WHAT IT IS SHOWN WITH of its values [columns] (null: every value; one array given to several
     * rows one set of values for them all): every value likely — its score above [half] — and of the rest the
     * highest-scored, likely or not, up to [least] with the likely; none of its [closed] (values, ascending, a list a
     * row). The likely in the order of their places, the rest after them, the higher first, of equal scores the lower
     * value ([choose]). Of the tags of fanfics.me a work is a few values of 8 894: a suggester that scores where it
     * chooses hands back the few, not the rows of every value.
     *
     * Of a suggester that tells no other: chosen of the rows handed over one at a time ([scores] with a reader).
     */
    fun shown(rows: IntArray, columns: List<IntArray?>, closed: List<IntArray>, least: Int): List<Shown> {
        require(columns.size == rows.size && closed.size == rows.size) {
            "${columns.size} sets of values, ${closed.size} closed of ${rows.size} rows"
        }
        val out = arrayOfNulls<Shown>(rows.size)
        scores(rows, columns) { r, row -> out[r] = choose(row, columns[r], closed[r], least, half) }
        return out.map { it!! }
    }

    /** The chance of a [score] of [scores]: never lower of a higher score. */
    fun chance(score: Float): Float = score

    /**
     * A score whose chance is one half, or the highest of those whose chance is one half or lower: no score at or under
     * it is likely, every one above it is.
     */
    val half: Float
        get() = 0.5f

    /**
     * What the scores hold besides the main memory freed — the coordinates of the items on the card: closed by whoever
     * readied them, once read.
     */
    override fun close() {}

    companion object {
        /**
         * THE RULE OF [shown], of one row: of a row's [scores] — of the values [allowed], in their order (null: of
         * every value, by its place) — the places of those shown and their scores. Every suggester's own choosing gives
         * what this gives.
         *
         * IN ONE PASS, THE NUMBER LOOKED AT FIRST: [closed] is asked only of a value that would stay. Of every work a
         * set asked of every value in five passes, a facet of thousands of characters over tens of thousands of works
         * took half a minute.
         */
        fun choose(scores: FloatArray, allowed: IntArray?, closed: IntArray, least: Int, half: Float): Shown =
            RowChoice(scores, allowed, closed, least).shown(half)
    }
}

/**
 * The choosing of one row ([SuggestionScores.choose]): of a row's [scores] — of the values [allowed], in their order
 * (null: of every value, by its place) — the likely ones and the best [least] of the rest, none of the [closed].
 */
private class RowChoice(
    private val scores: FloatArray,
    private val allowed: IntArray?,
    private val closed: IntArray,
    private val least: Int,
) {
    /** The likely values' places, in their order. */
    private val likely = ArrayList<Int>()

    /** The best of the rest kept so far, the better first: [kept] of them. */
    private val others = IntArray(least)
    private var kept = 0

    /** The places shown and their scores: the likely, then as many of the rest as make [least] with them. */
    fun shown(half: Float): Shown {
        for (p in scores.indices) {
            if (scores[p] > half) {
                if (!shut(p)) likely += p
            } else if (wouldStay(p) && !shut(p)) {
                keep(p)
            }
        }
        val places = (likely + others.take(minOf(kept, (least - likely.size).coerceAtLeast(0)))).toIntArray()
        return Shown(places, FloatArray(places.size) { scores[places[it]] })
    }

    /** The value at the place [p] of the row. */
    private fun value(p: Int) = allowed?.get(p) ?: p

    /** Whether the value at [p] is one the row may not be shown with. */
    private fun shut(p: Int) = closed.binarySearch(value(p)) >= 0

    /** Whether [p] goes before [other]: a higher score, of equal scores the lower value. */
    private fun before(p: Int, other: Int) =
        scores[p] > scores[other] || scores[p] == scores[other] && value(p) < value(other)

    /**
     * Whether an unlikely [p] would stay among the best of the rest: there is room yet, or it goes before the last
     * kept. Asked before [shut], the cheaper first.
     */
    private fun wouldStay(p: Int) = least > 0 && (kept < least || before(p, others[least - 1]))

    /** Puts [p] in its place among the rest kept, the last of them falling off when there is no room. */
    private fun keep(p: Int) {
        var at = minOf(kept, least - 1)
        while (at > 0 && before(p, others[at - 1])) {
            others[at] = others[at - 1]
            at--
        }
        others[at] = p
        if (kept < least) kept++
    }
}

/**
 * What a row of a task is shown with ([SuggestionScores.shown]): the [places] of the values among the values it was
 * asked of — the value itself when it was asked of every one — and their [scores].
 */
class Shown(val places: IntArray, val scores: FloatArray) {
    init {
        require(places.size == scores.size) { "${places.size} places, ${scores.size} scores" }
    }
}

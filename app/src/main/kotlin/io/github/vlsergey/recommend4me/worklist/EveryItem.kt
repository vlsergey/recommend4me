package io.github.vlsergey.recommend4me.worklist

import io.github.vlsergey.recommend4me.database.worklist.tables.references.ITEM
import io.github.vlsergey.recommend4me.database.worklist.tables.references.ITEM_NUMBER
import io.github.vlsergey.recommend4me.database.worklist.tables.references.ITEM_VALUE
import io.github.vlsergey.recommend4me.item.ItemKey
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.Table
import org.jooq.impl.DSL

/**
 * EVERY ITEM OF A SOURCE AS THE INDEX OF THE LIST KEEPS IT ([ListFacetValues.everyItem]), by their places, the ids
 * ascending ([ids]): its values by their numbers ([values]) — a value one number whichever item has it, its name in
 * [names], "<facet id>:<value>" —, its title as the user corrected it when asked for ([titles]), its numbers by their
 * ids ([numbers]: nought of one not known), the keys of its values of the facets read apart, by the facet ([apart]:
 * none of an item of none), and when asked for its predicted grade and its chance of interest, the user's mark over the
 * model's, as the index keeps them ([predictions], [interests]: not a number of none). Arrays by place, not maps by id:
 * of the 1.4 million works of ficbook the maps of strings and boxed numbers took most of the reading (owner,
 * 2026-11-20).
 */
class ItemRows(
    val ids: List<String>,
    val values: Array<IntArray>,
    val titles: List<String?>,
    val numbers: Map<String, DoubleArray>,
    val names: List<String>,
    val apart: Map<String, List<List<String>>> = emptyMap(),
    val predictions: DoubleArray? = null,
    val interests: DoubleArray? = null,
)

/**
 * The reading of every item of a [source], in one transaction ([ListIndex.searching]): its items and their titles — and
 * their predictions and chances of interest, [predicted] — in one pass, ordered by their keys; then the values of the
 * facets [facetIds] and of those read [apart] in one pass of theirs — not another pass of every item for a facet
 * another reader wants (ficbook's fandoms, 3 s) —, and the numbers [numberIds] alike. The database numbers them itself
 * — an item by its place among the source's keys ascending, a value by its place among the values read — and hands two
 * integers a row, the names of the values once: the 16.5 million values of ficbook as three strings a row, an item's
 * key parsed and a value's name made and looked up of every one, took 15 s of the 17 of the reading, numbered so 0.8
 * (2026-11-22, measured on a copy of the books' index). The rows read of the JDBC result itself, its columns by their
 * places: jOOQ's records, a boxed number a column, made the 16.6 million rows of the values 3.3 s and the 13.9 million
 * of the numbers 6.5, the result alone gives them in 0.6 and 0.4.
 */
internal class EveryItem(
    private val source: String,
    private val facetIds: List<String>,
    private val numberIds: List<String>,
    private val titled: Boolean,
    private val apart: List<String> = emptyList(),
    private val predicted: Boolean = false,
) {
    fun read(db: DSLContext): ItemRows {
        val prefix = ItemKey(source, "").toString()
        val keys = ArrayList<String>()
        val titles = ArrayList<String?>()
        val predictions = Numbers()
        val interests = Numbers()
        db.select(ITEM.ITEM_KEY, ITEM.TITLE, ITEM.PREDICTION, ITEM.INTEREST)
            .from(ITEM)
            .where(ITEM.SOURCE.eq(source))
            .orderBy(ITEM.ITEM_KEY)
            .fetchSize(STREAMED)
            .fetchResultSet()
            .use { rs ->
                while (rs.next()) {
                    keys += rs.getString(1).substring(prefix.length)
                    if (titled) titles += rs.getString(2)
                    if (predicted) {
                        predictions.add(rs.getDouble(PREDICTION_COLUMN).takeUnlessNull(rs.wasNull()))
                        interests.add(rs.getDouble(INTEREST_COLUMN).takeUnlessNull(rs.wasNull()))
                    }
                }
            }
        val placed = Placed.of(keys)
        val places = Places(source)
        val values = valuesOf(db, places, placed)
        return ItemRows(
            placed.ids,
            values.compared,
            if (titled) placed.inOrder(titles) else List(keys.size) { null },
            numbersOf(db, places, placed),
            values.codes.names,
            values.apart,
            if (predicted) placed.inOrder(predictions.all()) else null,
            if (predicted) placed.inOrder(interests.all()) else null,
        )
    }

    /** A number of a column read, of none [NONE]. */
    private fun Double.takeUnlessNull(none: Boolean) = if (none) NONE else this

    /** Numbers read a row at a time, of the room grown as they come. */
    private class Numbers {
        private var all = DoubleArray(START)
        private var count = 0

        fun add(x: Double) {
            if (count == all.size) all = all.copyOf(all.size * 2)
            all[count++] = x
        }

        fun all(): DoubleArray = all.copyOf(count)
    }

    /** The values read, of every item by its place: the numbers of those of [facetIds], the keys of those [apart]. */
    private class Values(val codes: Codes, val compared: Array<IntArray>, val apart: Map<String, List<List<String>>>)

    /**
     * The values the database numbered, by their numbers: of a facet of [facetIds], its number among theirs
     * ([compared], -1 of none) and their names ([names]); of a facet read [apart], its facet's place among them
     * ([apartFacet], -1 of none) and its key ([key]).
     */
    private inner class Codes(rows: List<Pair<String, String>>) {
        val names = ArrayList<String>()
        val compared = IntArray(rows.size) { -1 }
        val apartFacet = IntArray(rows.size) { -1 }
        val key = arrayOfNulls<String>(rows.size)

        init {
            val comparedFacets = facetIds.toSet()
            rows.forEachIndexed { c, (facet, value) ->
                if (facet in comparedFacets) compared[c] = names.size.also { names += "$facet:$value" }
                apartFacet[c] = apart.indexOf(facet)
                if (apartFacet[c] >= 0) key[c] = value
            }
        }
    }

    /** Of every item by its place, its values of the facets read: compared by their numbers, apart by their keys. */
    private fun valuesOf(db: DSLContext, places: Places, placed: Placed): Values {
        val asked = (facetIds + apart).distinct()
        if (asked.isEmpty()) return Values(Codes(emptyList()), Array(placed.size) { EMPTY }, emptyMap())
        val v = ITEM_VALUE
        val named =
            DSL.selectDistinct(v.FACET, v.VALUE_KEY)
                .from(v)
                .join(places.table)
                .on(places.key.eq(v.ITEM_KEY))
                .where(v.FACET.`in`(asked))
                .asTable("named")
        val facet = named.field(v.FACET)!!
        val valueKey = named.field(v.VALUE_KEY)!!
        val numbered =
            DSL.select(facet, valueKey, DSL.rowNumber().over().orderBy(facet, valueKey).minus(1).`as`(CODE))
                .from(named)
                .asTable("codes")
        val code = numbered.field(CODE, Int::class.java)!!
        val codes =
            Codes(
                db.select(numbered.field(facet)!!, numbered.field(valueKey)!!).from(numbered).orderBy(code).fetch {
                    it.value1()!! to it.value2()!!
                }
            )
        val buffers = arrayOfNulls<IntArray>(placed.size)
        val counts = IntArray(placed.size)
        val keys = List(apart.size) { arrayOfNulls<MutableList<String>>(placed.size) }
        db.select(places.place, code)
            .from(v)
            .join(places.table)
            .on(places.key.eq(v.ITEM_KEY))
            .join(numbered)
            .on(numbered.field(facet)!!.eq(v.FACET).and(numbered.field(valueKey)!!.eq(v.VALUE_KEY)))
            .where(v.FACET.`in`(asked))
            .fetchSize(STREAMED)
            .fetchResultSet()
            .use { rs ->
                while (rs.next()) {
                    val i = placed.of(rs.getInt(1))
                    val c = rs.getInt(2)
                    if (codes.compared[c] >= 0) put(buffers, counts, i, codes.compared[c])
                    if (codes.apartFacet[c] >= 0)
                        (keys[codes.apartFacet[c]][i]
                                ?: ArrayList<String>(1).also { keys[codes.apartFacet[c]][i] = it })
                            .add(codes.key[c]!!)
                }
            }
        return Values(
            codes,
            Array(placed.size) { i -> buffers[i]?.copyOf(counts[i]) ?: EMPTY },
            apart.withIndex().associate { (f, id) -> id to List(placed.size) { keys[f][it] ?: emptyList() } },
        )
    }

    /** The value [code] added to the values of the item at [i], the room of its buffer grown as it fills. */
    private fun put(buffers: Array<IntArray?>, counts: IntArray, i: Int, code: Int) {
        val own = buffers[i] ?: IntArray(START).also { buffers[i] = it }
        val grown = if (counts[i] == own.size) own.copyOf(own.size * 2).also { buffers[i] = it } else own
        grown[counts[i]++] = code
    }

    /**
     * Of every number of [numberIds], its value of every item by its place; nought of one not known. The number by its
     * place among [numberIds], not its id: ficbook's dozen numbers of 1.4 million works, an id a row, took 7 s.
     */
    private fun numbersOf(db: DSLContext, places: Places, placed: Placed): Map<String, DoubleArray> {
        val out = Array(numberIds.size) { DoubleArray(placed.size) }
        if (numberIds.isEmpty()) return emptyMap()
        val n = ITEM_NUMBER
        val number =
            DSL.case_(n.NUMBER_ID)
                .mapValues(numberIds.withIndex().associateTo(HashMap<String?, Int>()) { (k, id) -> id to k })
        db.select(places.place, number, n.CONTENT)
            .from(n)
            .join(places.table)
            .on(places.key.eq(n.ITEM_KEY))
            .where(n.NUMBER_ID.`in`(numberIds))
            .fetchSize(STREAMED)
            .fetchResultSet()
            .use { rs -> while (rs.next()) out[rs.getInt(2)][placed.of(rs.getInt(1))] = rs.getDouble(VALUE_COLUMN) }
        return numberIds.withIndex().associate { (k, id) -> id to out[k] }
    }

    /** The items of the source, each with its place among their keys ascending as the database orders them. */
    private class Places(source: String) {
        val table: Table<*> =
            DSL.select(ITEM.ITEM_KEY, DSL.rowNumber().over().orderBy(ITEM.ITEM_KEY).minus(1).`as`(PLACE))
                .from(ITEM)
                .where(ITEM.SOURCE.eq(source))
                .asTable("places")
        val key: Field<String?> = table.field(ITEM.ITEM_KEY)!!
        val place: Field<Int?> = table.field(PLACE, Int::class.java)!!
    }

    /**
     * The ids as the database ordered them, and their places as Kotlin's strings compare ([ids], what is searched in by
     * halves): the same but of an id of a letter beyond the 16 bits — the database orders by the code points, the
     * strings by their 16-bit units.
     */
    private class Placed(ordered: List<String>, private val order: IntArray?) {
        val size = ordered.size

        val ids: List<String> = if (order == null) ordered else inOrder(ordered).map { it!! }

        /** The place of the item the database placed at [place]. */
        fun of(place: Int): Int = order?.get(place) ?: place

        /** The numbers [rows] by the database's places put at their own. */
        fun inOrder(rows: DoubleArray): DoubleArray {
            if (order == null) return rows
            val out = DoubleArray(rows.size)
            rows.forEachIndexed { i, row -> out[order[i]] = row }
            return out
        }

        /** The [rows] by the database's places put at their own. */
        fun <T> inOrder(rows: List<T>): List<T?> {
            if (order == null) return rows
            val out = arrayOfNulls<Any?>(rows.size)
            rows.forEachIndexed { i, row -> out[order[i]] = row }
            @Suppress("UNCHECKED_CAST")
            return out.asList() as List<T?>
        }

        companion object {
            fun of(ordered: List<String>): Placed {
                if ((1 until ordered.size).all { ordered[it - 1] < ordered[it] }) return Placed(ordered, null)
                val sorted = ordered.indices.sortedWith(compareBy { ordered[it] })
                return Placed(ordered, IntArray(ordered.size).also { o -> sorted.forEachIndexed { j, i -> o[i] = j } })
            }
        }
    }

    private companion object {
        /** The rows streamed at a time: memory, not meaning. */
        const val STREAMED = 50_000

        /** The column of a number's value in its row read: after its item's place and the number's. */
        const val VALUE_COLUMN = 3

        /** The columns of an item's prediction and chance of interest in its row read: after its key and title. */
        const val PREDICTION_COLUMN = 3
        const val INTEREST_COLUMN = 4

        /** A number of none: of an item not predicted. */
        const val NONE = Double.NaN

        /** The values of an item first made room for. */
        const val START = 8

        const val PLACE = "place"
        const val CODE = "code"

        val EMPTY = IntArray(0)
    }
}

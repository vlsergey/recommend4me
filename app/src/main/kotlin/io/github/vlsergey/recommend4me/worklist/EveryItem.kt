package io.github.vlsergey.recommend4me.worklist

import java.util.Arrays
import java.util.stream.IntStream
import org.apache.lucene.index.LeafReader
import org.apache.lucene.index.Term
import org.apache.lucene.search.DocIdSetIterator
import org.apache.lucene.util.NumericUtils

/**
 * EVERY ITEM OF A SOURCE AS THE INDEX OF THE LIST KEEPS IT ([ListFacetValues.everyItem]), by their places, the ids
 * ascending ([ids]): its values by their numbers ([values]) — a value one number whichever item has it, its name in
 * [names], "<facet id>:<value>" —, its title as the user corrected it when asked for ([titles]), its numbers by their
 * ids ([numbers]: nought of one not known). Arrays by place, not maps by id: of the 1.4 million works of ficbook the
 * maps of strings and boxed numbers took most of the reading (owner, 2026-11-20).
 */
class ItemRows(
    val ids: List<String>,
    val values: Array<IntArray>,
    val titles: List<String?>,
    val numbers: Map<String, DoubleArray>,
    val names: List<String>,
)

/**
 * The reading of every item of a [source]: of every segment of the index its items' doc values read a run of documents
 * on a core, each with doc values of its own — the key, the title, the ordinals of every facet's values, the numbers —;
 * then the ordinals met numbered once a value, and every segment's items put together, ordered by their ids.
 */
internal class EveryItem(
    private val source: String,
    private val facetIds: List<String>,
    private val numberIds: List<String>,
    private val titled: Boolean,
) {
    private val names = ArrayList<String>()
    private val byName = HashMap<String, Int>()

    fun read(leaves: List<LeafReader>): ItemRows {
        val read = leaves.map { Segment(it).also(Segment::read) }
        read.forEach(Segment::number)
        // The segments one after another
        val total = read.sumOf { it.ids.size }
        val ids = arrayOfNulls<String>(total)
        val values = arrayOfNulls<IntArray>(total)
        val titles = arrayOfNulls<String>(total)
        val numbers = numberIds.associateWith { DoubleArray(total) }
        var at = 0
        read.forEach { s ->
            s.ids.copyInto(ids, at)
            s.values.copyInto(values, at)
            s.titles?.copyInto(titles, at)
            numberIds.forEach { id -> s.numbers.getValue(id).copyInto(numbers.getValue(id), at) }
            at += s.ids.size
        }
        // Ordered by their ids, an item of none and an id met twice left out
        val order = ids.indices.filter { ids[it] != null }.toTypedArray()
        Arrays.parallelSort(order) { a, b -> ids[a]!!.compareTo(ids[b]!!) }
        val kept = order.filterIndexed { k, i -> k == 0 || ids[order[k - 1]] != ids[i] }.toIntArray()
        return ItemRows(
            List(kept.size) { ids[kept[it]]!! },
            Array(kept.size) { values[kept[it]] ?: IntArray(0) },
            List(kept.size) { titles[kept[it]] },
            numbers.mapValues { (_, all) -> DoubleArray(kept.size) { all[kept[it]] } },
            names,
        )
    }

    /** One segment: its live documents of the source, read a run on a core. */
    private inner class Segment(private val r: LeafReader) {
        private val docs: IntArray = docsOf()
        val ids = arrayOfNulls<String>(docs.size)
        val titles = if (titled) arrayOfNulls<String>(docs.size) else null
        val numbers = numberIds.associateWith { DoubleArray(docs.size) }

        /** Of every item its values: the segment's ordinals made codes first ([codeAt]), then numbers ([number]). */
        val values = arrayOfNulls<IntArray>(docs.size)

        /** Where every facet's ordinals begin among the codes of the segment. */
        private val codeAt =
            IntArray(facetIds.size + 1).also { at ->
                facetIds.forEachIndexed { f, id ->
                    at[f + 1] =
                        at[f] + (r.getSortedSetDocValues(ListDocuments.valuesField(id))?.valueCount ?: 0L).toInt()
                }
            }
        private val used = BooleanArray(codeAt.last())

        private fun docsOf(): IntArray {
            val postings = r.postings(Term(ListDocuments.SOURCE, source)) ?: return IntArray(0)
            val live = r.liveDocs
            val out = ArrayList<Int>()
            var doc = postings.nextDoc()
            while (doc != DocIdSetIterator.NO_MORE_DOCS) {
                if (live == null || live.get(doc)) out += doc
                doc = postings.nextDoc()
            }
            return out.toIntArray()
        }

        fun read() {
            val runs = (docs.size + RUN - 1) / RUN
            IntStream.range(0, runs).parallel().forEach { k -> readRun(k * RUN, minOf(docs.size, (k + 1) * RUN)) }
        }

        /** The items [from] to [to] with doc values of their own: a run on a core. */
        private fun readRun(from: Int, to: Int) {
            val keys = r.getSortedDocValues(ListDocuments.KEY) ?: return
            val title = if (titled) r.getBinaryDocValues(ListDocuments.TITLE) else null
            val sets = facetIds.map { r.getSortedSetDocValues(ListDocuments.valuesField(it)) }
            val nums = numberIds.map { r.getSortedNumericDocValues(ListDocuments.numberField(it)) }
            val buffer = IntArray(BUFFER)
            for (i in from until to) {
                val doc = docs[i]
                if (!keys.advanceExact(doc)) continue
                ids[i] = idOf(keys.lookupOrd(keys.ordValue()))
                if (title != null && title.advanceExact(doc)) titles!![i] = title.binaryValue().utf8ToString()
                var n = 0
                sets.forEachIndexed { f, dv ->
                    if (dv == null || !dv.advanceExact(doc)) return@forEachIndexed
                    repeat(dv.docValueCount()) {
                        val code = codeAt[f] + dv.nextOrd().toInt()
                        used[code] = true
                        if (n < buffer.size) buffer[n++] = code
                    }
                }
                values[i] = buffer.copyOf(n)
                nums.forEachIndexed { k, dv ->
                    if (dv != null && dv.advanceExact(doc))
                        numbers.getValue(numberIds[k])[i] = NumericUtils.sortableLongToDouble(dv.nextValue())
                }
            }
        }

        /** The codes met numbered — a value's name looked up once — and every item's codes made their numbers. */
        fun number() {
            val numberOf = IntArray(used.size) { -1 }
            facetIds.forEachIndexed { f, id ->
                val dv = r.getSortedSetDocValues(ListDocuments.valuesField(id)) ?: return@forEachIndexed
                for (code in codeAt[f] until codeAt[f + 1]) {
                    if (!used[code]) continue
                    val name = "$id:${dv.lookupOrd((code - codeAt[f]).toLong()).utf8ToString()}"
                    numberOf[code] = byName.getOrPut(name) { names.size.also { names += name } }
                }
            }
            IntStream.range(0, values.size).parallel().forEach { i ->
                values[i]?.let { codes -> for (k in codes.indices) codes[k] = numberOf[codes[k]] }
            }
        }
    }

    private companion object {
        /** The items a core reads at a time. */
        const val RUN = 32_768

        /** The values of an item at most. */
        const val BUFFER = 4096

        /**
         * The id of the key "<source>/<id>" ([io.github.vlsergey.recommend4me.item.ItemKey]), decoded of its bytes
         * after the first slash: one string an item.
         */
        fun idOf(key: org.apache.lucene.util.BytesRef): String {
            var slash = key.offset
            while (slash < key.offset + key.length && key.bytes[slash] != '/'.code.toByte()) slash++
            return String(key.bytes, slash + 1, key.offset + key.length - slash - 1, Charsets.UTF_8)
        }
    }
}

package io.github.vlsergey.recommend4me.search

import io.github.vlsergey.recommend4me.item.ItemKey
import java.util.IdentityHashMap
import org.apache.lucene.index.IndexReader
import org.apache.lucene.index.LeafReaderContext
import org.apache.lucene.index.PostingsEnum
import org.apache.lucene.index.Terms
import org.apache.lucene.search.DocIdSetIterator
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.util.BytesRefBuilder
import org.apache.lucene.util.FixedBitSet

/** A name found of the index: the value it is of, in which field of the work, in which paragraph of it. */
internal class FoundIn(val value: String, val field: String, val paragraph: Int)

/** A matcher of one asking of [NameReading]: the asking's place, the matcher, each of its stems' place in its order. */
internal class Reader(val asking: Int, val matcher: NameMatcher) {
    val order: Map<String, Int> = matcher.stems.withIndex().associate { (i, stem) -> stem to i }
}

/**
 * THE NAMES COUNTED OF THE INDEX, not of the texts, of one reading of [searcher]: of every name's stems the places in
 * the works' own fields ([LuceneIndex.IndexedField.counted]) with whether written with a capital, and the marks the
 * paragraphs begin with ([ParagraphMarks]); then the rules of [NameMatcher] over them, a field at a time — no text read
 * again, no chapter. Of every asking its works — every work indexed when null — by its own names, answered in the order
 * asked; the index read once for them all: every stem looked up once in a field of a segment, every name stemmed once.
 */
internal class NameReading(
    index: LuceneIndex,
    private val searcher: IndexSearcher,
    private val asked: List<NamesAsked>,
) {
    private val documents = AskedDocuments(searcher, asked)
    private val counted = index.fields(searcher).text.filter { it.counted }.map { it.name }
    private val readers = readersOf()

    /** Of every asking, the names found of every work of it: a work naming none left out. */
    fun found(): List<Map<ItemKey, List<FoundIn>>> {
        val places = StemPlaces(documents, counted, readers)
        places.read(searcher.indexReader)
        val out = List(asked.size) { HashMap<ItemKey, MutableList<FoundIn>>() }
        for (slot in documents.docs.indices) {
            val own = readers[slot] ?: continue
            foundOf(slot, own, places, out)
        }
        return out.map { found -> found.filterValues { it.isNotEmpty() } }
    }

    /**
     * A matcher of every asking and set of languages, of every document by its place: of one map of names and set of
     * languages one base, the names stemmed once for them all, every asking's values left out laid over it.
     */
    private fun readersOf(): Array<MutableList<Reader>?> {
        val stems = NameStems()
        val bases = IdentityHashMap<Map<String, List<String>>, HashMap<List<String>, NameMatcher.Base>>()
        val readers = arrayOfNulls<MutableList<Reader>>(documents.docs.size)
        documents.docsOf.forEachIndexed { i, own ->
            own.groupBy { documents.languages[documents.slotOf[it]] }
                .forEach { (langs, docsOfThem) ->
                    val base =
                        bases
                            .getOrPut(asked[i].names) { HashMap() }
                            .getOrPut(langs) { NameMatcher.Base(asked[i].names, langs, stems) }
                    val reader = Reader(i, base.without(asked[i].without))
                    docsOfThem.forEach {
                        val slot = documents.slotOf[it]
                        (readers[slot] ?: ArrayList<Reader>().also { r -> readers[slot] = r }) += reader
                    }
                }
        }
        return readers
    }

    /** The names the matchers [own] of the document at [slot] find in its fields' words, put in [out]. */
    private fun foundOf(
        slot: Int,
        own: List<Reader>,
        places: StemPlaces,
        out: List<MutableMap<ItemKey, MutableList<FoundIn>>>,
    ) {
        counted.forEachIndexed { f, field ->
            val list = places.words[f][slot] ?: return@forEachIndexed
            val begins = places.marks[f][slot]?.also { it.sort() } ?: IntArray(0)
            own.forEach { reader ->
                // The words of its own names alone: as a reading of them alone has them
                val stemsOf = reader.matcher.stems
                val mine = list.filter { it.stem in stemsOf }
                val into = out[reader.asking].getOrPut(documents.keyOf[slot]!!) { ArrayList() }
                reader.matcher.found(mine).forEach { name ->
                    into += FoundIn(name.value, field, paragraphOf(begins, name.first.position))
                }
            }
        }
    }

    /** Of the paragraph a word at [position] stands in: how many of the marks [begins] are before it. */
    private fun paragraphOf(begins: IntArray, position: Int): Int =
        begins.binarySearch(position).let { if (it >= 0) it + 1 else -it - 1 }
}

/**
 * THE DOCUMENTS OF THE WORKS ASKED of one reading of [searcher]: of every asking its works' documents — every work
 * indexed when it names none ([docsOf]) — each document a place of its own among them all ([slotOf]) in the order of
 * the index ([docs]), with its work's key ([keyOf]) and the languages it was stemmed as ([languages]).
 */
internal class AskedDocuments(searcher: IndexSearcher, asked: List<NamesAsked>) {
    private val reader = searcher.indexReader

    /** Of every segment, its documents of items alive: what a work's id is looked for among. */
    private val items = liveItems()

    /**
     * The document of every work asked, of the ids' terms of every segment gone through once in their order — not a
     * search a work.
     */
    private val docOf = HashMap<ItemKey, Int>()

    /** Every document of an item, in the order of the index: of the askings of every work. */
    private val every by lazy { everyItem() }

    /** Of every asking, its documents. */
    val docsOf: List<IntArray>

    /** Every document asked, in the order of the index. */
    val docs: IntArray

    /** Of every document of the index, its place among those asked; -1 of one not asked. */
    val slotOf = IntArray(reader.maxDoc()) { -1 }

    /** Of every document asked, its work's key. */
    val keyOf: Array<ItemKey?>

    /** Of every document asked, the languages it was stemmed as — one list of every set of them. */
    val languages: Array<List<String>>

    init {
        val wanted = asked.flatMap { it.items.orEmpty() }.distinct().sortedBy { it.toString() }
        if (wanted.isNotEmpty()) reader.leaves().forEach { documentsIn(it, wanted) }
        docsOf = asked.map { a ->
            if (a.names.isEmpty()) IntArray(0) else a.items?.mapNotNull { docOf[it] }?.toIntArray() ?: every
        }
        docs = docsOf.flatMap { it.asIterable() }.distinct().sorted().toIntArray()
        docs.forEachIndexed { k, doc -> slotOf[doc] = k }
        keyOf = arrayOfNulls(docs.size)
        docOf.forEach { (key, doc) -> slotOf[doc].takeIf { it >= 0 }?.let { keyOf[it] = key } }
        val stored = searcher.storedFields()
        val languageLists = HashMap<String, List<String>>()
        languages =
            Array(docs.size) { k ->
                val fields =
                    if (keyOf[k] == null) setOf(LuceneIndex.ID, LuceneIndex.LANGUAGES) else setOf(LuceneIndex.LANGUAGES)
                val d = stored.document(docs[k], fields)
                if (keyOf[k] == null) keyOf[k] = ItemKey.parse(d.get(LuceneIndex.ID))
                val own = d.get(LuceneIndex.LANGUAGES).orEmpty()
                languageLists.getOrPut(own) { own.split(',').filter { it.isNotEmpty() } }
            }
    }

    private fun liveItems(): FixedBitSet {
        val items = FixedBitSet(reader.maxDoc().coerceAtLeast(1))
        reader.leaves().forEach { leaf ->
            val live = leaf.reader().liveDocs
            val docs = leaf.reader().postings(LuceneIndex.ITEM_TERM) ?: return@forEach
            var doc = docs.nextDoc()
            while (doc != DocIdSetIterator.NO_MORE_DOCS) {
                if (live == null || live.get(doc)) items.set(leaf.docBase + doc)
                doc = docs.nextDoc()
            }
        }
        return items
    }

    /** The documents of the works [wanted] of the segment [leaf], those not found in an earlier one. */
    private fun documentsIn(leaf: LeafReaderContext, wanted: List<ItemKey>) {
        val ids = leaf.reader().terms(LuceneIndex.ID)?.iterator() ?: return
        var postings: PostingsEnum? = null
        val term = BytesRefBuilder()
        wanted.forEach { key ->
            term.copyChars(key.toString())
            if (key !in docOf && ids.seekExact(term.get())) {
                val docs = ids.postings(postings, PostingsEnum.NONE.toInt()).also { postings = it }
                val first = firstLive(docs, leaf.docBase)
                if (first != null) docOf[key] = first
            }
        }
    }

    /** The first of the [docs] of a segment beginning at [base] that is an item alive; null of none. */
    private fun firstLive(docs: PostingsEnum, base: Int): Int? {
        var found: Int? = null
        var doc = docs.nextDoc()
        while (found == null && doc != DocIdSetIterator.NO_MORE_DOCS) {
            if (items.get(base + doc)) found = base + doc
            doc = docs.nextDoc()
        }
        return found
    }

    private fun everyItem(): IntArray {
        val out = ArrayList<Int>()
        var doc = if (items.length() == 0) DocIdSetIterator.NO_MORE_DOCS else items.nextSetBit(0)
        while (doc != DocIdSetIterator.NO_MORE_DOCS) {
            out += doc
            doc = if (doc + 1 >= items.length()) DocIdSetIterator.NO_MORE_DOCS else items.nextSetBit(doc + 1)
        }
        return out.toIntArray()
    }
}

/**
 * EVERY STEM AT ITS PLACES, of every counted field of every document asked ([documents]): the words the rules read
 * ([words]); and where its paragraphs begin ([marks]) — each by the field's place among [counted], then the document's
 * place among those asked.
 */
internal class StemPlaces(
    private val documents: AskedDocuments,
    private val counted: List<String>,
    private val readers: Array<MutableList<Reader>?>,
) {
    val words = Array(counted.size) { arrayOfNulls<MutableList<NamedWord>>(documents.docs.size) }
    val marks = Array(counted.size) { arrayOfNulls<IntArray>(documents.docs.size) }

    /** Every segment of [reader] that holds a document asked, read. */
    fun read(reader: IndexReader) {
        val docs = documents.docs
        var from = 0
        reader.leaves().forEach { leaf ->
            val upTo = leaf.docBase + leaf.reader().maxDoc()
            var until = from
            while (until < docs.size && docs[until] < upTo) until++
            if (until > from) readLeaf(leaf, from, until)
            from = until
        }
    }

    /** The segment [leaf], of the documents asked [first] until [until]. */
    private fun readLeaf(leaf: LeafReaderContext, first: Int, until: Int) {
        val inLeaf = IntArray(until - first) { documents.docs[first + it] - leaf.docBase }
        val asked = FixedBitSet(leaf.reader().maxDoc()).apply { inLeaf.forEach { set(it) } }
        val looked = stemsOf(first, until)
        val inOrder = looked.sorted()
        counted.forEachIndexed { f, field ->
            val fieldTerms = leaf.reader().terms(field) ?: return@forEachIndexed
            FieldReading(leaf, f, fieldTerms, inLeaf, asked).read(looked, inOrder)
        }
    }

    /**
     * The stems of the matchers of the documents asked [first] until [until], and the mark of a paragraph: each looked
     * up once.
     */
    private fun stemsOf(first: Int, until: Int): Set<String> {
        val looked = HashSet<String>()
        val seen = IdentityHashMap<Set<String>, Boolean>()
        for (k in first until until) readers[k]?.forEach { r ->
            if (seen.put(r.matcher.stems, true) == null) looked += r.matcher.stems
        }
        looked += ParagraphMarks.MARK
        return looked
    }

    /**
     * The places of the stems of one field ([f] among [counted]) of the segment [leaf], of its documents asked [inLeaf]
     * — marked in [asked]: one enumeration of the places, reused for every stem — thousands of stems of the names.
     */
    private inner class FieldReading(
        private val leaf: LeafReaderContext,
        private val f: Int,
        private val fieldTerms: Terms,
        private val inLeaf: IntArray,
        private val asked: FixedBitSet,
    ) {
        private val terms = fieldTerms.iterator()
        private var postings: PostingsEnum? = null

        /**
         * The stems [looked] for read: of a field of fewer words than the stems — a facet's values — its words read
         * through; else every stem looked up, in its order [inOrder].
         */
        fun read(looked: Set<String>, inOrder: List<String>) {
            if (fieldTerms.size() in 0 until looked.size) {
                var word = terms.next()
                while (word != null) {
                    val stem = word.utf8ToString()
                    if (stem in looked) read(stem)
                    word = terms.next()
                }
            } else {
                val term = BytesRefBuilder()
                inOrder.forEach { stem ->
                    term.copyChars(stem)
                    if (terms.seekExact(term.get())) read(stem)
                }
            }
        }

        /** The places of the [stem] the terms stand at, of the documents asked: of the two, the fewer gone through. */
        private fun read(stem: String) {
            val places = terms.postings(postings, PostingsEnum.PAYLOADS.toInt()).also { postings = it }
            if (terms.docFreq() < inLeaf.size) {
                var doc = places.nextDoc()
                while (doc != DocIdSetIterator.NO_MORE_DOCS) {
                    if (asked.get(doc)) take(places, stem, doc)
                    doc = places.nextDoc()
                }
            } else
                inLeaf.forEach { doc ->
                    // Gone to the document asked for, or past it: it may stand there already, gone to while looking for
                    // the one before
                    val standing = if (places.docID() < doc) places.advance(doc) else places.docID()
                    if (standing == doc) take(places, stem, doc)
                }
        }

        /** The places of the [stem] in the document [doc] of the segment: a word each, or the marks of paragraphs. */
        private fun take(places: PostingsEnum, stem: String, doc: Int) {
            val slot = documents.slotOf[leaf.docBase + doc]
            if (stem == ParagraphMarks.MARK) marks[f][slot] = IntArray(places.freq()) { places.nextPosition() }
            else {
                val list = words[f][slot] ?: ArrayList<NamedWord>().also { words[f][slot] = it }
                repeat(places.freq()) {
                    list += NamedWord(stem, places.nextPosition(), places.payload == Stemmers.CAPITAL)
                }
            }
        }
    }
}

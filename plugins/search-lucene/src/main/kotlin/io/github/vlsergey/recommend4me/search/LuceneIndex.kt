package io.github.vlsergey.recommend4me.search

import io.github.vlsergey.recommend4me.item.ItemKey
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.io.path.exists
import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute
import org.apache.lucene.document.Document
import org.apache.lucene.document.Field
import org.apache.lucene.document.KnnFloatVectorField
import org.apache.lucene.document.StoredField
import org.apache.lucene.document.StringField
import org.apache.lucene.document.TextField
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.index.FieldInfos
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.index.LeafReader
import org.apache.lucene.index.Term
import org.apache.lucene.index.VectorSimilarityFunction
import org.apache.lucene.search.DocIdSetIterator
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.SearcherFactory
import org.apache.lucene.search.SearcherManager
import org.apache.lucene.search.TermQuery
import org.apache.lucene.store.FSDirectory

/**
 * The index of one content type on disk: of every item a document of its fields — a field per [SearchField], searched
 * by words — and a document of every passage of its texts ([SearchPassage]), its vector searched by meaning; all of
 * them under the item's [ID], replaced and removed together.
 *
 * THE WEIGHT OF A FIELD IS IN ITS NAME — `<marks>.<weight in hundredths>.<key>`, the marks `n` of a field that names
 * the item, `c` of the work's own words ([SearchField.counted]), `t` of neither — so a search reads which fields there
 * are and what each weighs from the index itself, whatever source wrote them. Every passage's vector is in the one
 * field [VECTOR].
 *
 * EVERY TEXT STEMMED BEFORE IT IS PUT IN, ONCE, as written in its language ([TextLanguages], [Stemmers]) — one field of
 * it, no copy by another language (user, 2026-10-16): every word as its stem at its place, marked when written with a
 * capital ([Stemmers.CAPITAL]).
 *
 * MADE ANEW BESIDE THE OLD ONE: a rebuild writes into a folder of its own while the searches use the index as it was,
 * then takes its place; what is updated meanwhile goes into both.
 *
 * KEPT BETWEEN THE RUNS (user, 2026-10-17): every item's document carries the fingerprint of what it was made of
 * ([fingerprint]), the index the version of how its documents are made ([FORMAT]); on a start the documents are read
 * and only those whose fingerprint changed written again ([sync]), not every text stemmed and its language told anew.
 * An index of another version is made anew.
 */
class LuceneIndex(private val dir: Path) : AutoCloseable {

    private val lock = ReentrantLock()
    private var writer: IndexWriter = open(dir)
    private var searchers = SearcherManager(writer, SearcherFactory())

    /** The writer of a rebuild in progress: updates go into it too. */
    private var building: IndexWriter? = null

    /**
     * The index brought to [documents], every item of the type: the items whose fingerprint changed or that are new
     * written, those gone removed; answers how many were written. Made anew whole when it is of another [FORMAT], or
     * empty.
     */
    fun sync(documents: Sequence<SearchDocument>): Int {
        val kept =
            lock.withLock {
                DirectoryReader.open(writer).use { reader ->
                    if (reader.indexCommit.userData[FORMAT_KEY] != FORMAT.toString() || reader.numDocs() == 0) null
                    else fingerprintsOf(reader)
                }
            } ?: return rebuild(documents)
        val seen = HashSet<String>()
        var written = 0
        documents.forEach { doc ->
            val id = doc.key.toString()
            seen += id
            val fingerprint = fingerprint(doc)
            if (kept[id] == fingerprint) return@forEach
            lock.withLock { writer.updateDocuments(idTerm(doc.key), documents(doc, fingerprint)) }
            written++
        }
        lock.withLock {
            (kept.keys - seen).forEach { writer.deleteDocuments(Term(ID, it)) }
            commit(writer)
            searchers.maybeRefresh()
        }
        return written
    }

    /**
     * The fingerprint of every item's document of the index [reader] reads, by the item's id: of the items' documents
     * alone, found by their kind — not every passage's read too.
     */
    private fun fingerprintsOf(reader: DirectoryReader): Map<String, Long> {
        val wanted = setOf(ID, FINGERPRINT)
        val out = HashMap<String, Long>()
        for (leaf in reader.leaves()) {
            val r = leaf.reader()
            val live = r.liveDocs
            val stored = r.storedFields()
            val items = r.postings(ITEM_TERM) ?: continue
            var doc = items.nextDoc()
            while (doc != DocIdSetIterator.NO_MORE_DOCS) {
                if (live == null || live.get(doc)) {
                    val d = stored.document(doc, wanted)
                    d.getField(FINGERPRINT)?.numericValue()?.toLong()?.let { out[d.get(ID)] = it }
                }
                doc = items.nextDoc()
            }
        }
        return out
    }

    /** Rebuilds the index from [documents]; searches use the old one until it is done. */
    fun rebuild(documents: Sequence<SearchDocument>): Int {
        val fresh = dir.resolveSibling(dir.fileName.toString() + ".new")
        fresh.toFile().deleteRecursively()
        val next = open(fresh)
        lock.withLock { building = next }
        var count = 0
        // A rebuild that fails leaves no trace: its writer closed, its folder removed, the failure thrown on
        var built = false
        try {
            documents.forEach {
                next.updateDocuments(idTerm(it.key), documents(it))
                count++
            }
            commit(next)
            built = true
        } finally {
            if (!built) {
                lock.withLock { building = null }
                next.close()
                fresh.toFile().deleteRecursively()
            }
        }
        lock.withLock {
            building = null
            next.close()
            searchers.close()
            writer.close()
            dir.toFile().deleteRecursively()
            Files.move(fresh, dir)
            writer = open(dir)
            searchers = SearcherManager(writer, SearcherFactory())
        }
        return count
    }

    /**
     * The [documents] written, those [removed] taken out. A document of the fingerprint the index keeps is not written
     * again — a chance or an answer that changed nothing it is made of — save while a rebuild is under way, which may
     * hold it older. Each written one made once, for the index and the one being built alike.
     */
    fun update(documents: List<SearchDocument>, removed: Collection<ItemKey>) = lock.withLock {
        val kept = if (building == null) keptFingerprints(documents.map { it.key.toString() }) else emptyMap()
        val written = documents.mapNotNull { doc ->
            val fingerprint = fingerprint(doc)
            if (kept[doc.key.toString()] == fingerprint) null else doc.key to documents(doc, fingerprint)
        }
        listOfNotNull(writer, building).forEach { w ->
            written.forEach { (key, docs) -> w.updateDocuments(idTerm(key), docs) }
            removed.forEach { w.deleteDocuments(idTerm(it)) }
        }
        if (written.isNotEmpty() || removed.isNotEmpty()) {
            commit(writer)
            searchers.maybeRefresh()
        }
    }

    /**
     * The fingerprints the index keeps of the items' documents of [ids], by id, as the searcher of the index after its
     * last change reads them: of the items' documents alone, a passage keeps none.
     */
    private fun keptFingerprints(ids: List<String>): Map<String, Long> {
        val out = HashMap<String, Long>()
        val searcher = searchers.acquire()
        try {
            for (leaf in searcher.indexReader.leaves()) ids.forEach { id ->
                keptIn(leaf.reader(), id)?.let { out[id] = it }
            }
        } finally {
            searchers.release(searcher)
        }
        return out
    }

    /** The fingerprint the segment [r] keeps of the item's document of [id], null when it has none live. */
    private fun keptIn(r: LeafReader, id: String): Long? {
        val docs = r.postings(Term(ID, id)) ?: return null
        val live = r.liveDocs
        val stored = r.storedFields()
        var kept: Long? = null
        var doc = docs.nextDoc()
        while (doc != DocIdSetIterator.NO_MORE_DOCS) {
            if (live == null || live.get(doc))
                stored.document(doc, KEPT_FIELDS).getField(FINGERPRINT)?.numericValue()?.toLong()?.let { kept = it }
            doc = docs.nextDoc()
        }
        return kept
    }

    /** Runs [action] with a searcher of the index as it is now. */
    fun <T> search(action: (IndexSearcher) -> T): T {
        val manager = lock.withLock { searchers }
        val searcher = manager.acquire()
        try {
            return action(searcher)
        } finally {
            manager.release(searcher)
        }
    }

    /** The text fields of the index with their weights, and whether it has passages' vectors. */
    fun fields(searcher: IndexSearcher): Fields {
        val names = FieldInfos.getMergedFieldInfos(searcher.indexReader).map { it.name }
        val text = names.mapNotNull(::textField).sortedByDescending { it.weight }
        return Fields(text, VECTOR in names)
    }

    class Fields(val text: List<IndexedField>, val vectors: Boolean) {
        /** Of the fields searched alone: not those of weight 0, the work's own words kept for the counting of names. */
        fun searched() = Fields(text.filter { it.weight > 0f }, vectors)
    }

    /** How many items the index has: their documents of fields, not their passages. */
    fun items(searcher: IndexSearcher): Int = searcher.count(TermQuery(Term(KIND, ITEM)))

    /**
     * A text field of the index: its name there, its key in the documents, its weight, whether it names the item,
     * whether it is the work's own words.
     */
    data class IndexedField(
        val name: String,
        val key: String,
        val weight: Float,
        val naming: Boolean,
        val counted: Boolean,
    )

    override fun close() = lock.withLock {
        searchers.close()
        writer.close()
    }

    companion object {
        const val ID = "id"

        /** What a document is: an item's fields ([ITEM]) or a passage of it ([PASSAGE]). */
        private const val KIND = "kind"
        private const val ITEM = "item"
        private const val PASSAGE = "passage"

        /** The vector of a passage. */
        const val VECTOR = "v"

        /** The languages an item's texts were stemmed as, by a comma. */
        const val LANGUAGES = "languages"

        /** What an item's document was made of ([fingerprint]): written again when it changes. */
        const val FINGERPRINT = "fingerprint"

        /** The stored fields an item's fingerprint is read with. */
        private val KEPT_FIELDS = setOf(ID, FINGERPRINT)

        /**
         * The version of how the documents are made — the fields, the stemming, the marks — kept with every commit: an
         * index of another is made anew. 2: every text stemmed as its language, the paragraphs of the work's own words
         * marked, the fingerprints kept.
         */
        const val FORMAT = 2
        private const val FORMAT_KEY = "format"

        /** Commits [w] with the version of the index ([FORMAT]). */
        private fun commit(w: IndexWriter) {
            w.setLiveCommitData(mapOf(FORMAT_KEY to FORMAT.toString()).entries)
            w.commit()
        }

        /**
         * What a document is made of, as a number: its language, every field — its key, its name, its weight, how it is
         * read, its values — and every passage with its vector. Equal of a work unchanged since it was indexed.
         */
        fun fingerprint(doc: SearchDocument): Long {
            val digest = MessageDigest.getInstance("SHA-256")
            fun put(text: String) {
                digest.update(text.toByteArray(Charsets.UTF_8))
                digest.update(0)
            }
            put(doc.language.orEmpty())
            doc.fields.forEach { f ->
                put(f.key)
                put(f.name)
                put(f.group)
                put(f.weight.toString())
                put("" + f.list + f.naming + f.counted)
                f.values.forEach(::put)
            }
            doc.passages.forEach { p ->
                put(p.field)
                put(p.from.toString())
                put(p.to.toString())
                p.vector?.let { v ->
                    ByteBuffer.allocate(v.size * Float.SIZE_BYTES)
                        .also { it.asFloatBuffer().put(v) }
                        .array()
                        .let(digest::update)
                }
            }
            return ByteBuffer.wrap(digest.digest(), 0, Long.SIZE_BYTES).long
        }

        /**
         * What an item's document is: the term of the documents of items alone, not of their passages, and their query.
         */
        val ITEM_TERM = Term(KIND, ITEM)
        val ITEMS = TermQuery(ITEM_TERM)

        /** The languages the own words of [doc] are stemmed as ([TextLanguages]), in order. */
        fun languagesOf(doc: SearchDocument): List<String> =
            doc.fields
                .flatMap { f -> f.values.filter { it.isNotBlank() }.map { TextLanguages.of(it, doc.language) } }
                .distinct()
                .sorted()

        /** Where a passage is: its field's key, its characters from and until in the field's text. */
        const val PASSAGE_FIELD = "p.field"
        const val PASSAGE_FROM = "p.from"
        const val PASSAGE_TO = "p.to"

        /**
         * The writer's: the texts come stemmed already, each as its language ([documents]); what it tells is the gap
         * between two values of one field — two tags, two attributes are not one phrase — and it stems as English what
         * is ever given it unstemmed.
         */
        val analyzer: Analyzer = Stemmers.analyzer(Stemmers.DEFAULT)

        /** The positions between two values of one field — two tags — farther than any phrase reaches. */
        const val VALUE_GAP = 100

        /** The words of a [text] of the [language] as the index has them: their stems. */
        fun tokens(text: String, language: String): List<String> = tokens(text, Stemmers.analyzer(language))

        /**
         * The words of a [text] as the [analyzer] of its language reads them: of many texts, one analyzer for them all.
         */
        fun tokens(text: String, analyzer: Analyzer): List<String> {
            val out = ArrayList<String>()
            analyzer.tokenStream("", text).use { stream ->
                val term = stream.addAttribute(CharTermAttribute::class.java)
                stream.reset()
                while (stream.incrementToken()) out += term.toString()
                stream.end()
            }
            return out
        }

        fun fieldName(field: SearchField): String =
            ((if (field.naming) "n" else "") + (if (field.counted) "c" else "")).ifEmpty { "t" } +
                "." +
                Math.round(field.weight * HUNDREDTHS) +
                "." +
                field.group

        private val MARKS = Regex("[nct]+")

        /** The weight in a field's name is in hundredths. */
        private const val HUNDREDTHS = 100

        /** The parts of a field's name: its marks, its weight, its key. */
        private const val NAME_PARTS = 3

        private fun textField(name: String): IndexedField? {
            val parts = name.split('.', limit = NAME_PARTS)
            val weight = parts.takeIf { it.size == NAME_PARTS && MARKS.matches(it[0]) }?.get(1)?.toIntOrNull()
            return weight?.let {
                IndexedField(name, parts[2], it / HUNDREDTHS.toFloat(), 'n' in parts[0], 'c' in parts[0])
            }
        }

        private fun idTerm(key: ItemKey) = Term(ID, key.toString())

        private fun open(dir: Path): IndexWriter {
            if (!dir.exists()) Files.createDirectories(dir)
            return IndexWriter(
                FSDirectory.open(dir),
                IndexWriterConfig(analyzer).setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND),
            )
        }

        /**
         * The documents of an item: of its fields, and of every passage of it with a vector; [print] — the item's
         * [fingerprint], made once by the caller.
         */
        private fun documents(doc: SearchDocument, print: Long = fingerprint(doc)): List<Document> {
            val id = doc.key.toString()
            // Every value stemmed as its language: a stream of its own each, of an analyzer that reuses none
            val stemmers = HashMap<String, Analyzer>()
            val used = sortedSetOf<String>()
            val fields =
                Document().apply {
                    add(StringField(ID, id, Field.Store.YES))
                    add(StringField(KIND, ITEM, Field.Store.NO))
                    doc.fields.forEach { field ->
                        val name = fieldName(field)
                        field.values
                            .filter { it.isNotBlank() }
                            .forEach { value ->
                                val language = TextLanguages.of(value, doc.language).also { used += it }
                                val stream =
                                    stemmers
                                        .getOrPut(language) { Stemmers.analyzer(language, reuse = false) }
                                        .tokenStream(name, value)
                                // The work's own words with the marks their paragraphs begin with: two names in one
                                // paragraph are told by them
                                add(TextField(name, if (field.counted) ParagraphMarks(stream, value) else stream))
                            }
                    }
                    // The languages its texts were stemmed as: what a name looked for in them is read as
                    add(StoredField(LANGUAGES, used.joinToString(",")))
                    add(StoredField(FINGERPRINT, print))
                }
            // Every field's text made once, not a field looked for and its text joined again a passage
            val texts = HashMap<String, String>()
            doc.fields.forEach { texts.putIfAbsent(it.key, it.text) }
            val passages =
                doc.passages.mapNotNull { p ->
                    val vector = p.vector ?: return@mapNotNull null
                    // A piece of no phrase — "-", a changelog of dates — says nothing, and its vector lies
                    // near every query: the same few items were found by meaning whatever was looked for
                    val text = p.within(texts[p.field]) ?: return@mapNotNull null
                    if (Phrases.split(text).isEmpty()) return@mapNotNull null
                    Document().apply {
                        add(StringField(ID, id, Field.Store.YES))
                        add(StringField(KIND, PASSAGE, Field.Store.NO))
                        add(StoredField(PASSAGE_FIELD, p.field))
                        add(StoredField(PASSAGE_FROM, p.from))
                        add(StoredField(PASSAGE_TO, p.to))
                        add(KnnFloatVectorField(VECTOR, unit(vector), VectorSimilarityFunction.DOT_PRODUCT))
                    }
                }
            return listOf(fields) + passages
        }

        /** A dot product needs unit vectors; the stored ones are, up to the rounding of a float. */
        fun unit(v: FloatArray): FloatArray {
            var n = 0.0
            for (x in v) n += x.toDouble() * x
            n = kotlin.math.sqrt(n)
            return if (n > LEAST_NORM) FloatArray(v.size) { (v[it] / n).toFloat() } else v
        }

        /** The least length a vector is made of unit length from: a shorter one has no direction to keep. */
        private const val LEAST_NORM = 1e-9
    }
}

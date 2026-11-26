package io.github.vlsergey.recommend4me.search

import io.github.vlsergey.recommend4me.item.ItemKey
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.io.path.exists
import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute
import org.apache.lucene.document.Document
import org.apache.lucene.document.Field
import org.apache.lucene.document.StoredField
import org.apache.lucene.document.StringField
import org.apache.lucene.document.TextField
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.index.FieldInfos
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.index.Term
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.SearcherFactory
import org.apache.lucene.search.SearcherManager
import org.apache.lucene.search.TermQuery
import org.apache.lucene.store.FSDirectory

/**
 * The index of one content type on disk: of every item a document of its fields — a field per [SearchField], searched
 * by words — under the item's [ID]. What is searched by meaning is not here: the application's, its windows' full-text
 * vectors read whole ([SearchContext.nearest]); the vectors of millions of windows in a graph of the index were minutes
 * of random reads a search on the disk of the data (2026-11-25).
 *
 * THE WEIGHT OF A FIELD IS IN ITS NAME — `<marks>.<weight in hundredths>.<key>`, the marks `n` of a field that names
 * the item, `c` of the work's own words ([SearchField.counted]), `t` of neither — so a search reads which fields there
 * are and what each weighs from the index itself, whatever source wrote them.
 *
 * EVERY TEXT STEMMED BEFORE IT IS PUT IN, ONCE, as written in its language ([TextLanguages], [Stemmers]) — one field of
 * it, no copy by another language (user, 2026-10-16): every word as its stem at its place, marked when written with a
 * capital ([Stemmers.CAPITAL]).
 *
 * MADE ANEW BESIDE THE OLD ONE: a rebuild writes into a folder of its own while the searches use the index as it was,
 * then takes its place; what is updated meanwhile goes into both.
 *
 * KEPT BETWEEN THE RUNS (user, 2026-10-17): every item's document carries the fingerprint of what it was made of — the
 * application's ([SearchDocument.fingerprint]), else its own ([DocumentPrints]) — the index the version of how its
 * documents are made ([FORMAT]); on a start the fingerprints are held against those kept and only the documents of the
 * items whose fingerprint changed made and written again ([sync]): not every document made, its chapters read, only to
 * be found the same. An index of another version is made anew.
 */
class LuceneIndex(private val dir: Path) : AutoCloseable {

    private val lock = ReentrantLock()
    private var writer: IndexWriter = open(dir)
    private var searchers = SearcherManager(writer, SearcherFactory())

    /** The writer of a rebuild in progress: updates go into it too. */
    private var building: IndexWriter? = null

    /**
     * Whether the index is of this [FORMAT] and its last commit is of a [rebuild] that ended: every item's document
     * written since by [update] — an index rebuilt empty is current, its documents written by the application's passes
     * as they go.
     */
    fun current(): Boolean = lock.withLock {
        DirectoryReader.open(writer).use { reader ->
            val data = reader.indexCommit.userData
            data[FORMAT_KEY] == FORMAT.toString() && data[WHOLE_KEY] == true.toString()
        }
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
                next.updateDocuments(idTerm(it.key), documents(it, DocumentPrints.of(it)))
                count++
            }
            commit(next, whole = true)
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
     * The items of [fingerprints] brought into the index, those [removed] taken out: the documents made ([documents])
     * of those whose fingerprint is not the one the index keeps alone — a chance or an answer that changed nothing a
     * document is made of makes none —, every one while a rebuild is under way, which may hold them older. The
     * fingerprints read and the documents made out of the lock: a search meanwhile reads the index as it is. How many
     * documents were written.
     */
    fun update(
        fingerprints: Map<ItemKey, Long>,
        documents: (Collection<ItemKey>) -> List<SearchDocument>,
        removed: Collection<ItemKey>,
    ): Int {
        val rebuilding = lock.withLock { building != null }
        val kept =
            if (rebuilding) emptyMap()
            else search { IndexKept.fingerprints(it, fingerprints.keys.map { k -> k.toString() }) }
        val changed = fingerprints.filter { (key, print) -> kept[key.toString()] != print }.keys
        return write(if (changed.isEmpty()) emptyList() else documents(changed), removed)
    }

    /**
     * The [documents] written, those [removed] taken out. A document of the fingerprint the index keeps is not written
     * again, save while a rebuild is under way. Each written one made once, for the index and the one being built
     * alike. How many were written.
     */
    private fun write(documents: List<SearchDocument>, removed: Collection<ItemKey>): Int = lock.withLock {
        val kept =
            if (building == null) search { IndexKept.fingerprints(it, documents.map { d -> d.key.toString() }) }
            else emptyMap()
        val written = documents.mapNotNull { doc ->
            val fingerprint = DocumentPrints.of(doc)
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
        written.size
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

    /** The text fields of the index with their weights. */
    fun fields(searcher: IndexSearcher): Fields {
        val names = FieldInfos.getMergedFieldInfos(searcher.indexReader).map { it.name }
        return Fields(names.mapNotNull(::textField).sortedByDescending { it.weight })
    }

    class Fields(val text: List<IndexedField>) {
        /** Of the fields searched alone: not those of weight 0, the work's own words kept for the counting of names. */
        fun searched() = Fields(text.filter { it.weight > 0f })
    }

    /** How many items the index has. */
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

        /** What a document is: an item's fields ([ITEM]), the one kind there is. */
        private const val KIND = "kind"
        private const val ITEM = "item"

        /** The languages an item's texts were stemmed as, by a comma. */
        const val LANGUAGES = "languages"

        /** What an item's document was made of ([DocumentPrints]): written again when it changes. */
        const val FINGERPRINT = "fingerprint"

        /** The stored fields an item's fingerprint is read with. */

        /**
         * The version of how the documents are made — the fields, the stemming, the marks — kept with every commit: an
         * index of another is made anew. 2: every text stemmed as its language, the paragraphs of the work's own words
         * marked, the fingerprints kept; 3: the fingerprints the application's, of what a document is made of; 4: no
         * passage and no vector, the search by meaning the application's.
         */
        const val FORMAT = 4
        private const val FORMAT_KEY = "format"

        private const val WHOLE_KEY = "whole"

        /**
         * Commits [w] with the version of the index ([FORMAT]) and whether it was brought to every item: [whole] of the
         * end of a [sync] or a [rebuild]; else as the commit before it said.
         */
        private fun commit(w: IndexWriter, whole: Boolean? = null) {
            val before = w.liveCommitData?.associate { it.key to it.value }.orEmpty()
            val made = whole?.toString() ?: before[WHOLE_KEY] ?: false.toString()
            w.setLiveCommitData(mapOf(FORMAT_KEY to FORMAT.toString(), WHOLE_KEY to made).entries)
            w.commit()
        }

        /** What an item's document is: the term of the documents of items, and their query. */
        val ITEM_TERM = Term(KIND, ITEM)
        val ITEMS = TermQuery(ITEM_TERM)

        /** The languages the own words of [doc] are stemmed as ([TextLanguages]), in order. */
        fun languagesOf(doc: SearchDocument): List<String> =
            doc.fields
                .flatMap { f -> f.values.filter { it.isNotBlank() }.map { TextLanguages.of(it, f, doc) } }
                .distinct()
                .sorted()

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

        /** The documents of an item: of its fields; [print] — the item's fingerprint ([DocumentPrints]), made once. */
        private fun documents(doc: SearchDocument, print: Long): List<Document> {
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
                                val language = TextLanguages.of(value, field, doc).also { used += it }
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
            return listOf(fields)
        }
    }
}

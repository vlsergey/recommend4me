package io.github.vlsergey.recommend4me.search

import io.github.vlsergey.recommend4me.item.ItemKey
import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.analysis.LowerCaseFilter
import org.apache.lucene.analysis.TokenStream
import org.apache.lucene.analysis.en.EnglishPossessiveFilter
import org.apache.lucene.analysis.en.PorterStemFilter
import org.apache.lucene.analysis.miscellaneous.ASCIIFoldingFilter
import org.apache.lucene.analysis.ru.RussianLightStemFilter
import org.apache.lucene.analysis.standard.StandardTokenizer
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute
import org.apache.lucene.document.Document
import org.apache.lucene.document.Field
import org.apache.lucene.document.KnnFloatVectorField
import org.apache.lucene.document.StringField
import org.apache.lucene.document.TextField
import org.apache.lucene.index.FieldInfos
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.index.Term
import org.apache.lucene.index.VectorSimilarityFunction
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.SearcherFactory
import org.apache.lucene.search.SearcherManager
import org.apache.lucene.store.FSDirectory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.io.path.exists

/**
 * The index of one content type on disk: a document per item, a field per [SearchField] of it and
 * a vector field per text vector.
 *
 * THE WEIGHT OF A FIELD IS IN ITS NAME — `t.<weight in hundredths>.<key>`, `n.` for a field that
 * names the item — so a search reads which fields there are and what each weighs from the index
 * itself, whatever source wrote them. Vectors are `v.<key>`.
 *
 * MADE ANEW BESIDE THE OLD ONE: a rebuild writes into a folder of its own while the searches use
 * the index as it was, then takes its place; what is updated meanwhile goes into both.
 */
class LuceneIndex(private val dir: Path) : AutoCloseable {

    private val lock = ReentrantLock()
    private var writer: IndexWriter = open(dir)
    private var searchers = SearcherManager(writer, SearcherFactory())

    /** The writer of a rebuild in progress: updates go into it too. */
    private var building: IndexWriter? = null

    /** Rebuilds the index from [documents]; searches use the old one until it is done. */
    fun rebuild(documents: Sequence<SearchDocument>): Int {
        val fresh = dir.resolveSibling(dir.fileName.toString() + ".new")
        fresh.toFile().deleteRecursively()
        val next = open(fresh)
        lock.withLock { building = next }
        var count = 0
        try {
            documents.forEach {
                next.updateDocument(idTerm(it.key), document(it))
                count++
            }
            next.commit()
        } catch (e: Exception) {
            lock.withLock { building = null }
            next.close()
            fresh.toFile().deleteRecursively()
            throw e
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

    fun update(documents: List<SearchDocument>, removed: Collection<ItemKey>) = lock.withLock {
        listOfNotNull(writer, building).forEach { w ->
            documents.forEach { w.updateDocument(idTerm(it.key), document(it)) }
            removed.forEach { w.deleteDocuments(idTerm(it)) }
        }
        writer.commit()
        searchers.maybeRefresh()
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

    /** The text fields of the index with their weights, and its vector fields. */
    fun fields(searcher: IndexSearcher): Fields {
        val names = FieldInfos.getMergedFieldInfos(searcher.indexReader).map { it.name }
        val text = names.mapNotNull(::textField).sortedByDescending { it.weight }
        val vectors = names.filter { it.startsWith(VECTOR) }.map { it.removePrefix(VECTOR) }
        return Fields(text, vectors)
    }

    class Fields(val text: List<IndexedField>, val vectors: List<String>)

    /** A text field of the index: its name there, its key in the documents, its weight, whether it names the item. */
    data class IndexedField(val name: String, val key: String, val weight: Float, val naming: Boolean)

    override fun close() = lock.withLock {
        searchers.close()
        writer.close()
    }

    companion object {
        const val ID = "id"
        private const val VECTOR = "v."

        /**
         * Words, lower case, accents folded, English endings stemmed and irregular verbs brought to
         * their base — "clothes" and "clothing", "steals", "stealing" and "stolen" are one word
         * each — and Russian endings taken off ("драконы", "драконов" and "дракон" are one).
         */
        val analyzer: Analyzer = object : Analyzer() {
            override fun createComponents(fieldName: String): TokenStreamComponents {
                val source = StandardTokenizer()
                val english = PorterStemFilter(EnglishPossessiveFilter(ASCIIFoldingFilter(LowerCaseFilter(source))))
                return TokenStreamComponents(source, RussianLightStemFilter(IrregularVerbs.filter(english)))
            }

            override fun normalize(fieldName: String, `in`: TokenStream): TokenStream = ASCIIFoldingFilter(LowerCaseFilter(`in`))

            // Two tags, two attributes are not one phrase
            override fun getPositionIncrementGap(fieldName: String): Int = VALUE_GAP
        }

        /** The positions between two values of one field — two tags — farther than any phrase reaches. */
        const val VALUE_GAP = 100

        fun tokens(text: String): List<String> {
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
            (if (field.naming) "n." else "t.") + Math.round(field.weight * 100) + "." + field.key

        private fun textField(name: String): IndexedField? {
            if (!name.startsWith("t.") && !name.startsWith("n.")) return null
            val parts = name.split('.', limit = 3)
            if (parts.size < 3) return null
            val weight = parts[1].toIntOrNull() ?: return null
            return IndexedField(name, parts[2], weight / 100f, parts[0] == "n")
        }

        fun vectorField(key: String) = VECTOR + key

        private fun idTerm(key: ItemKey) = Term(ID, key.toString())

        private fun open(dir: Path): IndexWriter {
            if (!dir.exists()) Files.createDirectories(dir)
            return IndexWriter(FSDirectory.open(dir), IndexWriterConfig(analyzer).setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND))
        }

        private fun document(doc: SearchDocument): Document = Document().apply {
            add(StringField(ID, doc.key.toString(), Field.Store.YES))
            doc.fields.forEach { field ->
                val name = fieldName(field)
                field.values.filter { it.isNotBlank() }.forEach { add(TextField(name, it, Field.Store.NO)) }
            }
            doc.vectors.forEach { v ->
                // A text of no phrase — "-", a changelog of dates — says nothing, and its vector lies
                // near every query: the same few items were found by meaning whatever was looked for
                val text = doc.fields.firstOrNull { it.key == v.field }?.values?.joinToString("\n")
                if (text != null && Phrases.split(text).isEmpty()) return@forEach
                add(KnnFloatVectorField(vectorField(v.field), unit(v.vector), VectorSimilarityFunction.DOT_PRODUCT))
            }
        }

        /** A dot product needs unit vectors; the stored ones are, up to the rounding of a float. */
        fun unit(v: FloatArray): FloatArray {
            var n = 0.0
            for (x in v) n += x.toDouble() * x
            n = kotlin.math.sqrt(n)
            return if (n > 1e-9) FloatArray(v.size) { (v[it] / n).toFloat() } else v
        }
    }
}

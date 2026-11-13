package io.github.vlsergey.recommend4me.search

import org.apache.lucene.document.Document
import org.apache.lucene.index.LeafReader
import org.apache.lucene.index.Term
import org.apache.lucene.search.DocIdSetIterator
import org.apache.lucene.search.IndexSearcher

/**
 * WHAT THE INDEX KEEPS OF THE ITEMS' DOCUMENTS, read by their ids: the fingerprint of what a document was made of
 * ([LuceneIndex.FINGERPRINT]) and the languages its texts were stemmed as ([LuceneIndex.LANGUAGES]) — of the items'
 * documents alone, a passage keeps neither.
 */
internal object IndexKept {

    /** The fingerprints of the items' documents of [ids], by id, as the [searcher] reads them. */
    fun fingerprints(searcher: IndexSearcher, ids: List<String>): Map<String, Long> =
        kept(searcher, ids, PRINT_FIELDS) { it.getField(LuceneIndex.FINGERPRINT)?.numericValue()?.toLong() }

    /** The languages the texts of the items' documents of [ids] were stemmed as ([TextLanguages]), by id. */
    fun languages(searcher: IndexSearcher, ids: List<String>): Map<String, List<String>> =
        kept(searcher, ids, LANGUAGE_FIELDS) { d ->
            d.get(LuceneIndex.LANGUAGES)?.split(',')?.filter { it.isNotBlank() }
        }

    /**
     * What [read] reads of the stored [fields] of the items' documents of [ids], by id; of an item whose document has
     * none of it, nothing.
     */
    private fun <T : Any> kept(
        searcher: IndexSearcher,
        ids: List<String>,
        fields: Set<String>,
        read: (Document) -> T?,
    ): Map<String, T> {
        val out = HashMap<String, T>()
        for (leaf in searcher.indexReader.leaves()) ids.forEach { id ->
            keptIn(leaf.reader(), id, fields, read)?.let { out[id] = it }
        }
        return out
    }

    /** What [read] reads of the item's live document of [id] in the segment [r]; null when it has none. */
    private fun <T : Any> keptIn(r: LeafReader, id: String, fields: Set<String>, read: (Document) -> T?): T? {
        val docs = r.postings(Term(LuceneIndex.ID, id)) ?: return null
        val live = r.liveDocs
        val stored = r.storedFields()
        var kept: T? = null
        var doc = docs.nextDoc()
        while (doc != DocIdSetIterator.NO_MORE_DOCS) {
            if (live == null || live.get(doc)) read(stored.document(doc, fields))?.let { kept = it }
            doc = docs.nextDoc()
        }
        return kept
    }

    private val PRINT_FIELDS = setOf(LuceneIndex.ID, LuceneIndex.FINGERPRINT)
    private val LANGUAGE_FIELDS = setOf(LuceneIndex.LANGUAGES)
}

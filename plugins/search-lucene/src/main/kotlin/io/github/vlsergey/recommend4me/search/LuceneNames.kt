package io.github.vlsergey.recommend4me.search

import io.github.vlsergey.recommend4me.item.ItemKey
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute
import org.apache.lucene.analysis.tokenattributes.PayloadAttribute
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute

/**
 * The names the works' own words say, counted of the Lucene index of their content type ([NameReading]); where a
 * document names them, read again of its texts as they were put in the index.
 */
internal class LuceneNames(private val indexes: LuceneIndexes) : NameCounting {

    /** The names counted of the index of [contentType] for every asking of [asked] ([NameReading]). */
    private fun named(contentType: String, asked: List<NamesAsked>): List<Map<ItemKey, List<FoundIn>>> {
        val index = indexes.of(contentType)
        return index.search { searcher -> NameReading(index, searcher, asked).found() }
    }

    override fun mentions(
        contentType: String,
        items: Collection<ItemKey>?,
        names: Map<String, List<String>>,
    ): Map<ItemKey, Map<String, Int>> = mentions(contentType, listOf(NamesAsked(items, names))).single()

    override fun mentions(contentType: String, asked: List<NamesAsked>): List<Map<ItemKey, Map<String, Int>>> =
        named(contentType, asked).map { byItem ->
            byItem.mapValues { (_, found) -> found.groupingBy { it.value }.eachCount() }
        }

    override fun together(
        contentType: String,
        items: Collection<ItemKey>?,
        names: Map<String, List<String>>,
    ): Map<ItemKey, Map<Pair<String, String>, Int>> = together(contentType, listOf(NamesAsked(items, names))).single()

    override fun together(
        contentType: String,
        asked: List<NamesAsked>,
    ): List<Map<ItemKey, Map<Pair<String, String>, Int>>> =
        named(contentType, asked).map { byItem ->
            byItem
                .mapValues { (_, found) ->
                    val out = HashMap<Pair<String, String>, Int>()
                    found
                        .groupBy { it.field to it.paragraph }
                        .values
                        .forEach { paragraph ->
                            val each = paragraph.map { it.value }.toSortedSet().toList()
                            each.forEachIndexed { k, a ->
                                each.drop(k + 1).forEach { b -> out.merge(a to b, 1, Int::plus) }
                            }
                        }
                    out
                }
                .filterValues { it.isNotEmpty() }
        }

    /**
     * The places of the values [of] in the document's own words, found by the rules of [mentions] over its texts read
     * again as they were put in the index.
     */
    override fun places(
        document: SearchDocument,
        names: Map<String, List<String>>,
        of: Collection<String>,
    ): List<NamePlace> {
        val wanted = of.toHashSet()
        val matcher = NameMatcher(names, LuceneIndex.languagesOf(document))
        return document.fields
            .filter { it.counted }
            .flatMap { field ->
                field.values
                    .filter { it.isNotBlank() }
                    .mapNotNull { text ->
                        val ranges =
                            foundIn(text, document, matcher)
                                .filter { it.value in wanted }
                                .map { it.first.start until it.last.end }
                        if (ranges.isEmpty()) null else NamePlace(field.key, text, ranges)
                    }
            }
    }

    /**
     * The places of the two values [a] and [b] named in one paragraph of the document's own words, as [together] counts
     * them: of every value of a field, the names of both in the paragraphs naming both.
     */
    override fun placesTogether(
        document: SearchDocument,
        names: Map<String, List<String>>,
        a: String,
        b: String,
    ): List<NamePlace> {
        val matcher = NameMatcher(names, LuceneIndex.languagesOf(document))
        return document.fields
            .filter { it.counted }
            .flatMap { field ->
                field.values
                    .filter { it.isNotBlank() }
                    .mapNotNull { text ->
                        // A paragraph ends at a line break, as the marks of the index have it
                        val found = foundIn(text, document, matcher).filter { it.value == a || it.value == b }
                        val breaks = text.indices.filter { text[it] == '\n' }
                        fun paragraphOf(at: Int) = breaks.binarySearch(at).let { if (it >= 0) it else -it - 1 }
                        val ranges =
                            found
                                .groupBy { paragraphOf(it.first.start) }
                                .values
                                .filter { inOne -> inOne.any { it.value == a } && inOne.any { it.value == b } }
                                .flatten()
                                .map { it.first.start until it.last.end }
                        if (ranges.isEmpty()) null else NamePlace(field.key, text, ranges.sortedBy { it.first })
                    }
            }
    }

    private companion object {
        /**
         * The names of a [text] of the document found again, read as it was put in the index: its words with their
         * characters.
         */
        private fun foundIn(text: String, document: SearchDocument, matcher: NameMatcher): List<Found> {
            val words = ArrayList<NamedWord>()
            Stemmers.analyzer(TextLanguages.of(text, document.language)).tokenStream("", text).use { stream ->
                val term = stream.addAttribute(CharTermAttribute::class.java)
                val step = stream.addAttribute(PositionIncrementAttribute::class.java)
                val payload = stream.addAttribute(PayloadAttribute::class.java)
                val offset = stream.addAttribute(OffsetAttribute::class.java)
                stream.reset()
                var position = -1
                while (stream.incrementToken()) {
                    position += step.positionIncrement
                    words +=
                        NamedWord(
                            term.toString(),
                            position,
                            payload.payload == Stemmers.CAPITAL,
                            offset.startOffset(),
                            offset.endOffset(),
                        )
                }
                stream.end()
            }
            return matcher.found(words)
        }
    }
}

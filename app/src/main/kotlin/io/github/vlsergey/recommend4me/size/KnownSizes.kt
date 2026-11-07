package io.github.vlsergey.recommend4me.size

import io.github.vlsergey.recommend4me.contenttype.StandardLanguages
import io.github.vlsergey.recommend4me.contenttype.StandardNumbers
import io.github.vlsergey.recommend4me.correction.FacetCorrection
import io.github.vlsergey.recommend4me.source.FacetRole
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.tag.normalizedTags
import kotlin.concurrent.withLock

/** The number of the source's layer that counts a work's words; null when its site states none. */
internal fun SourceStore.wordsKey() = schema.numbers.firstOrNull { it.standard === StandardNumbers.WORDS }?.key

/** The number of the source's layer that counts a work's characters; null when its site states none. */
internal fun SourceStore.charsKey() = schema.numbers.firstOrNull { it.standard === StandardNumbers.CHARS }?.key

/** Whether a site of the type states a work's size, in words or in characters. */
internal fun TypeStore.hasSizes() = sources.any { it.wordsKey() != null || it.charsKey() != null }

/** What is known of a work's size by every item of it. */
internal class Known {
    val features = HashSet<String>()

    /** The longest text kept of any of its items, counted. */
    var text: Pair<Double, Double>? = null

    /** The words and characters one item states both of. */
    var stated: Pair<Double, Double>? = null

    /** The most words and the most characters any of its items states: a work may stand unfinished on a site. */
    var words = 0.0
    var chars = 0.0

    /** Of its text or of what one site states, none of two sites. */
    fun own(): SizeExample? = (text ?: stated)?.let { SizeExample(features, it.first, it.second) }

    fun acrossSites(): SizeExample? = if (words > 0 && chars > 0) SizeExample(features, words, chars) else null
}

/** What every source of the [type] knows of its works, each source read in one pass. */
internal fun knownSizes(type: TypeStore): Map<Long, Known> {
    val works = HashMap<Long, Known>()
    type.sources.forEach { s ->
        countTexts(s)
        SourceSizes(s, works).read()
    }
    return works
}

/** The parts of the source read and counted at once. */
private const val COUNTED_AT_ONCE = 100

/** Every part whose text is not counted yet counted: one count of a part, kept, however often the model is fitted. */
private fun countTexts(s: SourceStore) {
    while (true) {
        val parts = s.parts.uncounted(COUNTED_AT_ONCE)
        if (parts.isEmpty()) return
        s.writing.withLock {
            parts.forEach { s.parts.setCounted(it.itemId, it.partId, it.content.length, WordCount.of(it.content)) }
        }
    }
}

/** What the source [s] tells of the works its items are bound to, put in [works]. */
private class SourceSizes(private val s: SourceStore, private val works: HashMap<Long, Known>) {
    private val bound = s.works.allOf(s.id)

    private fun of(id: String): Known? = bound[id]?.let { work -> works.getOrPut(work) { Known() } }

    fun read() {
        bound.keys.forEach { of(it)!!.features += WordsPerChar.SOURCE + s.id }
        val corrections = s.corrections.allFacets()
        s.normalizedTags(null, corrections).forEach { (id, tags) ->
            of(id)?.features?.addAll(tags.map { WordsPerChar.TAG + it })
        }
        authors()
        languages(corrections)
        numbers()
        texts()
    }

    /** The authors of every item, normalized: features of its work. */
    private fun authors() {
        val f = s.schema.facets.firstOrNull { it.role == FacetRole.AUTHOR } ?: return
        val authors = s.authors.bindingsOf(s.id)
        s.items.forEachFacet(f.key) { id, keys ->
            keys.forEach { k -> authors[k]?.let { of(id)?.features?.add(WordsPerChar.AUTHOR + it) } }
        }
    }

    /** The languages of every item, the site's and the user's answers: features of its work. */
    private fun languages(corrections: List<FacetCorrection>) {
        s.schema.facets
            .firstOrNull { it.standard === StandardLanguages.LANGUAGE }
            ?.let { f ->
                s.items.forEachFacet(f.key) { id, keys ->
                    keys.mapNotNull(StandardLanguages::code).forEach {
                        of(id)?.features?.add(WordsPerChar.LANGUAGE + it)
                    }
                }
            }
        corrections
            .filter { it.facet == StandardLanguages.KEY && it.added }
            .forEach { of(it.itemId)?.features?.add(WordsPerChar.LANGUAGE + it.key) }
    }

    /** The words and the characters every item's site states: the most of each, and the two of one item. */
    private fun numbers() {
        val words = s.wordsKey()?.let(s.items::number).orEmpty()
        val chars = s.charsKey()?.let(s.items::number).orEmpty()
        words.forEach { (id, n) -> of(id)?.let { it.words = maxOf(it.words, n) } }
        chars.forEach { (id, n) ->
            val k = of(id) ?: return@forEach
            k.chars = maxOf(k.chars, n)
            val w = words[id]
            if (w != null && w > 0 && n > 0) k.stated = w to n
        }
    }

    /** The kept texts of every item, counted: the longest of a work's. */
    private fun texts() {
        s.parts.counted().forEach { (id, count) ->
            val k = of(id) ?: return@forEach
            if (count.first > 0 && count.second > (k.text?.second ?: 0.0)) k.text = count
        }
    }
}

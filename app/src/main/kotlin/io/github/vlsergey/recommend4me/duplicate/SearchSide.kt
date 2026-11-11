package io.github.vlsergey.recommend4me.duplicate

import io.github.vlsergey.recommend4me.author.AuthorKin
import io.github.vlsergey.recommend4me.contenttype.StandardNumbers
import io.github.vlsergey.recommend4me.matrix.Matrix
import io.github.vlsergey.recommend4me.source.NumberRole
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.TextRole
import io.github.vlsergey.recommend4me.textvector.TextVectors
import io.github.vlsergey.recommend4me.universe.UniverseFacet
import io.github.vlsergey.recommend4me.universe.UniverseFacets
import io.github.vlsergey.recommend4me.vector.Vectors

/** The facet the normalized authors are compared as. */
internal const val AUTHOR_FAMILY = "author"

/**
 * The facet a facet of the layer's values are compared as: the characters of either facet of them are one — a main one
 * on a site, a secondary one on another.
 */
internal fun familyOf(facet: String) = if (facet in UniverseFacet.CHARACTER_FACETS) UniverseFacets.CHARACTERS else facet

/**
 * The works of one site as the search reads them: those with a vector of their title, its unit rows ([titles], one row
 * a work), and of their descriptions ([descriptions], a row of noughts and [described] false of a work without one);
 * the values of the facets the sites share ([values], by their places among every value of the type); the numbers of
 * its size the sites share ([sizes], by the standard number, nought when not known); and every work's normalized
 * authors ([authors]). Handed to the model of pairs as its [works], weighed once every site is read.
 */
internal class SearchSide(
    val store: SourceStore,
    val ids: List<String>,
    val titles: Matrix,
    val descriptions: Matrix,
    val described: BooleanArray,
    val values: Array<IntArray>,
    val sizes: Map<String, DoubleArray>,
    val authors: Array<LongArray>,
) {
    /**
     * The works as the model of pairs reads them ([SiteWorks]): of every work the weight of each of its [values] — the
     * value's [rarity] among every work of the type, the work's values of each facet of unit length —, the facets it
     * has a value of (a bit a facet, by the places of [familyOf]), its numbers of size by the [sizeKeys] (nought of one
     * the site has not), and with its authors every author the dictionary gives as one with them ([kin]).
     */
    fun works(rarity: FloatArray, familyOf: IntArray, sizeKeys: List<String>, kin: AuthorKin): SiteWorks {
        val weights =
            Array(values.size) { i ->
                val w = FloatArray(values[i].size) { k -> rarity[values[i][k]] }
                val norms = HashMap<Int, Double>()
                values[i].forEachIndexed { k, v -> norms.merge(familyOf[v], w[k].toDouble() * w[k], Double::plus) }
                values[i].forEachIndexed { k, v ->
                    norms[familyOf[v]]?.let { n -> if (n > 0) w[k] /= Math.sqrt(n).toFloat() }
                }
                w
            }
        return SiteWorks(
            store.id,
            ids,
            titles,
            descriptions,
            described,
            values,
            weights,
            LongArray(values.size) { i -> values[i].fold(0L) { bits, v -> bits or (1L shl familyOf[v]) } },
            Array(sizeKeys.size) { k -> sizes[sizeKeys[k]] ?: DoubleArray(ids.size) },
            authors,
            Array(ids.size) { i -> authors[i].flatMap { kin.of(it) }.distinct().toLongArray() },
        )
    }

    companion object {
        /** The roles of the numbers of how a site's readers met a work — its likes, its views —: not the work's own. */
        private val SITE_ROLES = setOf(NumberRole.APPROVAL, NumberRole.REACH)

        /**
         * The side of the source by the [encoder]'s vectors, read as they go, and its works' values, sizes and authors;
         * null when it has none. Its values by their names, given their places once every site is read ([ReadSide]):
         * the sites are read side by side.
         */
        fun read(s: SourceStore, encoder: String, ratios: Map<Long, Double>): ReadSide? {
            val description = s.schema.texts.firstOrNull { it.role == TextRole.DESCRIPTION }?.key
            val ids = s.textVectors.itemsWith(TextVectors.TITLE)
            if (ids.isEmpty()) return null
            val at = ids.withIndex().associate { it.value to it.index }
            var titles: Matrix? = null
            var descriptions: Matrix? = null
            val described = BooleanArray(ids.size)
            s.textVectors.forEach(encoder, listOfNotNull(TextVectors.TITLE, description)) { id, key, vector ->
                val i = at[id] ?: return@forEach
                if (titles == null) {
                    titles = Matrix(ids.size, vector.size)
                    descriptions = Matrix(ids.size, vector.size)
                }
                if (vector.size != titles!!.cols) return@forEach
                val unit = Vectors.unit(vector)
                if (key == TextVectors.TITLE) titles!!.held.put(unit, 0, titles!!.row(i), unit.size)
                else {
                    descriptions!!.held.put(unit, 0, descriptions!!.row(i), unit.size)
                    described[i] = true
                }
            }
            return titles?.let {
                val values = WorkValues(s).apply { read() }
                val authors = values.authorsOf(ids)
                values.gather(ids, authors)
                val sizes = sizesOf(s, ids, ratios)
                ReadSide(values.named(ids)) { placed ->
                    SearchSide(s, ids, it, descriptions!!, described, placed, sizes, authors)
                }
            }
        }

        /**
         * The numbers of the size of every work the sites share — a standard number, not how the site's readers met it
         * — by its key; the words of a work of its characters alone, or the characters of its words alone, worked out
         * by the [ratios] of the works: so two sites compare, whichever they state.
         */
        private fun sizesOf(s: SourceStore, ids: List<String>, ratios: Map<Long, Double>): Map<String, DoubleArray> {
            val at = ids.withIndex().associate { it.value to it.index }
            val out =
                s.schema.numbers
                    .filter { it.standard != null && it.standard!!.role !in SITE_ROLES }
                    .associateTo(HashMap()) { n ->
                        val numbers = DoubleArray(ids.size)
                        s.items.number(n.key).forEach { (id, v) -> at[id]?.let { numbers[it] = v } }
                        n.standard!!.key to numbers
                    }
            val bound = s.works.allOf(s.id)
            val words = out.getOrPut(StandardNumbers.WORDS.key) { DoubleArray(ids.size) }
            val chars = out.getOrPut(StandardNumbers.CHARS.key) { DoubleArray(ids.size) }
            ids.forEachIndexed { i, id ->
                val ratio = bound[id]?.let { ratios[it] } ?: return@forEachIndexed
                if (words[i] <= 0 && chars[i] > 0) words[i] = chars[i] * ratio
                if (chars[i] <= 0 && words[i] > 0) chars[i] = words[i] / ratio
            }
            return out
        }
    }
}

/**
 * A site's [side] read, the values of its works by their names ([named]): given their places among every value of the
 * type once every site is read, one after another, so a value has one place whichever site read it first.
 */
internal class ReadSide(
    private val named: Array<Collection<String>>,
    private val side: (values: Array<IntArray>) -> SearchSide,
) {
    /** The side, its values by their places in [places], every new value given one. */
    fun placed(places: HashMap<String, Int>): SearchSide =
        side(Array(named.size) { i -> named[i].map { places.getOrPut(it) { places.size } }.sorted().toIntArray() })
}

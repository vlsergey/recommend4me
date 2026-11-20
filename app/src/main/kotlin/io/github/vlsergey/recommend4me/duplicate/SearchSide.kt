package io.github.vlsergey.recommend4me.duplicate

import io.github.vlsergey.recommend4me.author.AuthorKin
import io.github.vlsergey.recommend4me.contenttype.StandardNumbers
import io.github.vlsergey.recommend4me.correction.Corrected
import io.github.vlsergey.recommend4me.matrix.ByteMatrix
import io.github.vlsergey.recommend4me.matrix.Rows
import io.github.vlsergey.recommend4me.model.FeatureNames
import io.github.vlsergey.recommend4me.source.NumberDef
import io.github.vlsergey.recommend4me.source.NumberRole
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.TextRole
import io.github.vlsergey.recommend4me.universe.UniverseFacet
import io.github.vlsergey.recommend4me.universe.UniverseFacets
import io.github.vlsergey.recommend4me.vector.Vectors
import io.github.vlsergey.recommend4me.worklist.ListIndex
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(SearchSide::class.java)

/** The facet the normalized authors are compared as. */
internal const val AUTHOR_FAMILY = "author"

/**
 * The facet a facet of the layer's values are compared as: the characters of either facet of them are one — a main one
 * on a site, a secondary one on another.
 */
internal fun familyOf(facet: String) = if (facet in UniverseFacet.CHARACTER_FACETS) UniverseFacets.CHARACTERS else facet

/**
 * The works of one site as the search reads them: every work of a title — its letters' rows ([titles], [titleLengths]:
 * [Letters]), its authors' names' ([authorNames], [authorLengths]: nought of a work of no author), and the vectors of
 * their descriptions ([descriptions], a row of noughts and [described] false of a work without one, of no column a site
 * of no vector of a description); the values of the facets the sites share ([values], by their places among every value
 * of the type); the numbers of its size the sites share ([sizes], by the standard number, nought when not known); and
 * every work's normalized authors ([authors]). Handed to the model of pairs as its [works], weighed once every site is
 * read.
 */
internal class SearchSide(
    val store: SourceStore,
    val ids: List<String>,
    val titles: Rows,
    val titleLengths: FloatArray,
    val authorNames: Rows,
    val authorLengths: FloatArray,
    val descriptions: Rows,
    val described: BooleanArray,
    val values: Array<IntArray>,
    val sizes: Map<String, DoubleArray>,
    val authors: Array<LongArray>,
    val texts: TextsCompared,
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
            titleLengths,
            authorNames,
            authorLengths,
            descriptions,
            described,
            values,
            weights,
            LongArray(values.size) { i -> values[i].fold(0L) { bits, v -> bits or (1L shl familyOf[v]) } },
            Array(sizeKeys.size) { k -> sizes[sizeKeys[k]] ?: DoubleArray(ids.size) },
            authors,
            Array(ids.size) { i -> authors[i].flatMap { kin.of(it) }.distinct().toLongArray() },
            texts.titleVectors,
            texts.titled,
            texts.letters,
            texts.letterLengths,
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
        fun read(s: SourceStore, encoder: String, ratios: Map<Long, Double>, index: ListIndex): ReadSide? {
            val started = System.currentTimeMillis()
            // Every work of a title, ascending, as the user corrected it, its values, authors and sizes: of the index
            // of
            // the list, not of the databases (user, 2026-11-18)
            val numbers = sizeNumbers(s)
            val compared = ComparedValues(s, index).every(numbers.keys)
            val ids = compared.ids
            if (ids.isEmpty()) return null
            val read = System.currentTimeMillis()
            // Its letters, not a vector of its meaning
            val (titles, titleLengths) = Letters.rows(compared.titles)
            val (authorNames, authorLengths) = Letters.rows(compared.authorNames)
            val lettered = System.currentTimeMillis()
            val (descriptions, described) = descriptionsOf(s, encoder, ids)
            val texts = TextsCompared.read(s, encoder, ids)
            val vectors = System.currentTimeMillis()
            val sizes = sizesOf(ids, ratios, numbers, { compared.numbers[it] }, s.works.allOf(s.id))
            log.info(
                "{}: the search's side of {} works read in {} ms: the index {}, the letters {}, vectors {}, sizes {}",
                s.id,
                ids.size,
                System.currentTimeMillis() - started,
                read - started,
                lettered - read,
                vectors - lettered,
                System.currentTimeMillis() - vectors,
            )
            return ReadSide(compared.numbered, compared.names) { placed ->
                SearchSide(
                    s,
                    ids,
                    titles,
                    titleLengths,
                    authorNames,
                    authorLengths,
                    descriptions,
                    described,
                    placed,
                    sizes,
                    compared.authors,
                    texts,
                )
            }
        }

        /**
         * The vectors of the descriptions of the works [ids] by the [encoder], unit rows a byte a number (ByteMatrix),
         * and which have one: of no column, a site of none.
         */
        private fun descriptionsOf(s: SourceStore, encoder: String, ids: List<String>): Pair<ByteMatrix, BooleanArray> {
            val described = BooleanArray(ids.size)
            val description =
                s.schema.texts.firstOrNull { it.role == TextRole.DESCRIPTION }?.key
                    ?: return ByteMatrix(ids.size, 0) to described
            val at = ids.withIndex().associate { it.value to it.index }
            var rows: ByteMatrix? = null
            s.textVectors.forEach(encoder, listOf(description)) { id, _, vector ->
                val i = at[id] ?: return@forEach
                val into = rows ?: ByteMatrix(ids.size, vector.size).also { rows = it }
                if (vector.size != into.cols) return@forEach
                into.put(i, Vectors.unit(vector))
                described[i] = true
            }
            return (rows ?: ByteMatrix(ids.size, 0)) to described
        }

        /**
         * The side of the works [ids] of the source alone, read as every work of it is ([read]): what a pair no search
         * kept is compared by as it is shown ([LoosePairs]). Of the works there alone; null when none is.
         */
        fun readOf(
            s: SourceStore,
            encoder: String,
            ids: List<String>,
            ratios: Map<Long, Double>,
            index: ListIndex,
        ): ReadSide? {
            val heads = s.items.heads(ids)
            val own = ids.filter { it in heads }.distinct().sorted()
            if (own.isEmpty()) return null
            val fields = s.corrections.fieldsOf(own)
            val (titles, titleLengths) =
                Letters.rows(own.map { Corrected.title(heads.getValue(it).title, fields[it].orEmpty()) })
            val description = s.schema.texts.firstOrNull { it.role == TextRole.DESCRIPTION }?.key
            val vectors = description?.let { s.textVectors.ofMany(own, encoder, listOf(it)) }.orEmpty()
            val width = vectors.values.firstNotNullOfOrNull { it[description] }?.size ?: 0
            val descriptions = ByteMatrix(own.size, width)
            val described = BooleanArray(own.size)
            own.forEachIndexed { i, id ->
                vectors[id]
                    ?.get(description)
                    ?.takeIf { it.size == width }
                    ?.let { v ->
                        descriptions.put(i, Vectors.unit(v))
                        described[i] = true
                    }
            }
            val numbers = sizeNumbers(s)
            val compared = ComparedValues(s, index).of(own, numbers.keys)
            val (authorNames, authorLengths) = Letters.rows(compared.authorNames)
            val sizes = sizesOf(own, ratios, numbers, { compared.numbers[it] }, s.works.ofItems(s.id, own))
            val texts = TextsCompared.read(s, encoder, own)
            return ReadSide(compared.numbered, compared.names) { placed ->
                SearchSide(
                    s,
                    own,
                    titles,
                    titleLengths,
                    authorNames,
                    authorLengths,
                    descriptions,
                    described,
                    placed,
                    sizes,
                    compared.authors,
                    texts,
                )
            }
        }

        /**
         * The numbers of the size the sites share of the source — a standard number, not how the site's readers met a
         * work — by the id the index of the list keeps them by.
         */
        private fun sizeNumbers(s: SourceStore): Map<String, NumberDef> =
            s.schema.numbers
                .filter { it.standard != null && it.standard!!.role !in SITE_ROLES }
                .associateBy { FeatureNames.numberId(s.source, it) }

        /**
         * The numbers of the size of every work the sites share, of the [numbers] by their ids ([numberOf] of an id
         * every work's), by the standard number's key; the words of a work of its characters alone, or the characters
         * of its words alone, worked out by the [ratios] of the works: so two sites compare, whichever they state.
         */
        private fun sizesOf(
            ids: List<String>,
            ratios: Map<Long, Double>,
            numbers: Map<String, NumberDef>,
            numberOf: (id: String) -> DoubleArray?,
            bound: Map<String, Long>,
        ): Map<String, DoubleArray> {
            val out =
                numbers.entries.associateTo(HashMap()) { (id, n) ->
                    n.standard!!.key to (numberOf(id)?.copyOf() ?: DoubleArray(ids.size))
                }
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
 * A site's [side] read, the values of its works by the site's own numbers ([numbered], ascending) of their [names]:
 * given their places among every value of the type once every site is read, one after another, so a value has one place
 * whichever site read it first — a name looked up once a value, not once a work; two numbers of one name (a main and a
 * secondary character) one place.
 */
internal class ReadSide(
    private val numbered: Array<IntArray>,
    private val names: List<String?>,
    private val side: (values: Array<IntArray>) -> SearchSide,
) {
    /** The side, its values by their places in [places], every new value given one. */
    fun placed(places: HashMap<String, Int>): SearchSide {
        val place = IntArray(names.size) { k -> names[k]?.let { places.getOrPut(it) { places.size } } ?: -1 }
        return side(
            Array(numbered.size) { i ->
                numbered[i].map { place[it] }.filter { it >= 0 }.distinct().sorted().toIntArray()
            }
        )
    }
}

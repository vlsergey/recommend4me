package io.github.vlsergey.recommend4me.duplicate

import io.github.vlsergey.recommend4me.author.AuthorKin
import io.github.vlsergey.recommend4me.contenttype.StandardNumbers
import io.github.vlsergey.recommend4me.correction.Corrected
import io.github.vlsergey.recommend4me.matrix.Rows
import io.github.vlsergey.recommend4me.model.FeatureNames
import io.github.vlsergey.recommend4me.part.CharClasses
import io.github.vlsergey.recommend4me.part.PartCounts
import io.github.vlsergey.recommend4me.size.KeptRatios
import io.github.vlsergey.recommend4me.source.NumberDef
import io.github.vlsergey.recommend4me.source.NumberRole
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.universe.UniverseFacet
import io.github.vlsergey.recommend4me.universe.UniverseFacets
import io.github.vlsergey.recommend4me.worklist.ListIndex
import java.util.stream.IntStream
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
    /** Every work's title as the user corrected it, by its place. */
    val titleTexts: List<String>,
) {
    /**
     * The works as the model of pairs reads them ([SiteWorks]): of every work the weight of each of its [values] — the
     * value's [rarity] among every work of the type, the work's values of each facet of unit length —, the facets it
     * has a value of (a bit a facet, by the places of [familyOf]), its numbers of size by the [sizeKeys] (nought of one
     * the site has not), and with its authors every author the dictionary gives as one with them ([kin]).
     */
    fun works(rarity: FloatArray, familyOf: IntArray, sizeKeys: List<String>, kin: AuthorKin): SiteWorks {
        // A work on a core, its facets' lengths summed in a buffer of the core's: no map a work (2026-11-20)
        val families = (familyOf.maxOrNull() ?: -1) + 1
        val buffer = ThreadLocal.withInitial { DoubleArray(families) }
        val made = arrayOfNulls<FloatArray>(values.size)
        IntStream.range(0, values.size).parallel().forEach { i ->
            val own = values[i]
            val norms = buffer.get()
            val w = FloatArray(own.size) { k -> rarity[own[k]] }
            for (k in own.indices) norms[familyOf[own[k]]] += w[k].toDouble() * w[k]
            for (k in own.indices) norms[familyOf[own[k]]].let { n -> if (n > 0) w[k] /= Math.sqrt(n).toFloat() }
            for (v in own) norms[familyOf[v]] = 0.0
            made[i] = w
        }
        val weights = Array(values.size) { made[it]!! }
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
            Array(ids.size) { i -> kin.ofAll(authors[i]) },
            texts.titleVectors,
            texts.titled,
            texts.letters,
            texts.letterLengths,
        )
    }

    companion object {
        /**
         * The ids the index of the list keeps the counts of an item's kept texts by: its words, its characters, its
         * characters by their classes ([PartCounts]) — what the model of the sizes weighs a work by, read of the index.
         */
        private val COUNTS =
            listOf(PartCounts.WORDS_ID, PartCounts.CHARS_ID) + CharClasses.NAMES.indices.map(PartCounts::classId)

        /** Of the works [c] read, the words and the characters of those whose texts are counted. */
        private fun countedOf(c: ComparedValues.Compared): Map<String, Pair<Double, Double>> {
            val words = c.numbers[PartCounts.WORDS_ID]
            val chars = c.numbers[PartCounts.CHARS_ID] ?: return emptyMap()
            val out = HashMap<String, Pair<Double, Double>>()
            c.ids.forEachIndexed { i, id -> if (chars[i] > 0) out[id] = (words?.get(i) ?: 0.0) to chars[i] }
            return out
        }

        /** Of the works [c] read, the counts of the classes of the characters of those whose texts are counted. */
        private fun classesOf(c: ComparedValues.Compared): Map<String, IntArray> {
            val columns = CharClasses.NAMES.indices.map { c.numbers[PartCounts.classId(it)] }
            val out = HashMap<String, IntArray>()
            c.ids.forEachIndexed { i, id ->
                val own = IntArray(columns.size) { k -> columns[k]?.get(i)?.toInt() ?: 0 }
                if (own.any { it > 0 }) out[id] = own
            }
            return out
        }

        /** The roles of the numbers of how a site's readers met a work — its likes, its views —: not the work's own. */
        private val SITE_ROLES = setOf(NumberRole.APPROVAL, NumberRole.REACH)

        /**
         * The side of the source by the [encoder]'s vectors, read as they go, and its works' values, sizes and authors;
         * null when it has none. Its values by their names, given their places once every site is read ([ReadSide]):
         * the sites are read side by side.
         */
        fun read(s: SourceStore, encoder: String, model: KeptRatios, index: ListIndex): ReadSide? {
            val started = System.currentTimeMillis()
            // Every work of a title, ascending, as the user corrected it, its values, authors and sizes: of the index
            // of
            // the list, not of the databases (user, 2026-11-18)
            val numbers = sizeNumbers(s)
            val compared = ComparedValues(s, index).every(numbers.keys + COUNTS)
            val ids = compared.ids
            if (ids.isEmpty()) return null
            val read = System.currentTimeMillis()
            // Its letters, not a vector of its meaning
            val (titles, titleLengths) = Letters.rows(compared.titles)
            val (authorNames, authorLengths) = Letters.rows(compared.authorNames)
            val lettered = System.currentTimeMillis()
            val texts = TextsCompared.read(s, encoder, ids, index)
            val vectors = System.currentTimeMillis()
            val sizes =
                sizesOf(ids, model.of(s.id, compared, { countedOf(compared) }) { classesOf(compared) }, numbers) {
                    compared.numbers[it]
                }
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
                    texts.descriptions,
                    texts.described,
                    placed,
                    sizes,
                    compared.authors,
                    texts,
                    compared.titles,
                )
            }
        }

        /**
         * The side of the works [ids] of the source alone, read as every work of it is ([read]): what a pair no search
         * kept is compared by as it is shown ([LoosePairs]). Of the works there alone; null when none is.
         */
        fun readOf(
            s: SourceStore,
            encoder: String,
            ids: List<String>,
            model: KeptRatios,
            index: ListIndex,
        ): ReadSide? {
            val heads = s.items.heads(ids)
            val own = ids.filter { it in heads }.distinct().sorted()
            if (own.isEmpty()) return null
            val fields = s.corrections.fieldsOf(own)
            val titled = own.map { Corrected.title(heads.getValue(it).title, fields[it].orEmpty()) }
            val (titles, titleLengths) = Letters.rows(titled)
            val numbers = sizeNumbers(s)
            val compared = ComparedValues(s, index).of(own, numbers.keys)
            val (authorNames, authorLengths) = Letters.rows(compared.authorNames)
            val sizes =
                sizesOf(
                    own,
                    model.of(s.id, compared, { s.parts.counts.counted(own) }) { s.parts.counts.classes(own) },
                    numbers,
                ) {
                    compared.numbers[it]
                }
            val texts = TextsCompared.read(s, encoder, own, index)
            return ReadSide(compared.numbered, compared.names) { placed ->
                SearchSide(
                    s,
                    own,
                    titles,
                    titleLengths,
                    authorNames,
                    authorLengths,
                    texts.descriptions,
                    texts.described,
                    placed,
                    sizes,
                    compared.authors,
                    texts,
                    titled,
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
         * of its words alone, worked out by the [ratios] of the items by their places: so two sites compare, whichever
         * they state.
         */
        private fun sizesOf(
            ids: List<String>,
            ratios: DoubleArray?,
            numbers: Map<String, NumberDef>,
            numberOf: (id: String) -> DoubleArray?,
        ): Map<String, DoubleArray> {
            val out =
                numbers.entries.associateTo(HashMap()) { (id, n) ->
                    n.standard!!.key to (numberOf(id)?.copyOf() ?: DoubleArray(ids.size))
                }
            val words = out.getOrPut(StandardNumbers.WORDS.key) { DoubleArray(ids.size) }
            val chars = out.getOrPut(StandardNumbers.CHARS.key) { DoubleArray(ids.size) }
            if (ratios != null)
                for (i in ids.indices) {
                    val ratio = ratios[i]
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
                numbered[i].let { own ->
                    val out = IntArray(own.size)
                    var n = 0
                    for (v in own) place[v].let { if (it >= 0) out[n++] = it }
                    ascendingOnce(out, n)
                }
            }
        )
    }
}

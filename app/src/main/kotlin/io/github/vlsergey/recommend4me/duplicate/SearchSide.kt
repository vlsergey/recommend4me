package io.github.vlsergey.recommend4me.duplicate

import io.github.vlsergey.recommend4me.author.AuthorRepository
import io.github.vlsergey.recommend4me.contenttype.StandardNumbers
import io.github.vlsergey.recommend4me.matrix.Floats
import io.github.vlsergey.recommend4me.source.FacetRole
import io.github.vlsergey.recommend4me.source.NumberRole
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.TextRole
import io.github.vlsergey.recommend4me.tag.TagRepository
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
 * a work, [width] numbers), and of their descriptions ([descriptions], a row of noughts and [described] false of a work
 * without one); the values of the facets the sites share ([values], by their places among every value of the type) —
 * weighed once every site is read ([weigh]), and of every value the works that have it with its weight in each; the
 * numbers of its size the sites share ([sizes], by the standard number, nought when not known); and every work's
 * normalized authors ([authors]).
 */
internal class SearchSide(
    val store: SourceStore,
    val ids: List<String>,
    val width: Int,
    val titles: Floats,
    val descriptions: Floats,
    val described: BooleanArray,
    val values: Array<IntArray>,
    val sizes: Map<String, DoubleArray>,
    val authors: Array<LongArray>,
) {
    /** Of every normalized author, the works of this site they are of. */
    val withAuthor: Map<Long, List<Int>> =
        HashMap<Long, MutableList<Int>>().also { out ->
            authors.forEachIndexed { i, own -> own.forEach { out.getOrPut(it) { ArrayList() } += i } }
        }

    /**
     * Of every work, the weight of each of its [values]: the value's rarity among every work of the type, the work's
     * values of each facet of unit length.
     */
    lateinit var weights: Array<FloatArray>

    /** Of every work, the facets it has a value of: a bit a facet, by its place among the type's ([familyOf]). */
    lateinit var facets: LongArray

    /** Of every value of the type, the works of this site that have it ([withValueItems]) and its weight in each. */
    lateinit var withValueItems: Array<IntArray>
    lateinit var withValueWeights: Array<FloatArray>

    /**
     * Its works' values weighed by the [rarity] of every value, every facet's of a work apart, and every value's works
     * laid out.
     */
    fun weigh(rarity: FloatArray, familyOf: IntArray) {
        facets = LongArray(values.size) { i -> values[i].fold(0L) { bits, v -> bits or (1L shl familyOf[v]) } }
        weights =
            Array(values.size) { i ->
                val w = FloatArray(values[i].size) { k -> rarity[values[i][k]] }
                val norms = HashMap<Int, Double>()
                values[i].forEachIndexed { k, v -> norms.merge(familyOf[v], w[k].toDouble() * w[k], Double::plus) }
                values[i].forEachIndexed { k, v ->
                    norms[familyOf[v]]?.let { n -> if (n > 0) w[k] /= Math.sqrt(n).toFloat() }
                }
                w
            }
        val count = IntArray(rarity.size)
        values.forEach { own -> own.forEach { count[it]++ } }
        withValueItems = Array(rarity.size) { IntArray(count[it]) }
        withValueWeights = Array(rarity.size) { FloatArray(count[it]) }
        val filled = IntArray(rarity.size)
        values.forEachIndexed { i, own ->
            own.forEachIndexed { k, t ->
                withValueItems[t][filled[t]] = i
                withValueWeights[t][filled[t]] = weights[i][k]
                filled[t]++
            }
        }
    }

    companion object {
        /** The roles of the numbers of how a site's readers met a work — its likes, its views —: not the work's own. */
        private val SITE_ROLES = setOf(NumberRole.APPROVAL, NumberRole.REACH)

        /**
         * The side of the source by the [encoder]'s vectors, read as they go, and its works' values, sizes and authors;
         * null when it has none.
         */
        fun read(
            s: SourceStore,
            encoder: String,
            places: HashMap<String, Int>,
            ratios: Map<Long, Double>,
        ): SearchSide? {
            val description = s.schema.texts.firstOrNull { it.role == TextRole.DESCRIPTION }?.key
            val ids =
                s.textVectors.keys().filter { it.second == TextVectors.TITLE }.map { it.first }.distinct().sorted()
            if (ids.isEmpty()) return null
            val at = ids.withIndex().associate { it.value to it.index }
            var width = 0
            var titles: Floats? = null
            var descriptions: Floats? = null
            val described = BooleanArray(ids.size)
            s.textVectors.forEach(encoder, listOfNotNull(TextVectors.TITLE, description)) { id, key, vector ->
                val i = at[id] ?: return@forEach
                if (titles == null) {
                    width = vector.size
                    titles = Floats(ids.size * width)
                    descriptions = Floats(ids.size * width)
                }
                if (vector.size != width) return@forEach
                val unit = Vectors.unit(vector)
                if (key == TextVectors.TITLE) titles!!.put(unit, 0, i * width, width)
                else {
                    descriptions!!.put(unit, 0, i * width, width)
                    described[i] = true
                }
            }
            return titles?.let {
                val authors = authorsOf(s, ids)
                SearchSide(
                    s,
                    ids,
                    width,
                    it,
                    descriptions!!,
                    described,
                    WorkValues(s).apply { gather(ids, authors) }.places(ids, places),
                    sizesOf(s, ids, ratios),
                    authors,
                )
            }
        }

        /** The normalized authors of every work of the source, by the places of [ids]. */
        private fun authorsOf(s: SourceStore, ids: List<String>): Array<LongArray> {
            val out = Array(ids.size) { LongArray(0) }
            val at = ids.withIndex().associate { it.value to it.index }
            s.schema.facets
                .firstOrNull { it.role == FacetRole.AUTHOR }
                ?.let { f ->
                    val authors = s.authors.bindingsOf(s.id)
                    s.items.forEachFacet(f.key) { id, keys ->
                        at[id]?.let { i -> out[i] = keys.mapNotNull { authors[it] }.distinct().toLongArray() }
                    }
                }
            return out
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
 * Every work's values of every facet the sites share, as the site and the user give them — no model asked — each
 * "<facet>:<value>": of the facets of the application's layer (the kind, the universes, the characters — the main and
 * the secondary alike —, the pairings, the normalized tags, the languages, the age rating) the site's own, the drafts
 * of the layer, of the tags the site's tags by the normalized ones they are bound to, the tags the user ignores left
 * out, the user's answers over them all; of a site's facet of the standard values every site gives alike (the status,
 * the direction, the ratings the site allows) its values; the normalized authors. Gathered in this order ([gather]),
 * each step over the ones before it.
 */
private class WorkValues(private val s: SourceStore) {
    private val own = HashMap<String, MutableSet<String>>()

    /** The facets of the application's layer. */
    private val layers = s.schema.facets.filter { it.editable }

    private val layerKeys = layers.map { it.key }.toSet()

    private fun add(id: String, prefix: String, key: String) {
        own.getOrPut(id) { HashSet() } += "$prefix:$key"
    }

    /** Every value of every work of the source gathered, the normalized [authors] of the works of [ids] too. */
    fun gather(ids: List<String>, authors: Array<LongArray>) {
        val bound = s.tags.bindingsOf(s.id)
        val ignored = s.ignoredTags()
        siteLayers(bound)
        drafted()
        answered()
        ignored.forEach { (facet, keys) -> keys.forEach { k -> own.values.forEach { it -= "${familyOf(facet)}:$k" } } }
        standard()
        // The authors as the normalized authors they are: one writer's accounts on two sites one value
        ids.forEachIndexed { i, id -> authors[i].forEach { add(id, AUTHOR_FAMILY, AuthorRepository.key(it)) } }
    }

    /** The site's own values of the facets of the layer, and of the tags the site's tags by the normalized [bound]. */
    private fun siteLayers(bound: Map<String, Long>) {
        layers.forEach { f ->
            s.items.forEachFacet(f.key) { id, keys -> keys.forEach { add(id, familyOf(f.key), it) } }
            f.examplesFrom?.let { site ->
                s.items.forEachFacet(site) { id, keys ->
                    keys.forEach { k -> bound[k]?.let { add(id, familyOf(f.key), TagRepository.key(it)) } }
                }
            }
        }
    }

    /** The drafts of the layer. */
    private fun drafted() {
        s.drafts.all().forEach { (id, drafts) ->
            drafts.filter { it.facet in layerKeys }.forEach { add(id, familyOf(it.facet), it.key) }
        }
    }

    /** The user's answers on the facets of the layer, over every value before: added or taken out. */
    private fun answered() {
        s.corrections
            .allFacets()
            .filter { it.facet in layerKeys }
            .forEach { c ->
                val value = "${familyOf(c.facet)}:${c.key}"
                if (c.added) own.getOrPut(c.itemId) { HashSet() } += value else own[c.itemId]?.remove(value)
            }
    }

    /** The values of the site's facets of the standard values every site gives alike. */
    private fun standard() {
        s.schema.facets
            .filter { !it.editable && it.standard?.values != null }
            .forEach { f ->
                s.items.forEachFacet(f.key) { id, keys -> keys.forEach { add(id, f.standard!!.key, it) } }
            }
    }

    /** The values of every work of [ids] by their places in [places], every new value given one. */
    fun places(ids: List<String>, places: HashMap<String, Int>): Array<IntArray> =
        Array(ids.size) { i -> own[ids[i]].orEmpty().map { places.getOrPut(it) { places.size } }.sorted().toIntArray() }
}

package io.github.vlsergey.recommend4me.duplicate

import io.github.vlsergey.recommend4me.author.AuthorRepository
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.model.FeatureNames
import io.github.vlsergey.recommend4me.source.FacetDef
import io.github.vlsergey.recommend4me.source.FacetRole
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.worklist.ListFacetValues
import io.github.vlsergey.recommend4me.worklist.ListIndex
import io.github.vlsergey.recommend4me.worklist.ListModelValues
import io.github.vlsergey.recommend4me.worklist.ListRows
import java.util.stream.IntStream
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(ComparedValues::class.java)

/**
 * The facet a site's facet [def] is compared as: its authors as the normalized authors, a facet of the layer as its
 * family, a facet of the standard values as the standard one; none of a facet not compared.
 */
internal fun comparedAs(def: FacetDef): String? =
    when {
        def.role == FacetRole.AUTHOR -> AUTHOR_FAMILY
        def.editable -> familyOf(def.key)
        else -> def.standard?.takeIf { it.values != null }?.key
    }

/**
 * WHAT THE SEARCH OF DUPLICATES COMPARES OF A SITE'S WORKS, read of the index of the list (user, 2026-11-18: the values
 * the model worked out are compared — the sites' own words are of domains of their own, not alike across the sites): of
 * every facet of the layer and of the standard values the works are filtered by, a work's values as the index keeps
 * them — the site's, the model's kept above its cutoff, the user's word on top — named by the facet they are compared
 * as ([comparedAs]: the main and the secondary characters one), the tags the user ignores left out; and the normalized
 * authors of the site's facet of authors, a value of [AUTHOR_FAMILY] each. No value of the site's own facets: a site's
 * tags or fandoms are its own words, of no other site.
 */
internal class ComparedValues(private val s: SourceStore, private val index: ListIndex) {
    private val compared = ComparedFacets(s)
    private val families = compared.families
    private val ignored = compared.ignored

    private val authorDef = s.schema.facets.firstOrNull { it.role == FacetRole.AUTHOR }
    private val authorFacet = authorDef?.key

    /** The id the index keeps the site's authors by: read of it, not of the database. */
    private val authorId = authorDef?.let { FeatureNames.facetId(s.source, it) }

    /**
     * Every work of a title of the site the index holds, its ids ascending ([Compared.ids]) with their titles as the
     * user corrected them ([Compared.titles]), read in one pass of the index; their numbers of size of the [sizes]
     * numbers. By the works' places throughout, no map by an id (owner, 2026-11-20: the reading of the 1.4 million
     * works of ficbook took eleven seconds, most of them maps of strings and boxed numbers).
     */
    fun every(sizes: Collection<String>): Compared {
        val started = System.currentTimeMillis()
        val rows = ListFacetValues.everyItem(index, s.id, families.keys + listOfNotNull(authorId), sizes, titles = true)
        val kept = rows.ids.indices.filter { !rows.titles[it].isNullOrEmpty() }.toIntArray()
        val values = Array(kept.size) { rows.values[kept[it]] }
        val indexed = System.currentTimeMillis()
        // The authors' keys of every work, of the same reading of the index: its values of the authors' id
        val authorKeys = arrayOfNulls<List<String>>(kept.size)
        authorId?.let { id ->
            val prefix = "$id:"
            val keyOf = arrayOfNulls<String>(rows.names.size)
            rows.names.forEachIndexed { n, name ->
                if (name.startsWith(prefix)) keyOf[n] = name.substring(prefix.length)
            }
            IntStream.range(0, kept.size).parallel().forEach { i ->
                val keys = ArrayList<String>()
                for (v in values[i]) keyOf[v]?.let { keys += it }
                if (keys.isNotEmpty()) authorKeys[i] = keys
            }
        }
        val keyed = System.currentTimeMillis()
        val names = authorFacet?.let(s::names).orEmpty()
        val named = System.currentTimeMillis()
        val compared =
            Compared(
                rows.names,
                values,
                List(kept.size) { rows.ids[kept[it]] },
                authorsOf(authorKeys),
                namesOf(authorKeys, names),
                List(kept.size) { rows.titles[kept[it]]!! },
                rows.numbers.mapValues { (_, all) -> DoubleArray(kept.size) { all[kept[it]] } },
            )
        log.info(
            "{}: the values of {} works read in {} ms: the index {}, the authors {}, their names {}, gathered {}",
            s.id,
            kept.size,
            System.currentTimeMillis() - started,
            indexed - started,
            keyed - indexed,
            named - keyed,
            System.currentTimeMillis() - named,
        )
        return compared
    }

    /** The works [ids] alone, by their keys; their numbers of size of the [sizes] numbers. */
    fun of(ids: List<String>, sizes: Collection<String>): Compared {
        val names = ArrayList<String>()
        val byName = HashMap<String, Int>()
        val read = ListFacetValues.of(index, ids.map { ItemKey(s.id, it) })
        val values =
            Array(ids.size) { i ->
                read[ItemKey(s.id, ids[i])]
                    .orEmpty()
                    .filterKeys { it in families }
                    .flatMap { (facetId, keys) ->
                        keys.map { k ->
                            "$facetId:$k".let { n -> byName.getOrPut(n) { names.size.also { names += n } } }
                        }
                    }
                    .toIntArray()
            }
        val numbers = s.items.numbersOf(ids)
        val sized =
            s.schema.numbers
                .map { FeatureNames.numberId(s.source, it) to it.key }
                .filter { (id, _) -> id in sizes }
                .associate { (id, key) -> id to DoubleArray(ids.size) { numbers[ids[it]]?.get(key) ?: 0.0 } }
        val facets = authorFacet?.let { f -> s.items.facetsOf(ids) }.orEmpty()
        val authorKeys =
            Array(ids.size) { i -> authorFacet?.let { f -> facets[ids[i]]?.get(f) }?.takeIf { it.isNotEmpty() } }
        val authorNames = authorFacet?.let { f -> s.names(f, authorKeys.flatMap { it.orEmpty() }.distinct()) }.orEmpty()
        return Compared(names, values, ids, authorsOf(authorKeys), namesOf(authorKeys, authorNames), emptyList(), sized)
    }

    /**
     * The names of every work's authors — of their keys [authorKeys] by their places, as the site writes them, by their
     * [names] — one after another: what the authors are compared by letter by letter ([Letters]); none of a work of no
     * author.
     */
    private fun namesOf(authorKeys: Array<List<String>?>, names: Map<String, String>): List<String?> {
        val out = arrayOfNulls<String>(authorKeys.size)
        IntStream.range(0, out.size).parallel().forEach { i ->
            out[i] = authorKeys[i]?.joinToString(" ") { names[it] ?: it }
        }
        return out.asList()
    }

    /** The normalized authors of every work, by their places, of the site's authors' keys [authorKeys]. */
    private fun authorsOf(authorKeys: Array<List<String>?>): Array<LongArray> {
        if (authorKeys.all { it == null }) return Array(authorKeys.size) { LongArray(0) }
        val authors = s.authors.bindingsOf(s.id)
        return Array(authorKeys.size) { i ->
            authorKeys[i]?.mapNotNull { authors[it] }?.distinct()?.toLongArray() ?: LongArray(0)
        }
    }

    /**
     * WHAT THE WORKS [ids] HAVE OF WHAT IS COMPARED: their values by the site's numbers ([numbered], ascending) of
     * their [names] — "<the facet compared as>:<value>" —, the authors' too, of the index's [values] by their numbers
     * of its names [read]; their [authors]; their numbers by the id the index keeps them by, by their places
     * ([numbers]: nought of one not known).
     */
    inner class Compared(
        read: List<String>,
        values: Array<IntArray>,
        /** The works, by their places. */
        val ids: List<String>,
        val authors: Array<LongArray>,
        /** Every work's authors' names one after another; none of a work of no author. */
        val authorNames: List<String?>,
        /** Every work's title as the user corrected it, of the index; none of the works read by their ids. */
        val titles: List<String>,
        val numbers: Map<String, DoubleArray>,
    ) {
        /** The name of every value by its number: of a value the user ignores, none. */
        val names: List<String?>

        /** The values of every work of the ids, by their places: by their numbers ascending, each once. */
        val numbered: Array<IntArray>

        init {
            val named = ArrayList<String?>(read.size)
            read.forEach { n ->
                // Of the authors' id none: the authors compared as the normalized authors, below
                named +=
                    families[n.substringBefore(':')]
                        ?.let { family -> "$family:${n.substringAfter(':')}" }
                        ?.takeIf { it !in ignored }
            }
            // Every normalized author a number once, by the author, not by a string a work
            val authorNumber = HashMap<Long, Int>()
            authors.forEach { own ->
                own.forEach { a ->
                    authorNumber.getOrPut(a) {
                        named.size.also { named += "$AUTHOR_FAMILY:${AuthorRepository.key(a)}" }
                    }
                }
            }
            val out = arrayOfNulls<IntArray>(ids.size)
            IntStream.range(0, ids.size).parallel().forEach { i ->
                val own = values[i]
                val all = IntArray(own.size + authors[i].size)
                var k = 0
                for (v in own) if (named[v] != null) all[k++] = v
                for (a in authors[i]) all[k++] = authorNumber.getValue(a)
                out[i] = ascendingOnce(all, k)
            }
            numbered = Array(ids.size) { out[it]!! }
            names = named
        }
    }
}

/** The first [count] numbers of [a] ascending, each once. */
internal fun ascendingOnce(a: IntArray, count: Int): IntArray {
    a.sort(0, count)
    var k = 0
    for (q in 0 until count) if (k == 0 || a[k - 1] != a[q]) a[k++] = a[q]
    return a.copyOf(k)
}

/**
 * THE FACETS A PAIR IS COMPARED BY, of a site ([ComparedValues]): every facet of the layer and of the standard values
 * the works are filtered by, by its id in the index, with the facet it is compared as; the values the user ignores.
 */
internal class ComparedFacets(private val s: SourceStore) {
    /** Of every facet compared, by its id in the index, the facet it is compared as. */
    val families: Map<String, String> =
        s.schema.facets
            .filter { it.filter }
            .mapNotNull { def ->
                comparedAs(def)?.takeIf { it != AUTHOR_FAMILY }?.let { FeatureNames.facetId(s.source, def) to it }
            }
            .toMap()

    /** The values the user ignores, by their names. */
    val ignored: Set<String> =
        s.ignoredTags().flatMapTo(HashSet()) { (facet, keys) -> keys.map { "${familyOf(facet)}:$it" } }

    /**
     * The values compared of the works [ids], by their names, read of the databases as the index is made of them, the
     * models' as the index keeps them ([ListModelValues]) — what a screen of a pair shows, not of the index (user,
     * 2026-11-18: the screens are of the databases, the search alone of the index); the authors apart.
     */
    fun shown(ids: List<String>, index: ListIndex): Map<String, List<String>> {
        val byFacet = ListRows.facetsOf(s, ids, ListModelValues.of(index, s.id, ids))
        return ids.associateWith { id ->
            byFacet
                .flatMap { (facetId, byItem) ->
                    families[facetId]?.let { family -> byItem[id].orEmpty().map { "$family:$it" } }.orEmpty()
                }
                .filter { it !in ignored }
                .distinct()
        }
    }
}

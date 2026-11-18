package io.github.vlsergey.recommend4me.duplicate

import io.github.vlsergey.recommend4me.author.AuthorRepository
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.model.FeatureNames
import io.github.vlsergey.recommend4me.source.FacetDef
import io.github.vlsergey.recommend4me.source.FacetRole
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.worklist.ItemRows
import io.github.vlsergey.recommend4me.worklist.ListFacetValues
import io.github.vlsergey.recommend4me.worklist.ListIndex
import io.github.vlsergey.recommend4me.worklist.ListRows
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

    private val authorFacet = s.schema.facets.firstOrNull { it.role == FacetRole.AUTHOR }?.key

    /**
     * The works [ids] of every work of the site the index holds, read in one pass of it; their numbers of size of the
     * [sizes] numbers.
     */
    fun every(ids: List<String>, sizes: Collection<String>): Compared {
        val started = System.currentTimeMillis()
        val rows = ListFacetValues.everyItem(index, s.id, families.keys, sizes)
        val indexed = System.currentTimeMillis()
        val authorKeys = HashMap<String, List<String>>()
        authorFacet?.let { f -> s.items.forEachFacet(f) { id, keys -> authorKeys[id] = keys } }
        val keyed = System.currentTimeMillis()
        val names = authorFacet?.let(s::names).orEmpty()
        val named = System.currentTimeMillis()
        val compared = Compared(rows, ids, authorsOf(ids, authorKeys), namesOf(ids, authorKeys, names))
        log.info(
            "{}: the values of {} works read in {} ms: the index {}, the authors {}, their names {}, gathered {}",
            s.id,
            ids.size,
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
        val rows = ItemRows()
        ListFacetValues.of(index, ids.map { ItemKey(s.id, it) }).forEach { (key, facets) ->
            rows.values[key.id] =
                facets
                    .filterKeys { it in families }
                    .flatMap { (facetId, keys) -> keys.map { rows.numberOf("$facetId:$it") } }
                    .toIntArray()
        }
        val numbers = s.items.numbersOf(ids)
        s.schema.numbers.forEach { n ->
            val id = FeatureNames.numberId(s.source, n)
            if (id in sizes)
                numbers.forEach { (item, of) -> of[n.key]?.let { rows.numbers.getOrPut(id) { HashMap() }[item] = it } }
        }
        val facets = authorFacet?.let { f -> s.items.facetsOf(ids).mapValues { it.value[f].orEmpty() } }.orEmpty()
        val names = authorFacet?.let { f -> s.names(f, facets.values.flatten().distinct()) }.orEmpty()
        return Compared(rows, ids, authorsOf(ids, facets), namesOf(ids, facets, names))
    }

    /**
     * The names of every work's authors of [ids] — as the site writes them, by their [names] — one after another: what
     * the authors are compared by letter by letter ([Letters]); none of a work of no author.
     */
    private fun namesOf(
        ids: List<String>,
        authorKeys: Map<String, List<String>>,
        names: Map<String, String>,
    ): List<String?> = ids.map { id ->
        authorKeys[id]?.takeIf { it.isNotEmpty() }?.joinToString(" ") { names[it] ?: it }
    }

    /** The normalized authors of every work of [ids], by their places, of the site's authors' keys [authorKeys]. */
    private fun authorsOf(ids: List<String>, authorKeys: Map<String, List<String>>): Array<LongArray> {
        if (authorKeys.isEmpty()) return Array(ids.size) { LongArray(0) }
        val authors = s.authors.bindingsOf(s.id)
        return Array(ids.size) { i ->
            authorKeys[ids[i]].orEmpty().mapNotNull { authors[it] }.distinct().toLongArray()
        }
    }

    /**
     * WHAT THE WORKS [ids] HAVE OF WHAT IS COMPARED: their values by the site's numbers ([numbered], ascending) of
     * their [names] — "<the facet compared as>:<value>" —, the authors' too; their [authors]; their numbers by the id
     * the index keeps them by, then the work ([numbers]).
     */
    inner class Compared(
        rows: ItemRows,
        ids: List<String>,
        val authors: Array<LongArray>,
        /** Every work's authors' names one after another; none of a work of no author. */
        val authorNames: List<String?>,
    ) {
        val numbers: Map<String, Map<String, Double>> = rows.numbers

        /** The name of every value by its number: of a value the user ignores, none. */
        val names: List<String?>

        /** The values of every work of the ids, by their places: by their numbers ascending, each once. */
        val numbered: Array<IntArray>

        init {
            val named = ArrayList<String?>(rows.names.size)
            rows.names.forEach { n ->
                named +=
                    "${families.getValue(n.substringBefore(':'))}:${n.substringAfter(':')}".takeIf { it !in ignored }
            }
            val byName = HashMap<String, Int>()
            fun author(a: Long): Int =
                byName.getOrPut("$AUTHOR_FAMILY:${AuthorRepository.key(a)}") {
                    named.size.also { named += "$AUTHOR_FAMILY:${AuthorRepository.key(a)}" }
                }
            numbered =
                Array(ids.size) { i ->
                    val own = rows.values[ids[i]] ?: IntArray(0)
                    (own.filter { named[it] != null } + authors[i].map(::author)).distinct().sorted().toIntArray()
                }
            names = named
        }
    }
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
     * The values compared of the works [ids], by their names, read of the databases as the index is made of them — what
     * a screen of a pair shows, not of the index (user, 2026-11-18: the screens are of the databases, the search alone
     * of the index); the authors apart.
     */
    fun shown(ids: List<String>): Map<String, List<String>> {
        val byFacet = ListRows.facetsOf(s, ids)
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

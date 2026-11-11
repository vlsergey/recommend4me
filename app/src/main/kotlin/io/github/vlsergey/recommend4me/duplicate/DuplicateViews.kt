package io.github.vlsergey.recommend4me.duplicate

import io.github.vlsergey.recommend4me.api.model.DuplicatePair
import io.github.vlsergey.recommend4me.api.model.DuplicateValue
import io.github.vlsergey.recommend4me.api.model.DuplicateWork
import io.github.vlsergey.recommend4me.api.model.DuplicateWorkFacet
import io.github.vlsergey.recommend4me.author.AuthorKin
import io.github.vlsergey.recommend4me.correction.Corrected
import io.github.vlsergey.recommend4me.i18n.I18nKeys
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.size.WorkSizes
import io.github.vlsergey.recommend4me.source.FacetDef
import io.github.vlsergey.recommend4me.source.FacetRole
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.TextRole
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.textvector.TextVectors
import io.github.vlsergey.recommend4me.universe.UniverseFacet
import io.github.vlsergey.recommend4me.vector.Vectors
import org.springframework.stereotype.Component

/** A pair with what ranks it: its chances of one work and of one series, none of an answered pair not kept. */
internal class Ranked(
    val pair: Pair<ItemKey, ItemKey>,
    val chance: Double?,
    val seriesChance: Double?,
    val priority: Double,
)

/**
 * A work as a pair shows it, its normalized authors — what "the same author" is told by —, and the unit vectors of its
 * title and its description as they are: what how alike two works' texts are is measured by, as the pair is shown.
 */
internal class Described(
    val work: DuplicateWork,
    val authors: Set<Long>,
    val title: FloatArray? = null,
    val description: FloatArray? = null,
)

/**
 * THE PAIRS AND THEIR WORKS AS THE USER IS SHOWN THEM: every source's works read in one go, and every pair compared
 * field by field as it is shown — of no search: the search tells which pairs to show and how likely each is one work,
 * the comparison of its fields is of the works as they are now.
 */
@Component
class DuplicateViews(private val sizes: WorkSizes, private val plugins: Plugins) {

    /** The pairs as the user is shown them: a pair of a work no longer there, none. */
    internal fun pairs(
        type: TypeStore,
        said: Said,
        rows: List<Ranked>,
    ): List<DuplicatePair> {
        val described = describe(type, rows.flatMap { listOf(it.pair.first, it.pair.second) })
        val kin = AuthorKin(type.authors.told())
        return rows.mapNotNull { r ->
            val first = described[r.pair.first] ?: return@mapNotNull null
            val second = described[r.pair.second] ?: return@mapNotNull null
            DuplicatePair(
                first = first.work,
                second = second.work,
                chance = r.chance?.toBigDecimal(),
                seriesChance = r.seriesChance?.toBigDecimal(),
                priority = r.priority.toBigDecimal(),
                title = cosine(first.title, second.title)?.toBigDecimal(),
                description = cosine(first.description, second.description)?.toBigDecimal(),
                sameAuthor = kin.share(first.authors, second.authors),
                answer = said[r.pair]?.let { DuplicatePair.Answer.valueOf(it.name) },
                derived = (r.pair in said.derived).takeIf { it },
            )
        }
    }

    /** The cosine of two unit vectors; none when either is not there. */
    private fun cosine(a: FloatArray?, b: FloatArray?): Double? =
        if (a == null || b == null || a.size != b.size) null else a.indices.sumOf { a[it].toDouble() * b[it] }

    /** The works as the pairs show them: each source's read in one go; a work no longer there, none. */
    internal fun describe(type: TypeStore, keys: List<ItemKey>): Map<ItemKey, Described> {
        val out = HashMap<ItemKey, Described>()
        keys
            .distinct()
            .groupBy { it.source }
            .forEach { (source, own) ->
                val s = type.source(source) ?: return@forEach
                val ids = own.map { it.id }
                val shown = ShownWorks(type, s, ids, sizes, plugins.textEncoder()?.id)
                ids.forEach { id -> shown.described(id)?.let { out[ItemKey(source, id)] = it } }
            }
        return out
    }
}

/**
 * The facet a site's facet [def] is compared as ([WorkValues]): its authors as the normalized authors, a facet of the
 * layer as its family, a facet of the standard values as the standard one; none of a facet not compared.
 */
private fun comparedAs(def: FacetDef): String? =
    when {
        def.role == FacetRole.AUTHOR -> AUTHOR_FAMILY
        def.editable -> familyOf(def.key)
        else -> def.standard?.takeIf { it.values != null }?.key
    }

/** What the pairs show of the works [ids] of one source, read in one go. */
private class ShownWorks(
    type: TypeStore,
    private val s: SourceStore,
    ids: List<String>,
    sizes: WorkSizes,
    encoder: String?,
) {
    private val heads = s.items.heads(ids)
    private val fields = s.corrections.fieldsOf(ids)
    private val description = s.schema.texts.firstOrNull { it.role == TextRole.DESCRIPTION }?.key

    /** The vectors of the titles and the descriptions as they are, by the [encoder]; none without one. */
    private val vectors =
        encoder?.let { s.textVectors.ofMany(ids, it, listOfNotNull(TextVectors.TITLE, description)) }.orEmpty()

    // The site's own line of the characters and pairings, shown after the description, folded with it
    private val universeLine = s.schema.universeLine
    private val texts =
        listOfNotNull(description, universeLine).let { wanted ->
            if (wanted.isEmpty()) emptyMap() else s.items.textsOf(ids, wanted)
        }
    private val authorFacet = s.schema.facets.firstOrNull { it.role == FacetRole.AUTHOR }?.key
    private val fandomFacet = UniverseFacet.fandomFacet(s.schema)
    private val facets = s.items.facetsOf(ids)
    private val authorNames = namesOf(authorFacet)
    private val normalized = s.authors.bindingsOf(s.id)
    private val fandomNames = namesOf(fandomFacet)

    // The values the search compared, of these works alone: shown by the facet they are compared as, the authors apart
    private val compared =
        WorkValues(s, ids).apply {
            read()
            gather(ids, Array(ids.size) { LongArray(0) })
        }
    private val values = ids.zip(compared.named(ids)).toMap()

    /**
     * The facet every facet compared is named by, in the order of the site's facets: the layer's own facet of it (the
     * main characters of the characters), the site's facet of the standard values.
     */
    private val namedBy =
        LinkedHashMap<String, FacetDef>().apply {
            s.schema.facets.forEach { def ->
                val family = comparedAs(def)?.takeIf { it != AUTHOR_FAMILY } ?: return@forEach
                if (!def.editable || family == def.key) putIfAbsent(family, def)
            }
        }

    /**
     * The facets of the site whose values are those a work may have one of: the site's word of a facet of one value.
     */
    private val allowed = s.schema.facets.filter { it.single }.mapNotNull { it.originalFacet }.toSet()

    /** The values compared of every work, by the facet compared: "universe" to its keys. */
    private val byFamily = values.mapValues { (_, own) ->
        own.groupBy({ it.substringBefore(':') }, { it.substringAfter(':') })
    }

    /** The names of the values compared, by the facet compared and the key. */
    private val valueNames = namedBy.mapValues { (family, def) ->
        s.names(def.key, byFamily.values.flatMap { it[family].orEmpty() }.distinct())
    }
    private val itemSizes = sizes.ofItems(type, s, ids, s.items.numbersOf(ids), fields)

    // The normalized work of every item: a word on a pair is on the two works, the screen settles every pair of them
    private val works = s.works.ofItems(s.id, ids)

    /** The names of the values of the [facet] the works have, by their keys; none of no such facet. */
    private fun namesOf(facet: String?): Map<String, String> =
        facet?.let { f -> s.names(f, facets.values.flatMap { it[f].orEmpty() }.distinct()) }.orEmpty()

    /** The values of the [facet] the work [id] has, as their [names] name them. */
    private fun named(id: String, facet: String, names: Map<String, String>): List<String> =
        facets[id]?.get(facet).orEmpty().map { names[it] ?: it }

    /** The work [id] as a pair shows it; null when it is no longer there. */
    fun described(id: String): Described? {
        val head = heads[id] ?: return null
        val written = fields[id].orEmpty()
        val authors = authorFacet?.let { f -> facets[id]?.get(f) }.orEmpty()
        val shown = Corrected.texts(texts[id].orEmpty(), written)
        return Described(
            DuplicateWork(
                source = s.id,
                item = id,
                work = works[id],
                title = Corrected.title(head.title, written),
                url = head.url,
                authors = authors.map { authorNames[it] ?: it },
                fandoms = fandomFacet?.let { named(id, it, fandomNames) }.orEmpty(),
                description = description?.let { shown[it] },
                universeLine = universeLine?.let { shown[it] }?.takeIf { it.isNotBlank() },
                facets = comparedOf(id),
                words = itemSizes[id]?.words,
                chars = itemSizes[id]?.chars,
            ),
            authors.mapNotNull { normalized[it] }.toSet(),
            vectors[id]?.get(TextVectors.TITLE)?.let(Vectors::unit),
            description?.let { d -> vectors[id]?.get(d)?.let(Vectors::unit) },
        )
    }

    /** The values of the work [id] of every facet compared it has, named, by their names. */
    private fun comparedOf(id: String): List<DuplicateWorkFacet> = namedBy.mapNotNull { (family, def) ->
        byFamily[id]?.get(family)?.let { keys ->
            DuplicateWorkFacet(
                key = family,
                labelKey = I18nKeys.facetLabel(s.id, def),
                propertyValues =
                    keys
                        .map { k ->
                            DuplicateValue(
                                valueNames[family]?.get(k) ?: compared.given["$family:$k"] ?: k,
                                s.nameKey(def.key, k),
                            )
                        }
                        .sortedBy { it.name },
                allowed = (def.key in allowed).takeIf { it },
            )
        }
    }
}

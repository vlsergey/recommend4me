package io.github.vlsergey.recommend4me.duplicate

import io.github.vlsergey.recommend4me.api.model.DuplicateFacet
import io.github.vlsergey.recommend4me.api.model.DuplicatePair
import io.github.vlsergey.recommend4me.api.model.DuplicateStatus
import io.github.vlsergey.recommend4me.api.model.DuplicateWork
import io.github.vlsergey.recommend4me.author.AuthorKin
import io.github.vlsergey.recommend4me.correction.Corrected
import io.github.vlsergey.recommend4me.i18n.I18nKeys
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.size.WorkSizes
import io.github.vlsergey.recommend4me.source.FacetRole
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.TextRole
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.universe.UniverseFacet
import org.springframework.stereotype.Component

/** A pair with what ranks it: its layers, none of an answered pair not kept. */
internal class Ranked(
    val pair: Pair<ItemKey, ItemKey>,
    val layers: DuplicateCandidate?,
    val chance: Double?,
    val priority: Double,
)

/** A work as a pair shows it, and its normalized authors: what "the same author" is told by. */
internal class Described(val work: DuplicateWork, val authors: Set<Long>)

/** THE PAIRS AND THEIR WORKS AS THE USER IS SHOWN THEM: every source's works read in one go. */
@Component
class DuplicateViews(private val sizes: WorkSizes) {

    /** The pairs as the user is shown them: a pair of a work no longer there, none. */
    internal fun pairs(
        type: TypeStore,
        said: Map<Pair<ItemKey, ItemKey>, DuplicateAnswer>,
        rows: List<Ranked>,
    ): List<DuplicatePair> {
        val labels = familyLabels(type)
        val described = describe(type, rows.flatMap { listOf(it.pair.first, it.pair.second) })
        val kin = AuthorKin(type.authors.told())
        return rows.mapNotNull { r ->
            val first = described[r.pair.first] ?: return@mapNotNull null
            val second = described[r.pair.second] ?: return@mapNotNull null
            val d = r.layers
            DuplicatePair(
                first = first.work,
                second = second.work,
                chance = r.chance?.toBigDecimal(),
                priority = r.priority.toBigDecimal(),
                title = d?.title?.toBigDecimal(),
                description = d?.description?.toBigDecimal(),
                propertySize = d?.size?.toBigDecimal(),
                facets =
                    d?.facets
                        .orEmpty()
                        .entries
                        .sortedByDescending { it.value }
                        .map { (family, v) ->
                            DuplicateFacet(
                                key = family,
                                labelKey = labels[family] ?: family,
                                `value` = v.toBigDecimal(),
                            )
                        },
                sameAuthor = kin.share(first.authors, second.authors),
                answer = said[r.pair]?.let { DuplicatePair.Answer.valueOf(it.name) },
            )
        }
    }

    /** How every facet compared is named: the label of the facet of any source of the type that is it. */
    private fun familyLabels(type: TypeStore): Map<String, String> {
        val labels = HashMap<String, String>()
        type.sources.forEach { s ->
            s.schema.facets.forEach { def ->
                val family =
                    when {
                        def.role == FacetRole.AUTHOR -> AUTHOR_FAMILY
                        def.editable -> familyOf(def.key)
                        else -> def.standard?.takeIf { it.values != null }?.key
                    }
                if (family != null && (!def.editable || family == def.key))
                    labels.putIfAbsent(family, I18nKeys.facetLabel(s.id, def))
            }
        }
        return labels
    }

    /** The works as the pairs show them: each source's read in one go; a work no longer there, none. */
    internal fun describe(type: TypeStore, keys: List<ItemKey>): Map<ItemKey, Described> {
        val out = HashMap<ItemKey, Described>()
        keys
            .distinct()
            .groupBy { it.source }
            .forEach { (source, own) ->
                val s = type.source(source) ?: return@forEach
                val ids = own.map { it.id }
                val shown = ShownWorks(type, s, ids, sizes)
                ids.forEach { id -> shown.described(id)?.let { out[ItemKey(source, id)] = it } }
            }
        return out
    }
}

/** What the pairs show of the works [ids] of one source, read in one go. */
private class ShownWorks(type: TypeStore, private val s: SourceStore, ids: List<String>, sizes: WorkSizes) {
    private val heads = s.items.heads(ids)
    private val fields = s.corrections.fieldsOf(ids)
    private val description = s.schema.texts.firstOrNull { it.role == TextRole.DESCRIPTION }?.key

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
    private val statusFacet = s.schema.facets.firstOrNull { it.role == FacetRole.STATUS }?.key
    private val statusNames = namesOf(statusFacet)
    private val itemSizes = sizes.ofItems(type, s, ids, s.items.numbersOf(ids), fields)

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
                title = Corrected.title(head.title, written),
                url = head.url,
                authors = authors.map { authorNames[it] ?: it },
                fandoms = fandomFacet?.let { named(id, it, fandomNames) }.orEmpty(),
                description = description?.let { shown[it] },
                universeLine = universeLine?.let { shown[it] }?.takeIf { it.isNotBlank() },
                status =
                    statusFacet?.let { f ->
                        facets[id]?.get(f).orEmpty().map { DuplicateStatus(statusNames[it] ?: it, s.nameKey(f, it)) }
                    },
                words = itemSizes[id]?.words,
                chars = itemSizes[id]?.chars,
            ),
            authors.mapNotNull { normalized[it] }.toSet(),
        )
    }
}

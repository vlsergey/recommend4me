package io.github.vlsergey.recommend4me.duplicate

import io.github.vlsergey.recommend4me.api.model.AuthorKin
import io.github.vlsergey.recommend4me.api.model.DuplicatePair
import io.github.vlsergey.recommend4me.api.model.DuplicateValue
import io.github.vlsergey.recommend4me.api.model.DuplicateWork
import io.github.vlsergey.recommend4me.author.AuthorKin as KinOfAuthors
import io.github.vlsergey.recommend4me.contenttype.StandardTags
import io.github.vlsergey.recommend4me.correction.Corrected
import io.github.vlsergey.recommend4me.i18n.I18nKeys
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.series.SeriesDifferences
import io.github.vlsergey.recommend4me.size.WorkSizes
import io.github.vlsergey.recommend4me.source.FacetDef
import io.github.vlsergey.recommend4me.source.FacetRole
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.TextRole
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.universe.UniverseFacet
import io.github.vlsergey.recommend4me.worklist.ListIndexes
import org.springframework.stereotype.Component

/**
 * A pair with what ranks it: its layers, none of an answered pair not kept; its chances of one work and of one series.
 */
internal class Ranked(
    val pair: Pair<ItemKey, ItemKey>,
    val layers: DuplicateCandidate?,
    val chance: Double?,
    val seriesChance: Double?,
    val priority: Double,
)

/** THE PAIRS AND THEIR WORKS AS THE USER IS SHOWN THEM: every source's works read in one go. */
@Component
class DuplicateViews(private val sizes: WorkSizes, private val lists: ListIndexes) {

    /** The pairs as the user is shown them: a pair of a work no longer there, none. */
    internal fun pairs(
        type: TypeStore,
        said: Said,
        rows: List<Ranked>,
    ): List<DuplicatePair> {
        val described = describe(type, rows.flatMap { listOf(it.pair.first, it.pair.second) })
        val kin = KinOfAuthors(type.authors.distinct.told())
        val held = SeriesDifferences.heldBySites(type, rows.map { it.pair })
        return rows.mapNotNull { r ->
            val first = described[r.pair.first] ?: return@mapNotNull null
            val second = described[r.pair.second] ?: return@mapNotNull null
            pairOf(r, first, second, kin.share(first.authors, second.authors), said, null, r.pair in held)
        }
    }

    /**
     * The pair [r] of the works [first] and [second], of one author by the dictionary or not ([sameAuthor]), of no
     * author in common how close their authors are by their works ([kin], the window of one pair alone).
     */
    internal fun pairOf(
        r: Ranked,
        first: Described,
        second: Described,
        sameAuthor: Boolean,
        said: Said,
        kin: AuthorKin?,
        /** The two works held in one series by the sites ([SeriesDifferences.heldBySites]). */
        heldBySites: Boolean,
    ) =
        DuplicatePair(
            first = first.work,
            second = second.work,
            chance = r.chance?.toBigDecimal(),
            seriesChance = r.seriesChance?.toBigDecimal(),
            priority = r.priority.toBigDecimal(),
            sameAuthor = sameAuthor,
            rows = PairFields(first, second, r.layers, sameAuthor, kin).rows(),
            answer = said[r.pair]?.let { DuplicatePair.Answer.valueOf(it.name) },
            derived = (r.pair in said.derived).takeIf { it },
            heldBySites = heldBySites.takeIf { it },
        )

    /** The works as the pairs show them: each source's read in one go; a work no longer there, none. */
    internal fun describe(type: TypeStore, keys: List<ItemKey>): Map<ItemKey, Described> {
        val out = HashMap<ItemKey, Described>()
        keys
            .distinct()
            .groupBy { it.source }
            .forEach { (source, own) ->
                val s = type.source(source) ?: return@forEach
                val ids = own.map { it.id }
                val shown = ShownWorks(type, s, ids, sizes, lists)
                ids.forEach { id -> shown.described(id)?.let { out[ItemKey(source, id)] = it } }
            }
        return out
    }
}

/** What the pairs show of the works [ids] of one source, read in one go. */
private class ShownWorks(
    type: TypeStore,
    private val s: SourceStore,
    ids: List<String>,
    sizes: WorkSizes,
    lists: ListIndexes,
) {
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
    // The normalized authors of these works' authors alone, by their keys: never every binding of the site
    private val normalized =
        authorFacet
            ?.let { f -> s.authors.bindingsOf(s.id, facets.values.flatMap { it[f].orEmpty() }.distinct()) }
            .orEmpty()
    private val fandomNames = namesOf(fandomFacet)

    // The values the search compares, of these works alone, the models' as the index of the list keeps them: shown by
    // the facet they are compared as, the authors apart
    private val values = ComparedFacets(s).shown(ids, lists.of(type.id))

    // The site's own tags, shown by their names, not compared: a site's words, of no other site
    private val tagFacet = s.schema.facets.firstOrNull { it.standard === StandardTags.TAG }?.key
    private val tagNames = namesOf(tagFacet)

    // The names the user gave the values added, by "<the facet compared as>:<value>"
    private val given =
        s.corrections
            .facetsOf(ids)
            .values
            .flatten()
            .filter { it.added && it.name != null }
            .associate { "${familyOf(it.facet)}:${it.key}" to it.name!! }

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
            ),
            authors.mapNotNull { normalized[it] }.toSet(),
            ShownFields(
                authors = authors.map { authorNames[it] ?: it },
                fandoms = fandomFacet?.let { named(id, it, fandomNames) }.orEmpty(),
                description = description?.let { shown[it] },
                universeLine = universeLine?.let { shown[it] }?.takeIf { it.isNotBlank() },
                siteTags =
                    tagFacet?.let { f ->
                        named(id, f, tagNames).map { DuplicateValue(it, null) }.sortedBy { it.name }
                    },
                facets = comparedOf(id),
                words = itemSizes[id]?.words,
                chars = itemSizes[id]?.chars,
            ),
        )
    }

    /** The values of the work [id] of every facet compared it has, named, by their names. */
    private fun comparedOf(id: String): List<ShownFacet> = namedBy.mapNotNull { (family, def) ->
        byFamily[id]?.get(family)?.let { keys ->
            ShownFacet(
                key = family,
                labelKey = I18nKeys.facetLabel(s.id, def),
                values =
                    keys
                        .map { k ->
                            DuplicateValue(
                                valueNames[family]?.get(k) ?: given["$family:$k"] ?: k,
                                s.nameKey(def.key, k),
                            )
                        }
                        .sortedBy { it.name },
                allowed = def.key in allowed,
                single = def.single,
            )
        }
    }
}

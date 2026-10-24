package io.github.vlsergey.recommend4me.marking

import io.github.vlsergey.recommend4me.api.model.MarkVerdict
import io.github.vlsergey.recommend4me.api.model.MarkedWork
import io.github.vlsergey.recommend4me.api.model.MarkedWorks
import io.github.vlsergey.recommend4me.correction.Answering
import io.github.vlsergey.recommend4me.correction.Corrected
import io.github.vlsergey.recommend4me.item.ItemList
import io.github.vlsergey.recommend4me.refusal.Refusal
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.source.TextRole
import io.github.vlsergey.recommend4me.suggestion.FacetSuggestions
import io.github.vlsergey.recommend4me.universe.UniverseFacet
import io.github.vlsergey.recommend4me.universe.UniverseFacets
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * THE CONFIRMING OF THE APPLICATION'S MARKING, value by value: of one value of a facet of the layer
 * — a tag, a character, a pairing — the works that may have it, the likeliest first, and the user's
 * word on each: the work has it (answered so, and learnt from), the work does not say it outright
 * (asked so no more, nothing learnt), the work has it not (answered so, and learnt from).
 */
@Service
class Marking(
    private val stores: Stores,
    private val suggestions: FacetSuggestions,
    private val list: ItemList,
    private val answering: Answering,
) {

    /** The sources whose layer has the [facet] the model works out and the user answers on; refused when none has. */
    private fun sourcesOf(facet: String): List<SourceStore> =
        stores.sources.filter { s -> s.schema.facet(facet)?.let { it.editable && it.infer } == true }.ifEmpty {
            throw Refusal.NO_FIELD.refuse(HttpStatus.BAD_REQUEST, "No source works out the field '$facet'", "field" to facet)
        }

    /**
     * The characters a work must have a universe of to have the [value] of the [facet]: of a
     * character itself, of a pairing its two, the original characters aside — they are of every
     * universe; null of a facet of no characters. A pairing of no two characters is refused.
     */
    private fun charactersOf(facet: String, value: String): List<String>? = when (facet) {
        UniverseFacets.PAIRINGS -> UniverseFacet.members(value)?.toList()
            ?: throw Refusal.NO_VALUE.refuse(HttpStatus.BAD_REQUEST, "'$value' is no pairing of two characters")
        in UniverseFacet.CHARACTER_FACETS -> listOf(value)
        else -> null
    }?.filter { it !in UniverseFacet.ORIGINAL_CHARACTERS }

    /**
     * The works of the source that may have the value: of a value of characters, those with a
     * universe of each of its [characters] — the universes as the works are shown, the site's, the
     * model's and the user's word on top; of any other, every work.
     */
    private fun scope(s: SourceStore, characters: List<String>?, universesOf: Map<String, Set<String>>): Set<String> {
        val every = s.items.keys().map { it.id }.toSet()
        if (characters.isNullOrEmpty()) return every
        val def = s.schema.facet(UniverseFacet.KEY) ?: return emptySet()
        val needed = characters.map { universesOf[it].orEmpty() }
        if (needed.any { it.isEmpty() }) return emptySet()
        val shown = list.valuesOf(s, def, s.corrections.allFacets().groupBy { it.itemId })
        return every.filter { id -> shown[id].orEmpty().toSet().let { own -> needed.all { u -> u.any { it in own } } } }.toSet()
    }

    /** A work of the table: where it is, its chance, the user's word on it. */
    private class Row(val store: SourceStore, val id: String, val chance: Double?, val answer: MarkVerdict?)

    fun works(facet: String, value: String, answered: Boolean, offset: Int, limit: Int): MarkedWorks {
        val sources = sourcesOf(facet)
        val characters = charactersOf(facet, value)
        val universesOf = if (characters.isNullOrEmpty()) emptyMap() else stores.types.first { it.type.universes }.universes.characterUniverses()
        val rows = sources.flatMap { s ->
            val answers = s.corrections.allFacets().filter { it.facet == facet && it.key == value }.associate { it.itemId to if (it.added) MarkVerdict.YES else MarkVerdict.NO }
            val unknown = s.unknowns.ofFacet(facet).filter { it.key == value }.associate { it.itemId to MarkVerdict.UNKNOWN }
            val said = unknown + answers
            val ids = if (answered) said.keys else scope(s, characters, universesOf) - said.keys
            val chances = suggestions.chancesOfValue(s, ids, facet, value)
            ids.map { Row(s, it, chances[it], said[it]) }
        }.sortedWith(compareByDescending<Row> { it.chance ?: -1.0 }.thenBy { it.store.id }.thenBy { it.id })
        val page = rows.drop(offset.coerceAtLeast(0)).take(limit.coerceIn(1, 500))
        return MarkedWorks(total = rows.size, works = described(page))
    }

    /** The rows as the table shows them: each source's works read in one go. */
    private fun described(rows: List<Row>): List<MarkedWork> {
        val out = HashMap<Pair<String, String>, MarkedWork>()
        rows.groupBy { it.store }.forEach { (s, own) ->
            val ids = own.map { it.id }
            val heads = s.items.heads(ids)
            val fields = s.corrections.fieldsOf(ids)
            val description = s.schema.texts.firstOrNull { it.role == TextRole.DESCRIPTION }?.key
            val line = s.schema.universeLine
            val texts = s.items.textsOf(ids, listOfNotNull(description, line))
            // The site's own tags: the facet the layer's tags are learnt from
            val siteTags = s.schema.facets.firstOrNull { it.editable && it.examplesFrom != null }?.examplesFrom
            val facets = if (siteTags == null) emptyMap() else s.items.facetsOf(ids)
            val tagNames = siteTags?.let { f -> s.names(f, facets.values.flatMap { it[f].orEmpty() }.distinct()) }.orEmpty()
            own.forEach { r ->
                val head = heads[r.id] ?: return@forEach
                val written = fields[r.id].orEmpty()
                val shown = Corrected.texts(texts[r.id].orEmpty(), written)
                out[s.id to r.id] = MarkedWork(
                    source = s.id,
                    item = r.id,
                    title = Corrected.title(head.title, written),
                    url = head.url,
                    tags = siteTags?.let { f -> facets[r.id]?.get(f).orEmpty().map { tagNames[it] ?: it } }.orEmpty(),
                    description = description?.let { shown[it] },
                    line = line?.let { shown[it] },
                    chance = r.chance,
                    answer = r.answer,
                )
            }
        }
        return rows.mapNotNull { out[it.store.id to it.id] }
    }

    /** The work of the source, the facet of its layer; refused or none as [works] refuses them. */
    private fun storeOf(source: String, item: String, facet: String, value: String): SourceStore? {
        val store = sourcesOf(facet).firstOrNull { it.id == source }
            ?: throw Refusal.NO_FIELD.refuse(HttpStatus.BAD_REQUEST, "The source $source works out no field '$facet'", "field" to facet)
        charactersOf(facet, value)
        return store.takeIf { it.items.find(item) != null }
    }

    /**
     * The user's word on whether the work has the value: YES — answered so, and of a pairing its two
     * characters (the original ones aside) as the work's main ones; NO — answered so, of a pairing
     * its characters as they are; UNKNOWN — the answer taken back, asked so no more. False when there
     * is no such work.
     */
    fun answer(source: String, item: String, facet: String, value: String, verdict: MarkVerdict): Boolean {
        val store = storeOf(source, item, facet, value) ?: return false
        val had = store.corrections.facetsOf(item).any { it.facet == facet && it.key == value }
        when (verdict) {
            MarkVerdict.YES -> {
                val characters = if (facet == UniverseFacets.PAIRINGS) charactersOf(facet, value).orEmpty() else emptyList()
                answering.answered(store, item, listOf(facet) + (if (characters.isEmpty()) emptyList() else listOf(UniverseFacets.CHARACTERS))) {
                    answering.write(store, item, facet, value, true)
                    characters.forEach { answering.write(store, item, UniverseFacets.CHARACTERS, it, true) }
                    store.unknowns.forget(item, facet, value)
                }
            }
            MarkVerdict.NO -> {
                answering.refuseImplied(store, item, facet, value)
                answering.answered(store, item, listOf(facet)) {
                    answering.write(store, item, facet, value, false)
                    store.unknowns.forget(item, facet, value)
                }
            }
            // Nothing learnt: told only when an answer the models read is taken back
            MarkVerdict.UNKNOWN -> answering.answered(store, item, if (had) listOf(facet) else emptyList()) {
                store.corrections.unsetFacet(item, facet, value)
                store.unknowns.say(item, facet, value, Instant.now())
            }
        }
        return true
    }

    /** The user's word on whether the work has the value taken back, whatever it was. False when there is no such work. */
    fun unanswer(source: String, item: String, facet: String, value: String): Boolean {
        val store = storeOf(source, item, facet, value) ?: return false
        val had = store.corrections.facetsOf(item).any { it.facet == facet && it.key == value }
        answering.answered(store, item, if (had) listOf(facet) else emptyList()) {
            store.corrections.unsetFacet(item, facet, value)
            store.unknowns.forget(item, facet, value)
        }
        return true
    }
}

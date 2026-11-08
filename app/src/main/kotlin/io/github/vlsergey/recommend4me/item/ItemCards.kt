package io.github.vlsergey.recommend4me.item

import io.github.vlsergey.recommend4me.api.model.ItemFacet
import io.github.vlsergey.recommend4me.api.model.ItemRef
import io.github.vlsergey.recommend4me.api.model.ItemSummary
import io.github.vlsergey.recommend4me.api.model.LinkedItem
import io.github.vlsergey.recommend4me.api.model.Prediction
import io.github.vlsergey.recommend4me.api.model.SearchMatch
import io.github.vlsergey.recommend4me.api.model.SiteSaid
import io.github.vlsergey.recommend4me.api.model.TextRange
import io.github.vlsergey.recommend4me.correction.Corrected
import io.github.vlsergey.recommend4me.correction.FacetCorrection
import io.github.vlsergey.recommend4me.rating.Rating
import io.github.vlsergey.recommend4me.source.FacetDef
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.StoredItem
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.suggestion.Chance
import io.github.vlsergey.recommend4me.suggestion.FacetSuggestions
import io.github.vlsergey.recommend4me.suggestion.ModelValues
import io.github.vlsergey.recommend4me.work.TextPlace
import io.github.vlsergey.recommend4me.work.WorkClusters
import io.github.vlsergey.recommend4me.work.Works
import java.time.ZoneOffset
import org.springframework.stereotype.Component

/** The grade of the current version of a work, and the latest grade of an earlier one with its version. */
data class Graded(val grade: Int?, val previousGrade: Int?, val previousVersion: String?)

/**
 * Of items of works shown, every one's own values on its site and its chances of the facets wanted, read once a source
 * — one chain of the models a source for them all, not one an item ([ItemCards.membersRead]).
 */
class MembersRead(
    val facets: Map<ItemKey, Map<String, List<String>>>,
    val chances: Map<ItemKey, Map<String, Map<String, Chance>>>,
)

/** The cards of works as the list shows them, read in batch: one query per table per source. */
@Component
class ItemCards(private val stores: Stores, private val works: Works, private val suggestions: FacetSuggestions) {

    /** The grades of the items [ids] of a source at their [versions]. */
    fun graded(ratings: List<Rating>, versions: Map<String, String>): Map<String, Graded> =
        ratings
            .groupBy { it.itemId }
            .mapNotNull { (id, list) ->
                val version = versions[id] ?: return@mapNotNull null
                val current = list.filter { it.version == version }.maxByOrNull { it.ratedAt }
                val earlier = list.filter { it.version != version }.maxByOrNull { it.ratedAt }
                id to Graded(current?.grade, earlier?.grade, earlier?.version)
            }
            .toMap()

    /**
     * The facets of an item for the interface: every value with its name, the user's word on it, the model's chance of
     * it, and whether the model gave it; [sources] what every site of the work says of a field, for a form to show
     * above it ([ShownFacets]).
     */
    fun facets(
        store: SourceStore,
        work: WorkValues,
        corrections: List<FacetCorrection>,
        names: Map<String, Map<String, String>>,
        sources: Map<String, List<SiteSaid>> = emptyMap(),
        /** The facets shown with no value too: a value can be added to them. */
        empty: (FacetDef) -> Boolean = { false },
        /** The facets the user said the work's values of are all named: none offered besides. */
        complete: Set<String> = emptySet(),
        /**
         * Of values read already, what the catalogue says of them, null of one it says nothing of: facet to key to it.
         */
        described: Map<String, Map<String, String?>> = emptyMap(),
        only: (String) -> Boolean,
    ): List<ItemFacet> {
        val ofTags = store.schema.facets.any { only(it.key) && it.key in store.tagFacets }
        val shown = ShownFacets(store, work, corrections, names, sources, empty, complete, ofTags, described)
        return store.schema.facets.filter { only(it.key) }.mapNotNull(shown::of)
    }

    /**
     * What the sites and the model give the work of the item [key] in the facets the application works out — the
     * universes, the characters, the pairings, the tags, the age rating: of every item of a work of several, over its
     * own [site] values and [chances]; a value given by more than one, by the likeliest. The rest of the facets are the
     * site's own word on the item. The same whichever item of the work is read: the form of a work does not change with
     * the site.
     */
    fun ofWork(
        type: TypeStore,
        clusters: WorkClusters,
        key: ItemKey,
        site: Map<String, List<String>>,
        chances: Map<String, Map<String, Chance>>?,
        wanted: Collection<String>? = null,
        /** The work's other items read already, with those of other works: read here when not. */
        read: MembersRead? = null,
    ): WorkValues {
        val members = clusters.members(key)
        val store = type.source(key.source) ?: return WorkValues(site, chances)
        val layer = store.schema.facets.filter { it.editable && (wanted == null || it.key in wanted) }
        val given = SitesGive()
        // What each item gives is told by its own source: its tags are bound there
        members
            .groupBy { it.source }
            .forEach { (source, items) ->
                type.source(source)?.let { s -> given.read(s, source, items, key, site, layer) }
            }
        val own = WorkValues(site, chances, given.bySite, given.lets)
        val others = members.drop(1)
        return if (others.isEmpty()) own
        else withOthers(store, others, own, wanted, read ?: membersRead(type, others, wanted))
    }

    /**
     * Of the items [members], every one's own values on its site and its chances of the [wanted] facets (all when
     * null): read once a source, one chain of the models for all of its items.
     */
    fun membersRead(type: TypeStore, members: Collection<ItemKey>, wanted: Collection<String>?): MembersRead {
        val facets = HashMap<ItemKey, Map<String, List<String>>>()
        val chances = HashMap<ItemKey, Map<String, Map<String, Chance>>>()
        members
            .groupBy { it.source }
            .forEach { (source, items) ->
                val s = type.source(source) ?: return@forEach
                val ids = items.map { it.id }
                s.items.facetsOf(ids).forEach { (id, f) -> facets[ItemKey(source, id)] = f }
                val made = if (wanted == null) suggestions.chances(s, ids) else suggestions.chancesOf(s, ids, wanted)
                made.forEach { (id, c) -> chances[ItemKey(source, id)] = c }
            }
        return MembersRead(facets, chances)
    }

    /**
     * The values and the chances of the [work] with those of its [others] items added, in the facets the model works
     * out ([wanted] of them, all when null): a value given by more than one, by the likeliest.
     */
    private fun withOthers(
        store: SourceStore,
        others: List<ItemKey>,
        work: WorkValues,
        wanted: Collection<String>?,
        read: MembersRead,
    ): WorkValues {
        val wantedFacets = store.schema.facets.filter { wanted == null || it.key in wanted }
        val worked = wantedFacets.filter { it.infer }.map { it.key }
        val chanced = wantedFacets.filter { it.infer || it.suggest }.map { it.key }
        val values = LinkedHashMap(work.site)
        val likely = HashMap(work.chances.orEmpty())
        others.forEach { m ->
            val theirs = read.facets[m].orEmpty()
            val made = read.chances[m].orEmpty()
            worked.forEach { facet -> theirs[facet]?.let { values[facet] = (values[facet].orEmpty() + it).distinct() } }
            chanced.forEach { facet -> made[facet]?.let { likelier(likely, facet, it) } }
        }
        return WorkValues(values, likely, work.bySite, work.lets)
    }

    /** Of the [facet], each of the chances [made] by another item put in [likely] where likelier than its own. */
    private fun likelier(likely: MutableMap<String, Map<String, Chance>>, facet: String, made: Map<String, Chance>) {
        made.forEach { (value, chance) ->
            val own = likely[facet].orEmpty()
            if ((own[value]?.chance ?: -1.0) < chance.chance) likely[facet] = own + (value to chance)
        }
    }

    /**
     * The cards of [keys] of one content type, in their order; [matches] what a search found in them, [places] where in
     * the work each is ([Search.places][io.github.vlsergey.recommend4me.search.Search.places]). Every source's items
     * are read in one go.
     */
    fun cards(
        type: TypeStore,
        keys: List<ItemKey>,
        matches: Map<ItemKey, io.github.vlsergey.recommend4me.search.SearchMatch> = emptyMap(),
        places: Map<ItemKey, TextPlace> = emptyMap(),
        clusters: WorkClusters = works.of(type),
        /** The user's word on the works for the line searched, when searched. */
        judged: Map<ItemKey, Boolean> = emptyMap(),
        /** Every item of the works of [keys] read already, with the chances of the facets on a card at least. */
        members: MembersRead? = null,
    ): List<ItemSummary> {
        val out = HashMap<ItemKey, ItemSummary>()
        val all = keys.flatMap { clusters.members(it) }.distinct()
        // Every item of the works shown, theirs and their other items', read once a source: the facets of every card
        val onCard =
            keys
                .map { it.source }
                .distinct()
                .flatMap { stores.source(it)?.schema?.facets.orEmpty().filter { f -> f.onCard }.map { f -> f.key } }
                .toSet()
        val read =
            CardsRead(
                type,
                clusters,
                headsOf(all),
                picturesOf(all),
                members ?: membersRead(type, all, onCard),
                matches,
                places,
                judged,
            )
        keys
            .groupBy { it.source }
            .forEach { (source, list) ->
                val store = stores.source(source) ?: return@forEach
                out += SourceCards(store, source, list.map { it.id }, read).cards()
            }
        return keys.mapNotNull { out[it] }
    }

    /** The heads of every member with its title as the user corrected it, to name the linked items. */
    private fun headsOf(members: List<ItemKey>): Map<ItemKey, Pair<StoredItem, String>> =
        members
            .groupBy { it.source }
            .flatMap { (source, list) ->
                val store = stores.source(source) ?: return@flatMap emptyList()
                store.items.heads(list.map { it.id }).map { (id, head) ->
                    ItemKey(source, id) to (head to Corrected.title(head.title, store.corrections.fieldsOf(id)))
                }
            }
            .toMap()

    /**
     * The pictures of every member — how many, and whether there is a cover: the cover of a work is any of its items'.
     */
    private fun picturesOf(members: List<ItemKey>): Map<ItemKey, Pair<Int, Boolean>> =
        members
            .groupBy { it.source }
            .flatMap { (source, list) ->
                stores
                    .source(source)
                    ?.pictures
                    ?.countsOf(list.map { it.id })
                    ?.map { (id, count) -> ItemKey(source, id) to count }
                    .orEmpty()
            }
            .toMap()

    /**
     * What the cards of every source of one request read together: the [heads] and the [pictures] of every member of
     * the works shown, what a search found and the user's word on it.
     */
    private class CardsRead(
        val type: TypeStore,
        val clusters: WorkClusters,
        val heads: Map<ItemKey, Pair<StoredItem, String>>,
        val pictures: Map<ItemKey, Pair<Int, Boolean>>,
        /** Every item of the works shown: its site's values and its chances of the facets on a card. */
        val members: MembersRead,
        val matches: Map<ItemKey, io.github.vlsergey.recommend4me.search.SearchMatch>,
        val places: Map<ItemKey, TextPlace>,
        val judged: Map<ItemKey, Boolean>,
    )

    /** The cards of the items [ids] of one source, every table of it read once for them all. */
    private inner class SourceCards(
        private val store: SourceStore,
        private val source: String,
        private val ids: List<String>,
        private val read: CardsRead,
    ) {
        private val type = read.type
        private val facets = ids.mapNotNull { id -> read.members.facets[ItemKey(source, id)]?.let { id to it } }.toMap()
        private val corrections = store.corrections.facetsOf(ids)
        private val fields = store.corrections.fieldsOf(ids)
        private val onCard = store.schema.facets.filter { it.onCard }.map { it.key }.toSet()
        // The chances of the facets on a card alone, made now of these works with every other item of theirs
        private val chances =
            ids.mapNotNull { id -> read.members.chances[ItemKey(source, id)]?.let { id to it } }.toMap()
        // Of every facet on a card, every value the cards may show: their items' and their other items', given and
        // chanced, and answered on
        private val shownKeys = onCard.associateWith { facet ->
            ids.flatMap { id ->
                    read.clusters.members(ItemKey(source, id)).flatMap { m ->
                        read.members.facets[m]?.get(facet).orEmpty() +
                            ModelValues.named(read.members.chances[m]?.get(facet))
                    } + corrections[id].orEmpty().filter { c -> c.facet == facet }.map { c -> c.key }
                }
                .toSet()
        }
        private val names = shownKeys.mapValues { (facet, keys) -> store.names(facet, keys) }
        // What the catalogue says of them, read once for every card: a value it says nothing of told so, not asked
        // again
        private val described = shownKeys.mapValues { (facet, keys) ->
            store.descriptions(facet, keys).let { d -> keys.associateWith { d[it] } }
        }
        private val numbers = store.items.numbersOf(ids)
        private val ratings = store.ratings.ofItems(ids)
        private val versions =
            ids.mapNotNull { id -> read.heads[ItemKey(source, id)]?.first?.let { id to it.version } }.toMap()
        private val graded = graded(ratings, versions)
        private val predicted = type.predictions.predicted(source, ids)
        private val heldOut = type.heldOut.scores(source, ids)
        private val interest = store.interest.ofItems(ids)
        private val signals = store.signals.all()

        /** The card of every item that has a head, by its key. */
        fun cards(): Map<ItemKey, ItemSummary> =
            ids.mapNotNull { id ->
                    val key = ItemKey(source, id)
                    read.heads[key]?.let { (head, title) -> key to card(key, head, title) }
                }
                .toMap()

        /** The card of the item [key] with its [head] and its [title] as the user corrected it. */
        private fun card(key: ItemKey, head: StoredItem, title: String): ItemSummary {
            val id = key.id
            val g = graded[id]
            val clusters = read.clusters
            val work = ofWork(type, clusters, key, facets[id].orEmpty(), chances[id], onCard, read.members)
            return ItemSummary(
                source = source,
                item = id,
                work = clusters.representative(key).toString(),
                title = title,
                version = head.version,
                url = head.url,
                updatedAt = head.updatedAt.atOffset(ZoneOffset.UTC),
                facets = facets(store, work, corrections[id].orEmpty(), names, described = described) { it in onCard },
                numbers = Corrected.numbers(numbers[id].orEmpty(), fields[id].orEmpty()),
                cover =
                    clusters
                        .members(key)
                        .firstOrNull { read.pictures[it]?.second == true }
                        ?.let { ItemRef(it.source, it.id) },
                pictureCount = read.pictures[key]?.first ?: 0,
                linked =
                    clusters.members(key).drop(1).mapNotNull { m ->
                        read.heads[m]?.let { (h, t) -> LinkedItem(m.source, m.id, t, h.url) }
                    },
                grade = g?.grade,
                previousGrade = g?.previousGrade,
                previousGradeVersion = g?.previousVersion,
                prediction = predicted[id]?.let { Prediction(it.score) },
                heldOut = heldOut[id]?.let { Prediction(it) },
                interest = interest[id]?.interesting,
                interestChance = predicted[id]?.interest,
                searchMatch = searchMatch(key),
                searchRelevant = read.judged[key],
                signals = signals[id]?.takeIf { it.isNotEmpty() },
            )
        }

        /** What a search found in the item [key] and where in the work it is; null when not searched. */
        private fun searchMatch(key: ItemKey): SearchMatch? =
            read.matches[key]?.let { m ->
                val place = read.places[key] ?: TextPlace()
                SearchMatch(
                    byMeaning = m.byMeaning,
                    text = m.text,
                    highlights = m.highlights.map { (s, e) -> TextRange(s, e) },
                    labelKey = place.labelKey,
                    title = place.title,
                    part = place.part,
                )
            }
    }
}

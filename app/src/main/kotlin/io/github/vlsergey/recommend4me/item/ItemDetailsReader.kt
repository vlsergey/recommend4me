package io.github.vlsergey.recommend4me.item

import io.github.vlsergey.recommend4me.api.model.ContributionGroup as ApiContributionGroup
import io.github.vlsergey.recommend4me.api.model.FacetRole as ApiFacetRole
import io.github.vlsergey.recommend4me.api.model.FeatureContribution
import io.github.vlsergey.recommend4me.api.model.ItemDetails
import io.github.vlsergey.recommend4me.api.model.ItemSummary
import io.github.vlsergey.recommend4me.api.model.NumberValue
import io.github.vlsergey.recommend4me.api.model.PartInfo
import io.github.vlsergey.recommend4me.api.model.PictureInfo
import io.github.vlsergey.recommend4me.api.model.RatingRecord
import io.github.vlsergey.recommend4me.api.model.RelatedWorks
import io.github.vlsergey.recommend4me.api.model.ReviewInfo
import io.github.vlsergey.recommend4me.api.model.TextValue
import io.github.vlsergey.recommend4me.api.model.WorkRelation
import io.github.vlsergey.recommend4me.api.model.WorkSite
import io.github.vlsergey.recommend4me.correction.Corrected
import io.github.vlsergey.recommend4me.correction.FacetCorrection
import io.github.vlsergey.recommend4me.i18n.I18nKeys
import io.github.vlsergey.recommend4me.model.ContributionGroup
import io.github.vlsergey.recommend4me.model.Details
import io.github.vlsergey.recommend4me.model.Explanations
import io.github.vlsergey.recommend4me.model.LabelledContribution
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.source.FacetRole
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.source.downloadsPages
import io.github.vlsergey.recommend4me.suggestion.FacetSuggestions
import io.github.vlsergey.recommend4me.suggestion.ModelValues
import io.github.vlsergey.recommend4me.work.WorkClusters
import io.github.vlsergey.recommend4me.work.Works
import java.time.ZoneOffset
import org.springframework.stereotype.Component

fun LabelledContribution.toApi() =
    FeatureContribution(
        feature = contribution.feature,
        labelKey = label.labelKey,
        value = label.value,
        valueKey = label.valueKey,
        contribution = contribution.contribution,
        present = contribution.present,
    )

fun ContributionGroup.toApi(withFeatures: Boolean = true) =
    ApiContributionGroup(
        part = part,
        labelKey = labelKey,
        contribution = contribution,
        features = if (withFeatures) features.map { it.toApi() } else emptyList(),
    )

/**
 * The texts of an item of the [store] as shown, as the user corrected them, each with the site's own of [siteTexts]: a
 * facet's own line as the site writes it is shown with the facet, not among the texts.
 */
private fun textValues(
    store: SourceStore,
    siteTexts: Map<String, String>,
    fields: Map<String, String>,
): List<TextValue> {
    val texts = Corrected.texts(siteTexts, fields)
    val originals = store.schema.facets.mapNotNull { it.original }.toSet()
    return store.schema.texts
        .filter { it.key !in originals }
        .mapNotNull { def ->
            texts[def.key]?.let {
                TextValue(
                    def.key,
                    I18nKeys.textLabel(store.id, def.key),
                    it,
                    def.spoiler,
                    Corrected.textField(def.key) in fields,
                    siteTexts[def.key],
                )
            }
        }
}

/**
 * The numbers of an item of the [store] as shown, as the user corrected them; [withSite], each with the site's own of
 * [siteNumbers] too.
 */
private fun numberValues(
    store: SourceStore,
    siteNumbers: Map<String, Double>,
    fields: Map<String, String>,
    withSite: Boolean,
): List<NumberValue> {
    val numbers = Corrected.numbers(siteNumbers, fields)
    return store.schema.numbers.mapNotNull { def ->
        numbers[def.key]?.let {
            NumberValue(
                def.key,
                I18nKeys.numberLabel(store.id, def),
                it,
                Corrected.numberField(def.key) in fields,
                siteNumbers[def.key]?.takeIf { withSite },
            )
        }
    }
}

/** Everything the dialog of a work shows. */
@Component
class ItemDetailsReader(
    private val stores: Stores,
    private val plugins: Plugins,
    private val cards: ItemCards,
    private val explanations: Explanations,
    private val works: Works,
    private val sitesSay: SitesSay,
) {
    /**
     * The work as the dialog shows it, its chances made now in memory ([FacetSuggestions.chances]): of every item of
     * the work in one chain a source, for its card and its form alike.
     */
    fun read(key: ItemKey): ItemDetails? {
        val store = stores.source(key.source) ?: return null
        val type = stores.typeOf(key.source)
        val clusters = works.of(type)
        val members = cards.membersRead(type, clusters.members(key), null)
        return cards.cards(type, listOf(key), clusters = clusters, members = members).firstOrNull()?.let { summary ->
            details(store, type, key, summary, clusters, members)
        }
    }

    /**
     * Everything the dialog shows of the item [key] of the [store], its card [summary] and its work's [members] read.
     */
    private fun details(
        store: SourceStore,
        type: TypeStore,
        key: ItemKey,
        summary: ItemSummary,
        clusters: WorkClusters,
        members: MembersRead,
    ): ItemDetails {
        val corrections = store.corrections.facetsOf(key.id)
        val fields = store.corrections.fieldsOf(key.id)
        val work = cards.ofWork(type, clusters, key, store.items.facets(key.id), members.chances[key], read = members)
        val names = namesOf(store, work, corrections)
        // What the model of the grades reads of the work, of the chances made of its items already
        val model = explanations.details(key, members.chances)
        val matches = ItemMatches(stores, type, key)
        return ItemDetails(
            summary = summary,
            titleCorrected = Corrected.TITLE in fields,
            allFacets =
                cards.facets(
                    store,
                    work,
                    corrections,
                    names,
                    sitesSay.of(type, store, clusters.members(key)),
                    empty = { it.editable },
                    complete = store.corrections.completeOf(key.id),
                ) {
                    true
                },
            texts = textValues(store, store.items.texts(key.id), fields),
            allNumbers = numberValues(store, store.items.numbers(key.id), fields, withSite = false),
            // Of a graded work, what a model that had not seen it says is read apart (heldOut): it is
            // fitted for the asking, and every correction would wait a second or more for it
            explanation = model.explanation.map { it.toApi() },
            ratings = ratings(type, key),
            reviews = reviews(store, key, model),
            reviewCount = store.reviews.countOf(key.id),
            pictures = pictures(store, key, model),
            parts = parts(store, key, model),
            pictureMatches = matches.pictures(model.pictureMatches),
            reviewMatches = matches.reviews(model.reviewMatches),
            canRefresh = store.source.downloadsPages,
            related = related(type, store, key, Corrected.facets(work.site, corrections), names),
            sites = sites(type, clusters.members(key)),
            relations = relations(type, clusters.members(key)),
        )
    }

    /** The names of every value of every facet the [work] is shown with: the site's, those answered on, the model's. */
    private fun namesOf(
        store: SourceStore,
        work: WorkValues,
        corrections: List<FacetCorrection>,
    ): Map<String, Map<String, String>> =
        store.schema.facets.associate { def ->
            def.key to
                store.names(
                    def.key,
                    work.site[def.key].orEmpty() +
                        corrections.filter { it.facet == def.key }.map { it.key } +
                        ModelValues.named(work.chances?.get(def.key)),
                )
        }

    /** The reviews of the item [key], each with what it moves the [model] by and the user's mark of it. */
    private fun reviews(store: SourceStore, key: ItemKey, model: Details): List<ReviewInfo> {
        val reviewMarks = store.marks.reviewMarksOf(key.id)
        return model.reviews.map { (r, influence) ->
            ReviewInfo(
                reviewId = r.reviewId,
                content = r.content,
                author = r.author,
                stars = r.stars,
                postedAt = r.postedAt?.atOffset(ZoneOffset.UTC),
                influence = influence,
                mark = reviewMarks[r.reviewId],
            )
        }
    }

    /** The pictures of the item [key], each with what it moves the [model] by and the user's mark of it. */
    private fun pictures(store: SourceStore, key: ItemKey, model: Details): List<PictureInfo> {
        val pictureMarks = store.marks.pictureMarksOf(key.id)
        return store.pictures.ofItem(key.id, plugins.imageEncoder()?.id).map { p ->
            PictureInfo(
                position = p.position,
                hasFull = p.fullFile != null,
                analyzed = p.analyzed,
                mark = pictureMarks.firstOrNull { it.position == p.position && it.url == p.url }?.mark,
                influence = model.pictures[p.position],
                error = p.error,
            )
        }
    }

    /** The parts of the item [key], each with what its text moves the [model] by. */
    private fun parts(store: SourceStore, key: ItemKey, model: Details): List<PartInfo> =
        store.parts.ofItem(key.id).map {
            PartInfo(
                it.partId,
                it.position,
                it.hasText,
                it.title,
                it.publishedAt?.atOffset(ZoneOffset.UTC),
                model.parts[it.partId],
            )
        }

    /** What each item of a work says of it: the one that says the most first ([Works.fullness]). */
    private fun sites(type: TypeStore, members: List<ItemKey>): List<WorkSite> {
        val encoder = plugins.imageEncoder()?.id
        return works.fullestFirst(type, members).mapNotNull { m ->
            val s = type.source(m.source) ?: return@mapNotNull null
            val head = s.items.find(m.id) ?: return@mapNotNull null
            val fields = s.corrections.fieldsOf(m.id)
            val authors =
                s.schema.facets
                    .firstOrNull { it.role == FacetRole.AUTHOR }
                    ?.key
                    ?.let { facet ->
                        val keys = s.items.facets(m.id)[facet].orEmpty()
                        val names = s.names(facet, keys)
                        keys.map { names[it] ?: it }
                    }
                    .orEmpty()
            WorkSite(
                source = m.source,
                item = m.id,
                title = Corrected.title(head.title, fields),
                titleCorrected = Corrected.TITLE in fields,
                siteTitle = head.title,
                url = head.url,
                authors = authors,
                texts = textValues(s, s.items.texts(m.id), fields),
                numbers = numberValues(s, s.items.numbers(m.id), fields, withSite = true),
                reviewCount = s.reviews.countOf(m.id),
                pictureCount = s.pictures.ofItem(m.id, encoder).size,
                partCount = s.parts.ofItem(m.id).size,
                canRefresh = s.source.downloadsPages,
            )
        }
    }

    /** The works the user said to be related to the work of the [members]: every item of them. */
    private fun relations(type: TypeStore, members: List<ItemKey>): List<WorkRelation> {
        val own = members.toSet()
        return type.links
            .relations()
            .entries
            .mapNotNull { (pair, kind) ->
                val other =
                    when {
                        pair.first in own -> pair.second
                        pair.second in own -> pair.first
                        else -> return@mapNotNull null
                    }
                val s = type.source(other.source) ?: return@mapNotNull null
                val head = s.items.find(other.id) ?: return@mapNotNull null
                WorkRelation(
                    other.source,
                    other.id,
                    Corrected.title(head.title, s.corrections.fieldsOf(other.id)),
                    head.url,
                    WorkRelation.Kind.valueOf(kind),
                )
            }
            .distinctBy { it.source to it.item }
    }

    /**
     * The other works of the series the work is a part of and of the people who made it — the facets of the roles
     * SERIES and AUTHOR — in the same source, with the user's grades: how the user liked the volumes before tells most
     * of a volume after. The series first; a work shown there is not shown again under its author.
     */
    private fun related(
        type: TypeStore,
        store: SourceStore,
        key: ItemKey,
        values: Map<String, List<String>>,
        names: Map<String, Map<String, String>>,
    ): List<RelatedWorks> {
        // The series first: an author's works already shown as volumes are not shown again under the author
        val roles = mapOf(FacetRole.SERIES to ApiFacetRole.SERIES, FacetRole.AUTHOR to ApiFacetRole.AUTHOR)
        val defs =
            store.schema.facets.filter { it.role in roles }.sortedBy { if (it.role == FacetRole.SERIES) 0 else 1 }
        if (defs.isEmpty()) return emptyList()
        val shown = HashSet<String>()
        return defs.flatMap { def ->
            val keys = values[def.key].orEmpty()
            val bySite = store.items.itemsWith(def.key, keys)
            // The user's answers on these values alone: who else the user said is of them, or is not
            val own = store.corrections.facetsWith(def.key, keys)
            keys.mapNotNull { value ->
                val removed = own.filter { it.key == value && !it.added }.map { it.itemId }.toSet()
                val ids =
                    (bySite[value].orEmpty() + own.filter { it.key == value && it.added }.map { it.itemId })
                        .distinct()
                        .filter { it != key.id && it !in removed && it !in shown }
                if (ids.isEmpty()) return@mapNotNull null
                shown += ids
                val items =
                    cards
                        .cards(type, ids.map { ItemKey(store.id, it) })
                        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
                // A value the user added is named as they wrote it
                val name =
                    names[def.key]?.get(value)
                        ?: own.firstNotNullOfOrNull { c -> c.name.takeIf { c.key == value && c.added } }
                        ?: value
                RelatedWorks(
                    facet = def.key,
                    labelKey = I18nKeys.facetLabel(store.id, def),
                    role = roles.getValue(def.role!!),
                    value = value,
                    name = name,
                    items = items,
                )
            }
        }
    }

    /** The grades of the work [key] is of as they were given, newest first. */
    fun ratings(type: TypeStore, key: ItemKey): List<RatingRecord> =
        type.source(key.source)?.ratings?.given(key.id).orEmpty().map {
            RatingRecord(
                source = it.key.source,
                item = it.key.id,
                version = it.version,
                grade = it.grade,
                ratedAt = it.ratedAt.atOffset(ZoneOffset.UTC),
                current = it.current,
            )
        }
}

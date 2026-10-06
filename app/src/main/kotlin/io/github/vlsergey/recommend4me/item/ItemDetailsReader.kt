package io.github.vlsergey.recommend4me.item

import io.github.vlsergey.recommend4me.api.model.FeatureContribution
import io.github.vlsergey.recommend4me.api.model.ItemDetails
import io.github.vlsergey.recommend4me.api.model.NumberValue
import io.github.vlsergey.recommend4me.api.model.PartInfo
import io.github.vlsergey.recommend4me.api.model.PictureInfo
import io.github.vlsergey.recommend4me.api.model.PictureMatch
import io.github.vlsergey.recommend4me.api.model.RatingRecord
import io.github.vlsergey.recommend4me.api.model.ReviewInfo
import io.github.vlsergey.recommend4me.api.model.ReviewMatch
import io.github.vlsergey.recommend4me.api.model.TextValue
import io.github.vlsergey.recommend4me.correction.Corrected
import io.github.vlsergey.recommend4me.mark.MarkKind
import io.github.vlsergey.recommend4me.model.Contribution
import io.github.vlsergey.recommend4me.model.Recommendations
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.source.SourceMode
import io.github.vlsergey.recommend4me.source.Stores
import org.springframework.stereotype.Component
import java.time.ZoneOffset

fun Contribution.toApi() = FeatureContribution(feature = feature, label = label, contribution = contribution, present = present)

/** Everything the dialog of a work shows. */
@Component
class ItemDetailsReader(
    private val stores: Stores,
    private val plugins: Plugins,
    private val cards: ItemCards,
    private val recommendations: Recommendations,
) {
    fun read(key: ItemKey): ItemDetails? {
        val store = stores.source(key.source) ?: return null
        val type = stores.typeOf(key.source)
        val summary = cards.cards(type, listOf(key)).firstOrNull() ?: return null
        val schema = store.source.schema
        val site = store.items.facets(key.id)
        val corrections = store.corrections.facetsOf(key.id)
        val fields = store.corrections.fieldsOf(key.id)
        val names = schema.facets.associate { def ->
            def.key to store.items.facetNames(def.key, site[def.key].orEmpty() + corrections.filter { it.facet == def.key }.map { it.key })
        }
        val siteTexts = store.items.texts(key.id)
        val texts = Corrected.texts(siteTexts, fields)
        val siteNumbers = store.items.numbers(key.id)
        val numbers = Corrected.numbers(siteNumbers, fields)
        val model = recommendations.details(key)
        val encoder = plugins.imageEncoder()?.id
        val pictureMarks = store.marks.pictureMarksOf(key.id)
        val pictures = store.pictures.ofItem(key.id, encoder)
        val reviewMarks = store.marks.reviewMarksOf(key.id)
        val feedback = type.sources.flatMap { it.marks.feedback(MarkKind.PICTURE) + it.marks.feedback(MarkKind.REVIEW) }
            .filter { it.match.item == key }
        fun verdict(kind: MarkKind, mark: io.github.vlsergey.recommend4me.likeness.Marked, matchRef: String?) =
            feedback.firstOrNull { it.kind == kind && it.mark.item == mark.owner && it.mark.ref == mark.ref && it.match.ref == matchRef }?.verdict
        fun titleOf(k: ItemKey): String = stores.source(k.source)?.let { s ->
            s.items.find(k.id)?.let { Corrected.title(it.title, s.corrections.fieldsOf(k.id)) }
        } ?: k.toString()
        return ItemDetails(
            summary = summary,
            titleCorrected = Corrected.TITLE in fields,
            allFacets = cards.facets(store, site, corrections, names) { true },
            texts = schema.texts.mapNotNull { def ->
                texts[def.key]?.let { TextValue(def.key, def.label, it, def.spoiler, Corrected.textField(def.key) in fields) }
            },
            allNumbers = schema.numbers.mapNotNull { def ->
                numbers[def.key]?.let { NumberValue(def.key, def.label, it, Corrected.numberField(def.key) in fields) }
            },
            explanation = model.explanation.map { it.toApi() },
            ratings = store.ratings.ofItem(key.id).map { RatingRecord(it.version, it.grade, it.ratedAt.atOffset(ZoneOffset.UTC)) },
            reviews = model.reviews.map { (r, influence) ->
                ReviewInfo(
                    reviewId = r.reviewId, content = r.content, author = r.author, stars = r.stars,
                    postedAt = r.postedAt?.atOffset(ZoneOffset.UTC), influence = influence, mark = reviewMarks[r.reviewId],
                )
            },
            reviewCount = store.reviews.countOf(key.id),
            pictures = pictures.map { p ->
                PictureInfo(
                    position = p.position, hasFull = p.fullFile != null, analyzed = p.analyzed,
                    mark = pictureMarks.firstOrNull { it.position == p.position && it.url == p.url }?.mark,
                    influence = model.pictures[p.position], error = p.error,
                )
            },
            parts = store.parts.ofItem(key.id).map { PartInfo(it.partId, it.position, it.hasText, it.title, it.publishedAt?.atOffset(ZoneOffset.UTC)) },
            pictureMatches = model.pictureMatches.map {
                PictureMatch(
                    position = it.ref?.toIntOrNull() ?: 0, markSource = it.mark.owner.source, markItem = it.mark.owner.id,
                    markTitle = titleOf(it.mark.owner), markPosition = it.mark.ref.toIntOrNull() ?: 0, mark = it.mark.mark,
                    strength = it.strength.toDouble(), verdict = verdict(MarkKind.PICTURE, it.mark, it.ref),
                )
            },
            reviewMatches = model.reviewMatches.let { matches ->
                val contents = matches.flatMap { m -> listOfNotNull(m.ref?.let { key to it }, m.mark.owner to m.mark.ref) }
                    .groupBy { it.first.source }
                    .flatMap { (source, refs) -> stores.source(source)?.reviews?.contents(refs.map { it.first.id to it.second })?.map { (k, v) -> (ItemKey(source, k.first) to k.second) to v }.orEmpty() }
                    .toMap()
                matches.map {
                    ReviewMatch(
                        reviewId = it.ref.orEmpty(), content = contents[key to it.ref.orEmpty()].orEmpty(),
                        markSource = it.mark.owner.source, markItem = it.mark.owner.id, markReviewId = it.mark.ref,
                        markTitle = titleOf(it.mark.owner), markContent = contents[it.mark.owner to it.mark.ref].orEmpty(),
                        mark = it.mark.mark, strength = it.strength.toDouble(), verdict = verdict(MarkKind.REVIEW, it.mark, it.ref),
                    )
                }
            },
            canRefresh = store.source.modes.any { it != SourceMode.BROWSER },
        )
    }
}

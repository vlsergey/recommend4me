package io.github.vlsergey.recommend4me.item

import io.github.vlsergey.recommend4me.api.model.PictureMatch
import io.github.vlsergey.recommend4me.api.model.ReviewMatch
import io.github.vlsergey.recommend4me.correction.Corrected
import io.github.vlsergey.recommend4me.likeness.MarkLikeness
import io.github.vlsergey.recommend4me.likeness.Marked
import io.github.vlsergey.recommend4me.mark.MarkKind
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.source.TypeStore

/**
 * THE MARKS A WORK'S PICTURES AND REVIEWS ARE ALIKE TO, as the dialog of the work [key] shows them: each with the work
 * it was made on and the user's word on the match. The user's words on the matches of the type are read once.
 */
internal class ItemMatches(private val stores: Stores, type: TypeStore, private val key: ItemKey) {
    private val feedback =
        type.sources
            .flatMap { it.marks.feedback(MarkKind.PICTURE) + it.marks.feedback(MarkKind.REVIEW) }
            .filter { it.match.item == key }

    /** The marks of pictures the work's pictures are alike to. */
    fun pictures(matches: List<MarkLikeness.Match>): List<PictureMatch> = matches.map {
        PictureMatch(
            position = it.ref?.toIntOrNull() ?: 0,
            markSource = it.mark.owner.source,
            markItem = it.mark.owner.id,
            markTitle = titleOf(it.mark.owner),
            markPosition = it.mark.ref.toIntOrNull() ?: 0,
            mark = it.mark.mark,
            strength = it.strength.toDouble(),
            verdict = verdict(MarkKind.PICTURE, it.mark, it.ref),
        )
    }

    /** The marks of reviews the work's reviews are alike to, both texts read in one go of every source. */
    fun reviews(matches: List<MarkLikeness.Match>): List<ReviewMatch> {
        val contents =
            matches
                .flatMap { m -> listOfNotNull(m.ref?.let { key to it }, m.mark.owner to m.mark.ref) }
                .groupBy { it.first.source }
                .flatMap { (source, refs) ->
                    stores
                        .source(source)
                        ?.reviews
                        ?.contents(refs.map { it.first.id to it.second })
                        ?.map { (k, v) -> (ItemKey(source, k.first) to k.second) to v }
                        .orEmpty()
                }
                .toMap()
        return matches.map {
            ReviewMatch(
                reviewId = it.ref.orEmpty(),
                content = contents[key to it.ref.orEmpty()].orEmpty(),
                markSource = it.mark.owner.source,
                markItem = it.mark.owner.id,
                markReviewId = it.mark.ref,
                markTitle = titleOf(it.mark.owner),
                markContent = contents[it.mark.owner to it.mark.ref].orEmpty(),
                mark = it.mark.mark,
                strength = it.strength.toDouble(),
                verdict = verdict(MarkKind.REVIEW, it.mark, it.ref),
            )
        }
    }

    /** The user's word on the match of the [mark] with the work's thing [matchRef]; null when none was given. */
    private fun verdict(kind: MarkKind, mark: Marked, matchRef: String?) =
        feedback
            .firstOrNull {
                it.kind == kind && it.mark.item == mark.owner && it.mark.ref == mark.ref && it.match.ref == matchRef
            }
            ?.verdict

    /** The title of the work [k] as the user corrected it; its key when it is not known. */
    private fun titleOf(k: ItemKey): String =
        stores.source(k.source)?.let { s ->
            s.items.find(k.id)?.let { Corrected.title(it.title, s.corrections.fieldsOf(k.id)) }
        } ?: k.toString()
}

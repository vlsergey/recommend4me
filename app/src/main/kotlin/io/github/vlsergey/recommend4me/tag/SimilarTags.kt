package io.github.vlsergey.recommend4me.tag

import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.textvector.PhraseVectors
import org.springframework.stereotype.Service

/**
 * A row of a tag's table of the alike: another normalized tag alike it — or [site], a site's tag bound to it — with how
 * alike, 0..1, and what the user said it is to the tag.
 */
class SimilarTag(val tag: NamedTag, val similarity: Double, val relation: TagRelation, val site: TagVariant? = null)

/** A page of a tag's table of the alike, and how many rows the whole table has. */
class SimilarPage(val total: Int, val rows: List<SimilarTag>)

/**
 * THE TABLE OF THE ALIKE OF A TAG: the application only offers tags alike by the meaning of their names, within one
 * site and across them ([TagMeaning]); it merges nothing by itself, the user says what each is to the tag
 * ([TagEditing]).
 */
@Service
class SimilarTags(private val tags: Tags, private val phrases: PhraseVectors, private val plugins: Plugins) {

    /** A row before it is named — a tag, or a site's tag bound to this one — named only on the page asked for. */
    private class Row(
        val tag: TagStats,
        val similarity: Double,
        val relation: TagRelation,
        val site: TagVariant?,
        val key: String,
    )

    /**
     * A page of the table of the alike of [tag]: every other normalized tag and every site's tag bound to it (merged),
     * but the rows of the relations [hidden], the likest first by the meaning of their names, of one site or another
     * and of the entries of the catalogues they are bound to ([TagMeaning]) — a name encoded as the search encodes
     * short texts, a tag the mean of its names' directions, alike by the cosine. Alike the tag's own names; alike the
     * text [query] instead, when one is given. Of rows alike equally, by key: a page read after another continues it
     * while nothing changed.
     */
    fun similar(
        scope: TagScope,
        tag: String,
        query: String?,
        hidden: Set<TagRelation>,
        offset: Int,
        limit: Int,
        ignored: Boolean? = null,
    ): SimilarPage? {
        val stats = tags.stats(scope)
        val own = stats[tag] ?: return null
        val n = tags.naming(scope, stats)
        val meaning = TagMeaning(scope, stats, phrases, plugins)
        val asked = query?.trim()?.takeIf { it.isNotEmpty() }
        // The tag's own direction; till it is made, of its sites' tags' names the sources keep
        val target =
            if (asked == null) meaning.names(own.key).ifEmpty { meaning.names(null, own.variants.keys) }
            else meaning.ofText(asked)
        val relation = relationTo(scope, n, own)
        val others =
            stats.values
                .filter { it.key != tag }
                .map { t -> Row(t, meaning.likeness(target, t.key), relation(t.key), null, t.key) }
        // Every site's tag bound to this one: a row of its own, merged — taken out by the user, a tag of its own
        val bound =
            own.variants.values.map { v ->
                Row(
                    own,
                    meaning.likeness(target, null, listOf(v.site)),
                    TagRelation.MERGED,
                    v,
                    "site:${v.site.source}/${v.site.tag}",
                )
            }
        val rows =
            (others + bound)
                .filter { it.relation !in hidden && (ignored == null || (it.tag.key in n.ignored) == ignored) }
                .sortedWith(compareByDescending<Row> { it.similarity }.thenBy { it.key })
        val page = rows.drop(offset.coerceAtLeast(0)).take(limit)
        return SimilarPage(rows.size, page.map { SimilarTag(n.named(it.tag), it.similarity, it.relation, it.site) })
    }

    /** What the user said every other tag is to [own], by the other's key. */
    private fun relationTo(scope: TagScope, n: Naming, own: TagStats): (String) -> TagRelation {
        val distinct = scope.tags.relations.distinct()
        val descendants = n.hierarchy.descendants(own.key)
        val ancestors = n.hierarchy.ancestors(own.key)
        return { key ->
            when {
                key in n.hierarchy.childrenOf(own.key) -> TagRelation.CHILD
                key in n.hierarchy.parentsOf(own.key) -> TagRelation.PARENT
                key in descendants -> TagRelation.DESCENDANT
                key in ancestors -> TagRelation.ANCESTOR
                TagRepository.pair(own.id, TagRepository.idOf(key)!!) in distinct -> TagRelation.DIFFERENT
                else -> TagRelation.NONE
            }
        }
    }
}

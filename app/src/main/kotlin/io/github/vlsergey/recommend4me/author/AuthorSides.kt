package io.github.vlsergey.recommend4me.author

import io.github.vlsergey.recommend4me.api.model.AuthorSide
import io.github.vlsergey.recommend4me.api.model.AuthorWork
import io.github.vlsergey.recommend4me.api.model.SiteAuthor
import io.github.vlsergey.recommend4me.api.model.TagEntryInfo
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.tag.BoundEntry
import io.github.vlsergey.recommend4me.tag.TagEntryRef
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * What a normalized author is shown by: the authors of the sites bound to them, by their names and how many works each
 * has; the entries of the catalogues they are bound to, in the user's language; how many works they have.
 */
@Component
class AuthorSides(
    /**
     * The languages an entry is asked in, the user's first: of its texts, those of the first that has them are shown.
     */
    @Value("\${recommend4me.languages:ru,en}") private val languages: List<String>
) {

    /** The author [id] as shown, with the works of theirs [rest] listed. */
    internal fun side(
        id: Long,
        sites: Map<Long, List<SiteAuthor>>,
        entries: Map<Long, List<TagEntryInfo>>,
        library: AuthorLibrary,
        rest: List<AuthorWork>,
    ) =
        AuthorSide(
            id = id,
            sites = sites[id].orEmpty(),
            works = library.worksOf[id].orEmpty().size,
            rest = rest,
            propertyEntries = entries[id].orEmpty(),
        )

    /** The entries [bound] as kept, by the author. */
    fun entryInfos(type: TypeStore, bound: Map<Long, List<TagEntryRef>>): Map<Long, List<TagEntryInfo>> {
        val kept = type.tags.keptEntries(bound.values.flatten().toSet())
        return bound.mapValues { (_, refs) ->
            refs.mapNotNull { ref ->
                kept[ref]?.let { e ->
                    TagEntryInfo(
                        ref.catalogue,
                        e.id,
                        e.url,
                        BoundEntry(ref.catalogue, e).names,
                        firstOf(e.labels),
                        firstOf(e.descriptions),
                    )
                }
            }
        }
    }

    /** Of the [texts] by language, the one of the user's first language that has one. */
    fun firstOf(texts: Map<String, String>): String? = languages.firstNotNullOfOrNull { texts[it] }

    /**
     * The authors of the sites of the normalized authors [ids]: their names and how many works each has, of every
     * source in one go.
     */
    fun sites(type: TypeStore, ids: Collection<Long>): Map<Long, List<SiteAuthor>> {
        val refs = type.authors.sitesOf(ids)
        val inSources = refs.values.flatten().groupBy { it.source }
        val shown = HashMap<SiteAuthorRef, SiteAuthor>()
        inSources.forEach { (source, own) ->
            val s = type.source(source) ?: return@forEach
            val facet = authorFacetOf(s) ?: return@forEach
            val names = s.names(facet, own.map { it.author })
            val uses = s.items.valueUses(facet)
            own.forEach {
                val name = names[it.author] ?: it.author
                shown[it] =
                    SiteAuthor(source, it.author, name, uses[it.author] ?: 0, s.source.valueUrl(facet, it.author, name))
            }
        }
        return refs.mapValues { (_, list) -> list.mapNotNull { shown[it] } }
    }
}

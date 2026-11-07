package io.github.vlsergey.recommend4me.tag

import io.github.vlsergey.recommend4me.api.TagsApi
import io.github.vlsergey.recommend4me.api.model.SimilarTag as ApiSimilarTag
import io.github.vlsergey.recommend4me.api.model.SimilarTagPage
import io.github.vlsergey.recommend4me.api.model.TagBindingRequest
import io.github.vlsergey.recommend4me.api.model.TagIgnoredRequest
import io.github.vlsergey.recommend4me.api.model.TagInfo
import io.github.vlsergey.recommend4me.api.model.TagNameRequest
import io.github.vlsergey.recommend4me.api.model.TagPage
import io.github.vlsergey.recommend4me.api.model.TagRelation as ApiTagRelation
import io.github.vlsergey.recommend4me.api.model.TagRelationRequest
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController

/** The tags, their table of the alike and the user's word on them. */
@RestController
class TagsController(
    private val tags: Tags,
    private val similar: SimilarTags,
    private val editing: TagEditing,
    /** The user's languages: of an entry's texts, those of the first that has them are shown. */
    @Value("\${recommend4me.languages:ru,en}") private val languages: List<String>,
) : TagsApi {

    /** What the tags are read over and their facet; 404 when no type has free tags. */
    private fun scope(): Pair<TagScope, String> {
        val scope = tags.requiredScope()
        return scope to scope.facet
    }

    override fun listTags(
        query: String?,
        with: List<String>?,
        without: List<String>?,
        ignored: Boolean?,
        offset: Int,
        limit: Int,
    ): ResponseEntity<TagPage> {
        val (t, facet) = scope()
        val all = tags.list(t, query, with.orEmpty().toSet(), without.orEmpty().toSet(), ignored)
        return ResponseEntity.ok(
            TagPage(
                total = all.size,
                tags = all.drop(offset.coerceAtLeast(0)).take(limit.coerceIn(1, MAX_PAGE)).map { it.toApi(languages) },
                sources = tags.sources(t, facet),
            )
        )
    }

    override fun getTag(tag: String): ResponseEntity<TagInfo> {
        val (t, facet) = scope()
        return tags.one(t, facet, tag)?.let { ResponseEntity.ok(it.toApi(languages)) }
            ?: ResponseEntity.notFound().build()
    }

    override fun listSimilarTags(
        tag: String,
        query: String?,
        offset: Int,
        limit: Int,
        ignored: Boolean?,
        hidden: List<ApiTagRelation>?,
    ): ResponseEntity<SimilarTagPage> {
        val t = tags.requiredScope()
        // Rows of no relation are the offers themselves: never hidden
        val hide = hidden.orEmpty().map { TagRelation.valueOf(it.name) }.toSet() - TagRelation.NONE
        val page =
            similar.similar(t, tag, query, hide, offset, limit.coerceIn(1, MAX_PAGE), ignored)
                ?: return ResponseEntity.notFound().build()
        val rows =
            page.rows.map {
                ApiSimilarTag(
                    it.tag.toApi(languages),
                    it.similarity,
                    ApiTagRelation.valueOf(it.relation.name),
                    it.site?.toApi(),
                )
            }
        return ResponseEntity.ok(SimilarTagPage(page.total, rows))
    }

    override fun relateTags(tagRelationRequest: TagRelationRequest): ResponseEntity<TagInfo> {
        val (t, facet) = scope()
        val r = tagRelationRequest
        val related = editing.relate(t, facet, r.tag, r.other, TagRelation.valueOf(r.relation.name))
        return related?.let { ResponseEntity.ok(it.toApi(languages)) } ?: ResponseEntity.notFound().build()
    }

    override fun nameTag(tagNameRequest: TagNameRequest): ResponseEntity<TagInfo> {
        val (t, facet) = scope()
        return editing.name(t, facet, tagNameRequest.tag, tagNameRequest.name)?.let {
            ResponseEntity.ok(it.toApi(languages))
        } ?: ResponseEntity.notFound().build()
    }

    override fun ignoreTag(tagIgnoredRequest: TagIgnoredRequest): ResponseEntity<TagInfo> {
        val (t, facet) = scope()
        return editing.ignore(t, facet, tagIgnoredRequest.tag, tagIgnoredRequest.ignored)?.let {
            ResponseEntity.ok(it.toApi(languages))
        } ?: ResponseEntity.notFound().build()
    }

    override fun bindSiteTag(tagBindingRequest: TagBindingRequest): ResponseEntity<TagInfo> {
        val (t, facet) = scope()
        val r = tagBindingRequest
        return editing.bind(t, facet, SiteTagRef(r.source, r.siteTag), r.tag)?.let {
            ResponseEntity.ok(it.toApi(languages))
        } ?: ResponseEntity.notFound().build()
    }

    private companion object {
        /** The most rows of a page asked for. */
        const val MAX_PAGE = 1000
    }
}

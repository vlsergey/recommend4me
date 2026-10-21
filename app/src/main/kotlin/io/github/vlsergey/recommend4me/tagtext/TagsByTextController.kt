package io.github.vlsergey.recommend4me.tagtext

import io.github.vlsergey.recommend4me.api.TagsByTextApi
import io.github.vlsergey.recommend4me.api.model.TagByText
import io.github.vlsergey.recommend4me.api.model.TagByTextAnswer
import io.github.vlsergey.recommend4me.api.model.TagsByText
import io.github.vlsergey.recommend4me.source.Stores
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController

/** The tags a work's own text is closest to, and the user's word on them ([TextTags]). */
@RestController
class TagsByTextController(private val stores: Stores, private val tags: TextTags) : TagsByTextApi {

    override fun getTagsByText(source: String, item: String): ResponseEntity<TagsByText> {
        val store = stores.source(source)?.takeIf { it.items.find(item) != null } ?: return ResponseEntity.notFound().build()
        val list = tags.of(store, item) ?: return ResponseEntity.notFound().build()
        val facet = store.tagFacets.first()
        val names = store.names(facet, list.tags.map { it.key })
        return ResponseEntity.ok(
            TagsByText(
                pending = list.pending, fitted = list.fitted,
                tags = list.tags.map {
                    TagByText(
                        key = it.key, name = names[it.key] ?: it.key, closeness = it.closeness, part = it.partId,
                        paragraph = it.paragraph, quote = it.quote, url = it.url, chance = it.chance, partTitle = it.partTitle,
                    )
                },
            ),
        )
    }

    override fun answerTagByText(source: String, item: String, tagByTextAnswer: TagByTextAnswer): ResponseEntity<Unit> {
        val store = stores.source(source)?.takeIf { it.items.find(item) != null } ?: return ResponseEntity.notFound().build()
        val a = tagByTextAnswer
        val kept = tags.answer(store, item, a.key, a.part, a.paragraph, TagTextVerdict.valueOf(a.verdict.value))
        return if (kept) ResponseEntity.noContent().build() else ResponseEntity.notFound().build()
    }
}

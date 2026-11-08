package io.github.vlsergey.recommend4me.tagtext

import io.github.vlsergey.recommend4me.api.TagsByTextApi
import io.github.vlsergey.recommend4me.api.model.AnsweredFacet
import io.github.vlsergey.recommend4me.api.model.TagByText
import io.github.vlsergey.recommend4me.api.model.TagByTextAnswer
import io.github.vlsergey.recommend4me.api.model.TagsByText
import io.github.vlsergey.recommend4me.correction.AnsweredFacets
import io.github.vlsergey.recommend4me.source.Stores
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController

/** The tags a work's own text is closest to, and the user's word on them ([TextTags]). */
@RestController
class TagsByTextController(private val stores: Stores, private val tags: TextTags) : TagsByTextApi {

    override fun getTagsByText(source: String, item: String): ResponseEntity<TagsByText> {
        val (store, list) =
            stores
                .source(source)
                ?.takeIf { it.items.find(item) != null }
                ?.let { s -> tags.of(s, item)?.let { s to it } } ?: return ResponseEntity.notFound().build()
        val facet = store.tagFacets.first()
        val names = store.names(facet, list.tags.map { it.key })
        return ResponseEntity.ok(
            TagsByText(
                pending = list.pending,
                fitted = list.fitted,
                tags =
                    list.tags.map {
                        TagByText(
                            key = it.key,
                            name = names[it.key] ?: it.key,
                            closeness = it.closeness,
                            part = it.partId,
                            paragraph = it.paragraph,
                            quote = it.quote,
                            url = it.url,
                            chance = it.chance,
                            partTitle = it.partTitle,
                        )
                    },
            )
        )
    }

    /** The user's word on a pair kept; of a yes, what the answer made of the facet of tags ([AnsweredFacets]). */
    override fun answerTagByText(
        source: String,
        item: String,
        tagByTextAnswer: TagByTextAnswer,
    ): ResponseEntity<List<AnsweredFacet>> {
        val a = tagByTextAnswer
        val verdict = TagTextVerdict.valueOf(a.verdict.value)
        val store =
            stores.source(source)?.takeIf {
                it.items.find(item) != null && tags.answer(it, item, a.key, a.part, a.paragraph, verdict)
            } ?: return ResponseEntity.notFound().build()
        val yes = verdict == TagTextVerdict.YES
        return ResponseEntity.ok(if (yes) AnsweredFacets.of(store, item, store.tagFacets.first()) else emptyList())
    }
}

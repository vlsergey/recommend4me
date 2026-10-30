package io.github.vlsergey.recommend4me.author

import io.github.vlsergey.recommend4me.api.AuthorsApi
import io.github.vlsergey.recommend4me.api.model.AuthorAnswer
import io.github.vlsergey.recommend4me.api.model.AuthorOfferPage
import io.github.vlsergey.recommend4me.api.model.AuthorSide
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController

@RestController
class AuthorsController(private val authors: Authors) : AuthorsApi {

    override fun getAuthorOffers(type: String, distinct: Boolean, offset: Int, limit: Int): ResponseEntity<AuthorOfferPage> =
        authors.offers(type, distinct, offset, limit)?.let { ResponseEntity.ok(it) } ?: ResponseEntity.notFound().build()

    override fun getMergedAuthors(type: String): ResponseEntity<List<AuthorSide>> =
        authors.merged(type)?.let { ResponseEntity.ok(it) } ?: ResponseEntity.notFound().build()

    override fun answerAuthors(authorAnswer: AuthorAnswer): ResponseEntity<Unit> {
        val a = authorAnswer.first
        val b = authorAnswer.second
        val done = when (authorAnswer.verdict) {
            AuthorAnswer.Verdict.SAME -> authors.merge(a, b)
            AuthorAnswer.Verdict.DIFFERENT -> authors.setDistinct(a, b, true)
            AuthorAnswer.Verdict.NONE -> authors.setDistinct(a, b, false)
        }
        return if (done) ResponseEntity.noContent().build() else ResponseEntity.notFound().build()
    }

    override fun takeOutSiteAuthor(id: Long, source: String, author: String): ResponseEntity<Unit> =
        if (authors.takeOut(id, source, author)) ResponseEntity.noContent().build() else ResponseEntity.notFound().build()
}

package io.github.vlsergey.recommend4me.author

import io.github.vlsergey.recommend4me.api.AuthorsApi
import io.github.vlsergey.recommend4me.api.model.AuthorAnswer
import io.github.vlsergey.recommend4me.api.model.AuthorEntryRequest
import io.github.vlsergey.recommend4me.api.model.AuthorKind
import io.github.vlsergey.recommend4me.api.model.AuthorOfferPage
import io.github.vlsergey.recommend4me.api.model.AuthorPage
import io.github.vlsergey.recommend4me.api.model.AuthorSaid
import io.github.vlsergey.recommend4me.api.model.AuthorSide
import io.github.vlsergey.recommend4me.api.model.FoundAuthorEntry
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController

@RestController
class AuthorsController(private val authors: Authors) : AuthorsApi {

    override fun listAuthors(type: String, kind: AuthorKind, search: String?, offset: Int, limit: Int): ResponseEntity<AuthorPage> =
        authors.list(type, kind, search, offset, limit)?.let { ResponseEntity.ok(it) } ?: ResponseEntity.notFound().build()

    override fun getAuthor(type: String, id: Long): ResponseEntity<AuthorSide> =
        authors.one(type, id)?.let { ResponseEntity.ok(it) } ?: ResponseEntity.notFound().build()

    override fun getAuthorOffers(type: String, said: AuthorSaid, author: Long?, offset: Int, limit: Int): ResponseEntity<AuthorOfferPage> =
        authors.offers(type, said, author, offset, limit)?.let { ResponseEntity.ok(it) } ?: ResponseEntity.notFound().build()

    override fun answerAuthors(authorAnswer: AuthorAnswer): ResponseEntity<Unit> {
        val first = SiteAuthorRef(authorAnswer.first.source, authorAnswer.first.author)
        val second = SiteAuthorRef(authorAnswer.second.source, authorAnswer.second.author)
        return if (authors.answer(first, second, authorAnswer.verdict)) ResponseEntity.noContent().build() else ResponseEntity.notFound().build()
    }

    override fun findAuthorEntries(type: String, query: String): ResponseEntity<List<FoundAuthorEntry>> =
        authors.findEntries(type, query)?.let { ResponseEntity.ok(it) } ?: ResponseEntity.notFound().build()

    override fun bindAuthorEntry(id: Long, authorEntryRequest: AuthorEntryRequest): ResponseEntity<Unit> {
        val r = authorEntryRequest
        return if (authors.bindEntry(id, r.catalogue, r.entry, r.bound)) ResponseEntity.noContent().build() else ResponseEntity.notFound().build()
    }

    override fun takeOutSiteAuthor(id: Long, source: String, author: String): ResponseEntity<Unit> =
        if (authors.takeOut(id, source, author)) ResponseEntity.noContent().build() else ResponseEntity.notFound().build()
}

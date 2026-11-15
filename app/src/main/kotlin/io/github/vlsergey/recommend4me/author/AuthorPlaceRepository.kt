package io.github.vlsergey.recommend4me.author

import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.SITE_AUTHOR_PLACE
import java.time.Instant
import org.jooq.DSLContext
import org.jooq.impl.DSL

/**
 * The places on the web the authors' pages on the sites list as theirs ("Места в сети" of fanfics.me), as each page
 * showed them last, in the dictionaries: every address, and of one that is an author's page of another source, that
 * author.
 */
class AuthorPlaceRepository(private val db: DSLContext) {

    /** The places [owner]'s page lists now — every address with the author of another source it is, if any. */
    fun set(owner: SiteAuthorRef, places: Map<String, SiteAuthorRef?>, now: Instant) = db.transaction { c ->
        val p = SITE_AUTHOR_PLACE
        val tx = DSL.using(c)
        tx.deleteFrom(p).where(p.SOURCE.eq(owner.source), p.AUTHOR.eq(owner.author)).execute()
        places.forEach { (url, place) ->
            tx.insertInto(p)
                .set(p.SOURCE, owner.source)
                .set(p.AUTHOR, owner.author)
                .set(p.URL, url)
                .set(p.PLACE_SOURCE, place?.source)
                .set(p.PLACE_AUTHOR, place?.author)
                .set(p.SAID_AT, now)
                .execute()
        }
    }

    /** Every place kept: whose page listed it, its address, and the author of another source it is, if any. */
    fun all(): List<AuthorPlace> {
        val p = SITE_AUTHOR_PLACE
        return db.select(p.SOURCE, p.AUTHOR, p.URL, p.PLACE_SOURCE, p.PLACE_AUTHOR).from(p).fetch { r ->
            AuthorPlace(
                SiteAuthorRef(r.value1()!!, r.value2()!!),
                r.value3()!!,
                r.value4()?.let { source -> r.value5()?.let { SiteAuthorRef(source, it) } },
            )
        }
    }

    /** The place [url] of [owner] the author of another source [place] is — or, null, none. */
    fun resolve(owner: SiteAuthorRef, url: String, place: SiteAuthorRef?) {
        val p = SITE_AUTHOR_PLACE
        db.update(p)
            .set(p.PLACE_SOURCE, place?.source)
            .set(p.PLACE_AUTHOR, place?.author)
            .where(p.SOURCE.eq(owner.source), p.AUTHOR.eq(owner.author), p.URL.eq(url))
            .execute()
    }
}

/** A place an author's page lists: whose page, its address, and the author of another source it is, if any. */
data class AuthorPlace(val owner: SiteAuthorRef, val url: String, val place: SiteAuthorRef?)

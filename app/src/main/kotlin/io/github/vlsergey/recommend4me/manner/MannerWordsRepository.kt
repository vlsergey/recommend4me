package io.github.vlsergey.recommend4me.manner

import io.github.vlsergey.recommend4me.database.model.tables.references.MANNER_WORDS
import java.time.Instant
import org.jooq.DSLContext

/** The words a content type's manners are measured by ([MannerWords]), in its model database: one set of them. */
class MannerWordsRepository(private val db: DSLContext) {

    /** The words in use; none before the first texts were read. */
    fun find(): MannerWords? =
        db.selectFrom(MANNER_WORDS).orderBy(MANNER_WORDS.FITTED_AT.desc()).limit(1).fetchOne {
            MannerWords.unpack(it.id!!, it.texts!!, it.content!!)
        }

    /** The id of the words in use, their words not read; none before the first texts were read. */
    fun id(): Long? =
        db.select(MANNER_WORDS.ID)
            .from(MANNER_WORDS)
            .orderBy(MANNER_WORDS.FITTED_AT.desc())
            .limit(1)
            .fetchOne()
            ?.value1()

    /** The [words] kept in place of any before them. */
    fun keep(words: MannerWords, now: Instant) {
        db.transaction { c ->
            c.dsl().deleteFrom(MANNER_WORDS).execute()
            c.dsl()
                .insertInto(MANNER_WORDS)
                .set(MANNER_WORDS.ID, words.id)
                .set(MANNER_WORDS.TEXTS, words.texts)
                .set(MANNER_WORDS.FITTED_AT, now)
                .set(MANNER_WORDS.CONTENT, words.pack())
                .execute()
        }
    }
}

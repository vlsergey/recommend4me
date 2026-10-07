package io.github.vlsergey.recommend4me.setvector

import io.github.vlsergey.recommend4me.database.model.tables.references.SET_EMBEDDING
import org.jooq.DSLContext
import java.time.Instant

/**
 * The directions of a kind of sets of a content type, the id the set vectors made by them carry,
 * and how many members of the catalogue they were fitted on (none when not kept).
 */
class StoredEmbedding(val id: Long, val encoder: String, val embedding: SetEmbedding, val members: Int? = null)

/** The directions of the set embeddings of a content type, in its model database. */
class SetEmbeddingRepository(private val db: DSLContext) {

    fun find(kind: SetKind): StoredEmbedding? =
        db.selectFrom(SET_EMBEDDING).where(SET_EMBEDDING.KIND.eq(kind.name))
            .fetchOne { StoredEmbedding(it.embeddingId!!, it.encoder!!, SetEmbedding.unpack(it.content!!), it.members) }

    fun save(kind: SetKind, id: Long, encoder: String, embedding: SetEmbedding, members: Int, now: Instant) {
        val content = embedding.pack()
        db.insertInto(SET_EMBEDDING)
            .set(SET_EMBEDDING.KIND, kind.name).set(SET_EMBEDDING.EMBEDDING_ID, id).set(SET_EMBEDDING.ENCODER, encoder)
            .set(SET_EMBEDDING.FITTED_AT, now).set(SET_EMBEDDING.CONTENT, content).set(SET_EMBEDDING.MEMBERS, members)
            .onDuplicateKeyUpdate()
            .set(SET_EMBEDDING.EMBEDDING_ID, id).set(SET_EMBEDDING.ENCODER, encoder)
            .set(SET_EMBEDDING.FITTED_AT, now).set(SET_EMBEDDING.CONTENT, content).set(SET_EMBEDDING.MEMBERS, members)
            .execute()
    }

    /** The ids of the directions in use, by kind. */
    fun ids(): Map<SetKind, Long> =
        db.select(SET_EMBEDDING.KIND, SET_EMBEDDING.EMBEDDING_ID).from(SET_EMBEDDING).fetch()
            .associate { SetKind.valueOf(it.value1()!!) to it.value2()!! }
}

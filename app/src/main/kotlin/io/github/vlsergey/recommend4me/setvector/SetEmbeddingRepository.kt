package io.github.vlsergey.recommend4me.setvector

import io.github.vlsergey.recommend4me.database.model.tables.references.SET_EMBEDDING
import org.jooq.DSLContext
import java.time.Instant

/** The directions of a kind of sets of a content type, and the id the set vectors made by them carry. */
class StoredEmbedding(val id: Long, val encoder: String, val embedding: SetEmbedding)

/** The directions of the set embeddings of a content type, in its model database. */
class SetEmbeddingRepository(private val db: DSLContext) {

    fun find(kind: SetKind): StoredEmbedding? =
        db.selectFrom(SET_EMBEDDING).where(SET_EMBEDDING.KIND.eq(kind.name))
            .fetchOne { StoredEmbedding(it.embeddingId!!, it.encoder!!, SetEmbedding.unpack(it.content!!)) }

    fun save(kind: SetKind, id: Long, encoder: String, embedding: SetEmbedding, now: Instant) {
        val content = embedding.pack()
        db.insertInto(SET_EMBEDDING)
            .set(SET_EMBEDDING.KIND, kind.name).set(SET_EMBEDDING.EMBEDDING_ID, id).set(SET_EMBEDDING.ENCODER, encoder)
            .set(SET_EMBEDDING.FITTED_AT, now).set(SET_EMBEDDING.CONTENT, content)
            .onDuplicateKeyUpdate()
            .set(SET_EMBEDDING.EMBEDDING_ID, id).set(SET_EMBEDDING.ENCODER, encoder)
            .set(SET_EMBEDDING.FITTED_AT, now).set(SET_EMBEDDING.CONTENT, content)
            .execute()
    }

    /** The ids of the directions in use, by kind. */
    fun ids(): Map<SetKind, Long> =
        db.select(SET_EMBEDDING.KIND, SET_EMBEDDING.EMBEDDING_ID).from(SET_EMBEDDING).fetch()
            .associate { SetKind.valueOf(it.value1()!!) to it.value2()!! }
}

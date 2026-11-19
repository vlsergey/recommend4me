package io.github.vlsergey.recommend4me.setvector

import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.review.ReviewRepository
import io.github.vlsergey.recommend4me.source.TypeStore
import org.springframework.stereotype.Service

/**
 * The sets of every item as vectors of fixed length ([SetEmbedding]): its screenshots (picture vectors), its reviews (a
 * vector of every review, read whole) and the windows of its text, as the models read them — kept in the source
 * database, made as the sets grow ([SetVectorMaking]); of a normalized work of several items, made of all of them.
 */
@Service
class SetVectors(private val sets: SetMembers) {
    /**
     * The sets of a normalized work of several [items], each made of the members of all of them by the directions in
     * use: the reviews of one work on two sites are one set. A review not encoded yet is encoded now: for a
     * computation, not a request.
     */
    fun ofWork(type: TypeStore, items: List<ItemKey>): Map<String, FloatArray> = ofWorks(type, listOf(items)).single()

    /**
     * [ofWork] of every work of [works] (each its items), the members of all of them read at once: a source's and a
     * kind's in one reading, not a reading a work.
     */
    fun ofWorks(type: TypeStore, works: List<List<ItemKey>>): List<Map<String, FloatArray>> {
        val bySource = works.flatten().groupBy({ it.source }, { it.id }).mapValues { it.value.distinct() }
        val out = List(works.size) { HashMap<String, FloatArray>() }
        SetKind.entries.forEach { kind ->
            val encoder = sets.encoderOf(kind)
            val used = encoder?.let { e -> type.embeddings.find(kind)?.takeIf { it.encoder == e } } ?: return@forEach
            val members = bySource.mapValues { (source, ids) ->
                type.source(source)?.let { s -> sets.members(s, kind, used.encoder, ids) }.orEmpty()
            }
            works.forEachIndexed { w, items ->
                val all = items.flatMap { members[it.source]?.get(it.id).orEmpty() }
                used.embedding.of(all)?.let { out[w][kind.key] = it }
            }
        }
        return out
    }

    companion object {
        /** A review as its set reads it. */
        fun textOf(review: String) = ReviewRepository.textOf(review)
    }
}

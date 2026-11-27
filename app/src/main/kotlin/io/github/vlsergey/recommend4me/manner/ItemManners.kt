package io.github.vlsergey.recommend4me.manner

import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.part.PartMark
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.vector.Vectors

/**
 * THE MANNERS OF THE ITEMS A TURN OF THE STAGE TAKES ([Manners]), by their keys: of an item whose texts, or the words
 * it was measured by, are other than its manner was made of, made anew by the [MannerWords]; of an item of no text now,
 * or of a text of no word of them, taken away. Their texts read a few items at a time.
 */
internal object ItemManners {

    /** The manners of the [items] of the [type] made by the [words] where they changed; the items whose changed. */
    fun made(type: TypeStore, words: MannerWords, items: Collection<ItemKey>): Set<ItemKey> {
        val changed = HashSet<ItemKey>()
        items.groupBy({ it.source }, { it.id }).forEach { (source, ids) ->
            val s = type.source(source) ?: return@forEach
            val hashes = s.parts.marks.of(ids).mapValues { (_, parts) -> hashOf(parts) }
            val made = s.manner.madeOf(ids)
            val gone = HashSet(made.keys - hashes.keys)
            val todo = hashes.filter { (id, hash) -> made[id] != MannerMade(words.id, hash) }
            todo.keys.chunked(CHUNK).forEach { chunk ->
                val contents = s.parts.contentsOf(chunk)
                chunk.forEach { id ->
                    val manner = contents[id]?.let { parts -> words.of(parts.map { it.third }) }
                    when {
                        manner != null -> {
                            s.manner.save(id, MannerMade(words.id, todo.getValue(id)), manner)
                            changed += ItemKey(source, id)
                        }
                        // A text of no word of the words: no manner, as of no text
                        id in made -> gone += id
                    }
                }
            }
            s.manner.remove(gone)
            gone.forEach { changed += ItemKey(source, it) }
        }
        return changed
    }

    /** What the texts of an item's parts are, of their hashes: its manner is made again when it changes. */
    fun hashOf(parts: List<PartMark>): String = Vectors.hash(parts.joinToString("\n") { "${it.partId}:${it.hash}" })

    /** The items whose texts are read at once: memory, not meaning. */
    private const val CHUNK = 50
}

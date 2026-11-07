package io.github.vlsergey.recommend4me.author

import io.github.vlsergey.recommend4me.api.model.AuthorWork
import io.github.vlsergey.recommend4me.api.model.WorkAuthor
import io.github.vlsergey.recommend4me.correction.Corrected
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.source.TypeStore

/**
 * What the works [keys] are shown by, read in one go: their titles, and the authors of those of several, by their names
 * and pages on their sites.
 */
internal class ShownWorks(private val type: TypeStore, library: AuthorLibrary, keys: Collection<ItemKey>) {
    private val titles = titles(type, keys)
    private val writers = HashMap<ItemKey, List<WorkAuthor>>()

    init {
        keys
            .distinct()
            .filter { (library.writersOf[it]?.size ?: 0) > 1 }
            .groupBy { it.source }
            .forEach { (source, own) ->
                val s = type.source(source) ?: return@forEach
                val facet = authorFacetOf(s) ?: return@forEach
                val names = s.names(facet, own.flatMap { library.writersOf[it].orEmpty().keys }.toSet())
                own.forEach { key ->
                    writers[key] =
                        library.writersOf[key].orEmpty().map { (author, id) ->
                            val name = names[author] ?: author
                            WorkAuthor(id, author, name, s.source.valueUrl(facet, author, name))
                        }
                }
            }
    }

    /** The work [key] as shown of the normalized [author]: the others of its authors beside it. */
    fun of(key: ItemKey, author: Long) =
        AuthorWork(
            key.source,
            key.id,
            titles[key] ?: key.id,
            type.source(key.source)?.source?.itemUrl(key.id).orEmpty(),
            writers[key].orEmpty().filter { it.id != author },
        )

    private companion object {
        /** The titles of the works as the user sees them: each source's read in one go. */
        fun titles(type: TypeStore, keys: Collection<ItemKey>): Map<ItemKey, String> {
            val out = HashMap<ItemKey, String>()
            keys
                .distinct()
                .groupBy { it.source }
                .forEach { (source, own) ->
                    val s = type.source(source) ?: return@forEach
                    val ids = own.map { it.id }
                    val heads = s.items.heads(ids)
                    val fields = s.corrections.fieldsOf(ids)
                    ids.forEach { id ->
                        heads[id]?.let { out[ItemKey(source, id)] = Corrected.title(it.title, fields[id].orEmpty()) }
                    }
                }
            return out
        }
    }
}

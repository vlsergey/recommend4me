package io.github.vlsergey.recommend4me.worklist

import io.github.vlsergey.recommend4me.folder.DataFolder
import java.util.concurrent.ConcurrentHashMap
import org.springframework.beans.factory.DisposableBean
import org.springframework.stereotype.Component

/** The index of the list of every content type ([ListIndex]), in `<data-dir>/types/<type>/list-index`. */
@Component
class ListIndexes(private val folder: DataFolder) : DisposableBean {

    /** The open indexes — files, not data: a writer is opened once and held. */
    private val open = ConcurrentHashMap<String, ListIndex>()

    /** The index of the content type [typeId]. */
    fun of(typeId: String): ListIndex = open.computeIfAbsent(typeId) { ListIndex(folder.type(it).resolve(FOLDER)) }

    override fun destroy() = open.values.forEach { it.close() }

    private companion object {
        const val FOLDER = "list-index"
    }
}

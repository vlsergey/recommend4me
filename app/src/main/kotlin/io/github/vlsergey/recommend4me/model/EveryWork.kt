package io.github.vlsergey.recommend4me.model

import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.item.ItemKeys
import io.github.vlsergey.recommend4me.laps.Laps
import io.github.vlsergey.recommend4me.likeness.Likenesses
import io.github.vlsergey.recommend4me.likeness.TypeLikeness
import io.github.vlsergey.recommend4me.manner.TypeManners
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.series.TypeSeries
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.work.Works
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

private val log = LoggerFactory.getLogger(EveryWork::class.java)

/**
 * EVERY WORK OF A CONTENT TYPE READ AS THE MODEL READS IT, NEVER ALL AT ONCE: a type of a million works — ficbook's
 * dump — held whole was more than the memory (2026-11-17). A scoring reads them a [PORTION] at a time
 * ([forEachPortion]); a training reads the works it learns of alone ([training]) and the average work counted of every
 * work as it streams past ([averageWork]), nothing of a work kept once counted.
 */
@Component
class EveryWork(
    private val reader: CatalogueReader,
    private val plugins: Plugins,
    private val works: Works,
    private val likenesses: Likenesses,
) {

    /**
     * What a training of the [type] learns of: the works graded or marked of interest, each with all its items, as
     * [CatalogueReader.gradedWorks] reads them, with the average work counted now of every work ([averageWork]) and the
     * likeness to the marks made anew for the training ([withLikeness]) or as kept.
     */
    fun training(type: TypeStore, withLikeness: Boolean = true): Catalogue {
        val average = averageWork(type)
        val interest = interestOf(type)
        return reader.gradedWorks(type, average.averages, interest.keys, ratingsOf(type), interest) { graded ->
            if (withLikeness) likenesses.forTraining(type, average.pictureCenter, graded) else likenesses.stored(type)
        }
    }

    /**
     * THE AVERAGE WORK OF THE [type], counted of every work as it streams past: the vectors of every source in one pass
     * of each kind, every work's features and manners a portion at a time ([forEachPortion]).
     */
    fun averageWork(type: TypeStore): AverageWork {
        val started = System.currentTimeMillis()
        val count = AverageCount(plugins.textEncoder(), plugins.imageEncoder(), type.embeddings.ids())
        type.sources.forEach { s ->
            val laps = Laps()
            count.read(s, laps)
            log.info("{}/{}: the vectors of every work counted in ms: {}", type.id, s.id, laps)
        }
        val read = System.currentTimeMillis()
        var counted = 0
        forEachPortion(type, likenesses.stored(type)) { portion ->
            portion.catalogue.items.forEach { work ->
                count.add(work, portion.own.getValue(work.key), portion.manners.blocks(work.key))
            }
            counted += portion.catalogue.items.size
        }
        return count.made().also {
            log.info(
                "{}: the average work counted of {} works in {} ms: the vectors {}, the works {}",
                type.id,
                counted,
                System.currentTimeMillis() - started,
                read - started,
                System.currentTimeMillis() - read,
            )
        }
    }

    /**
     * EVERY WORK OF THE [type], a [PORTION] of works at a time, each with all its items: the memory of a portion alone
     * in use whatever the number of the works. A portion's vectors are read as it is scored
     * ([Catalogue.vectorsOfMany]), the likeness to the marks by [likeness].
     */
    fun forEachPortion(type: TypeStore, likeness: TypeLikeness, action: (WorkPortion) -> Unit) {
        val pass = Pass(type, likeness)
        type.sources.forEach { s ->
            var after: String? = null
            do {
                val keys = s.items.keysAfter(after, PORTION)
                after = keys.lastOrNull()?.id
                pass.portion(s, keys)?.let(action)
            } while (keys.size == PORTION)
        }
    }

    /** One pass over every work of the [type]: what every portion of it reads alike, read once. */
    private inner class Pass(private val type: TypeStore, private val likeness: TypeLikeness) {
        private val severalSources = type.sources.size > 1
        private val ratings = ratingsOf(type)
        private val interest = interestOf(type)
        private val clusters = works.of(type)
        private val series = TypeSeries.read(type)
        private val none = Averages(emptyMap(), emptyMap())

        /**
         * The works the items [keys] of the source [s] name, each with all its items; null when they name none — every
         * one of a work of several named by an item of another site.
         */
        fun portion(s: SourceStore, keys: List<ItemKeys>): WorkPortion? {
            val named = keys.map { ItemKey(s.id, it.id) }.filter { clusters.representative(it) == it }
            if (named.isEmpty()) return null
            val members = named.flatMap(clusters::members)
            val own = keys.associateBy { it.id }
            val items =
                members.groupBy({ it.source }, { it.id }).flatMap { (source, ids) ->
                    val of = type.source(source) ?: return@flatMap emptyList()
                    // The items of the works of several of other sites, or further in this one, read by their ids
                    val heads = ids.mapNotNull { if (source == s.id) own[it] else null }.takeIf { it.size == ids.size }
                    reader.readItems(of, severalSources, series, heads ?: of.items.keysOf(ids))
                }
            val manners = TypeManners.of(type, clusters, members)
            // The vectors of the works of several items read now: a work's are made of all of its items'
            val linked = members.filter(clusters::linked)
            val vectors = manners.with(reader.vectorsOfMany(type, linked), linked)
            val catalogue = reader.catalogue(type, items, ratings, vectors, likeness, interest, none, manners)
            return WorkPortion(catalogue, items.associateBy { it.key }, manners)
        }
    }

    companion object {
        /**
         * The works read at a time by [forEachPortion]: of a million works a few hundred megabytes in use, each a few
         * hundred values and numbers, and their vectors a chunk at a time as they are scored.
         */
        const val PORTION = 10_000
    }
}

/**
 * A PORTION OF THE WORKS of a content type as [EveryWork.forEachPortion] reads them: the works' [catalogue], each item
 * of them as read of its own site ([own]), and the manners of the works.
 */
class WorkPortion(val catalogue: Catalogue, val own: Map<ItemKey, CatalogueItem>, val manners: TypeManners)

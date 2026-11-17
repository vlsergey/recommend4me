package io.github.vlsergey.recommend4me.model

import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.laps.Laps
import io.github.vlsergey.recommend4me.likeness.Likenesses
import io.github.vlsergey.recommend4me.likeness.TypeLikeness
import io.github.vlsergey.recommend4me.manner.TypeManners
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.series.TypeSeries
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
        val severalSources = type.sources.size > 1
        val ratings = ratingsOf(type)
        val interest = interestOf(type)
        val clusters = works.of(type)
        val series = TypeSeries.read(type)
        val heads = type.sources.associate { s -> s.id to s.items.keys().associateBy { it.id } }
        val named = heads.flatMap { (source, of) ->
            of.keys.map { ItemKey(source, it) }.filter { clusters.representative(it) == it }
        }
        val none = Averages(emptyMap(), emptyMap())
        named.chunked(PORTION).forEach { portion ->
            val members = portion.flatMap(clusters::members)
            val items =
                members.groupBy({ it.source }, { it.id }).flatMap { (source, ids) ->
                    val s = type.source(source) ?: return@flatMap emptyList()
                    val of = heads[source].orEmpty()
                    reader.readItems(s, severalSources, series, ids.mapNotNull(of::get))
                }
            val manners = TypeManners.of(type, clusters, members)
            // The vectors of the works of several items read now: a work's are made of all of its items'
            val linked = members.filter(clusters::linked)
            val vectors = manners.with(reader.vectorsOfMany(type, linked), linked)
            val catalogue = reader.catalogue(type, items, ratings, vectors, likeness, interest, none, manners)
            action(WorkPortion(catalogue, items.associateBy { it.key }, manners))
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

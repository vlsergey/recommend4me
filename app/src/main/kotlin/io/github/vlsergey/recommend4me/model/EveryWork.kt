package io.github.vlsergey.recommend4me.model

import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.item.ItemKeyList
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
 * dump — held whole was more than the memory (2026-11-17). The sweep of the scores reads them a [PORTION] at a time
 * from where it stopped ([portions], [ScoreSweep]); a training reads the works it learns of alone ([training]) with the
 * average work as the last sweep counted it and kept ([AverageWorkRepository]) — counted of every work as it streams
 * past ([averageWork]) only when none is kept, nothing of a work kept once counted.
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
     * [CatalogueReader.gradedWorks] reads them, with the average work kept ([averageOf]) and the likeness to the marks
     * made anew for the training ([withLikeness]) or as kept.
     */
    fun training(type: TypeStore, withLikeness: Boolean = true): Catalogue {
        val average = averageOf(type)
        val interest = interestOf(type)
        return reader.gradedWorks(type, average.averages, interest.keys, ratingsOf(type), interest) { graded ->
            if (withLikeness) likenesses.forTraining(type, average.pictureCenter, graded) else likenesses.stored(type)
        }
    }

    /**
     * The average work of the [type] as the last sweep of the scores counted it, of the vectors as they are made now
     * ([AverageWork.basis]); when none is kept — the first training, the encoders changed — counted now of every work
     * ([averageWork]) and kept.
     */
    fun averageOf(type: TypeStore): AverageWork {
        val basis = AverageWork.basis(plugins, type)
        return type.averageWork.load(basis) ?: averageWork(type).also { type.averageWork.save(it, basis) }
    }

    /**
     * THE AVERAGE WORK OF THE [type], counted of every work as it streams past: the vectors of every source in one pass
     * of each kind, every work's features and manners a portion at a time ([portions]) — no vectors of a portion read,
     * nor the sets of its works of several items made.
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
        Pass(type, likenesses.stored(type), seen = null, vectors = false).walk(null) { portion ->
            count.addAll(portion)
            counted += portion.catalogue.items.size
            true
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
     * EVERY WORK OF THE [type] FROM [from] ON (from the first when null), a [portion] of works at a time, each with all
     * its items, till [action] answers false: the memory of a portion alone in use whatever the number of the works. A
     * portion's vectors are read as it is scored ([Catalogue.vectorsOfMany]), [seen] told of every one, the likeness to
     * the marks by [likeness]. Answers where the walk stopped — the portion to go on from — or null when every work was
     * read.
     */
    internal fun portions(
        type: TypeStore,
        likeness: TypeLikeness,
        from: PortionCursor?,
        seen: VectorSeen? = null,
        portion: Int = PORTION,
        action: (WorkPortion) -> Boolean,
    ): PortionCursor? = Pass(type, likeness, seen, vectors = true, portion).walk(from, action)

    /**
     * One pass over every work of the [type]: what every portion of it reads alike, read once. Of a pass that reads no
     * [vectors], the works of several items are read without theirs, their sets not made.
     */
    private inner class Pass(
        private val type: TypeStore,
        private val likeness: TypeLikeness,
        private val seen: VectorSeen?,
        private val vectors: Boolean,
        private val size: Int = PORTION,
    ) {
        private val severalSources = type.sources.size > 1
        private val ratings = ratingsOf(type)
        private val interest = interestOf(type)
        private val clusters = works.of(type)
        private val series = TypeSeries.read(type)
        private val none = Averages(emptyMap(), emptyMap())

        /**
         * The portions from [from] on — of the sources in their order, of each the items in the order of their ids
         * ([ItemKeyList.keysAfter]) — handed to [action] till it answers false; where the walk stopped, null at the
         * end.
         */
        fun walk(from: PortionCursor?, action: (WorkPortion) -> Boolean): PortionCursor? {
            // A source gone since the walk stopped: from the first again
            val first = from?.let { at -> type.sources.indexOfFirst { it.id == at.source } }?.takeIf { it >= 0 }
            var after = if (first == null) null else from?.after
            for (i in (first ?: 0) until type.sources.size) {
                val s = type.sources[i]
                do {
                    val keys = s.items.keysAfter(after, size)
                    after = keys.lastOrNull()?.id ?: after
                    val more = keys.size == size
                    val next = if (more) PortionCursor(s.id, after) else type.sources.getOrNull(i + 1)?.let(::start)
                    if (portion(s, keys)?.let(action) == false) return next
                } while (more)
                after = null
            }
            return null
        }

        /**
         * The works the items [keys] of the source [s] name, each with all its items; null when they name none — every
         * one of a work of several named by an item of another site.
         */
        fun portion(s: SourceStore, keys: List<ItemKeys>): WorkPortion? {
            val named = keys.map { ItemKey(s.id, it.id) }.filter { clusters.representative(it) == it }
            if (named.isEmpty()) return null
            val laps = Laps()
            val members = named.flatMap(clusters::members)
            val own = keys.associateBy { it.id }
            val idsOf = members.groupBy({ it.source }, { it.id })
            // The site's values of the portion read once, for the works and their manners alike
            val values =
                idsOf
                    .mapNotNull { (source, ids) -> type.source(source)?.let { source to it.items.facetsOf(ids) } }
                    .toMap()
            laps.lap("values", members.size)
            val items = idsOf.flatMap { (source, ids) ->
                val of = type.source(source) ?: return@flatMap emptyList()
                // The items of the works of several of other sites, or further in this one, read by their ids
                val heads = ids.mapNotNull { if (source == s.id) own[it] else null }.takeIf { it.size == ids.size }
                val read = values[source]
                reader.readItems(
                    of,
                    severalSources,
                    series,
                    heads ?: of.items.keysOf(ids),
                    laps.within("$source:"),
                    read,
                )
            }
            laps.restart()
            val manners = TypeManners.of(type, clusters, members, values)
            laps.lap("manners")
            // The vectors of the works of several items read now: a work's are made of all of its items'
            val linked = members.filter(clusters::linked)
            val read = if (vectors) manners.with(reader.vectorsOfMany(type, linked, seen), linked) else emptyMap()
            val catalogue =
                reader.catalogue(
                    type,
                    items,
                    ratings,
                    read,
                    likeness,
                    interest,
                    none,
                    clusters,
                    manners,
                    seen,
                    sets = vectors,
                )
            laps.lap("works of several", linked.size)
            log.info("{}/{}: a portion of {} works read in ms: {}", type.id, s.id, catalogue.items.size, laps)
            return WorkPortion(catalogue, items.associateBy { it.key }, manners)
        }
    }

    companion object {
        /**
         * The works read at a time by [portions]: of a million works a few hundred megabytes in use, each a few hundred
         * values and numbers, and their vectors a chunk at a time as they are scored.
         */
        const val PORTION = 10_000
    }
}

/**
 * Where a walk over every work stopped ([EveryWork.portions]): the [source] it goes on with, and the last id of its
 * items read ([after]; none of a source not begun).
 */
data class PortionCursor(val source: String, val after: String?)

/** The beginning of the source [s]. */
private fun start(s: SourceStore) = PortionCursor(s.id, null)

/**
 * A PORTION OF THE WORKS of a content type as [EveryWork.portions] reads them: the works' [catalogue], each item of
 * them as read of its own site ([own]), and the manners of the works.
 */
class WorkPortion(val catalogue: Catalogue, val own: Map<ItemKey, CatalogueItem>, val manners: TypeManners)

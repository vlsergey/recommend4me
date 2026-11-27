package io.github.vlsergey.recommend4me.work

import io.github.vlsergey.recommend4me.author.authorFacetOf
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.series.TypeSeries
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.worklist.WorkDatum
import java.time.Instant
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * THE AUTHORS AND THE SERIES OF THE WORKS CHANGED, DATED ([WorkDatum.GROUPS]): of normalized authors merged or an
 * author of a site taken out of one, every work of the items naming the authors of their sites; of works put in a
 * series of the user's or out of it, every work of the series they are of now, of the sites' series joined by the
 * user's groups too ([TypeSeries.around]). Found by their keys, written as they are told: the stages reading them take
 * these works by the date ([io.github.vlsergey.recommend4me.worklist.StageSweeps]), no work compared with another.
 */
@Component
class WorkGroupDates(private val stores: Stores) {

    @EventListener
    fun regrouped(event: WorkGroupChangeEvent) {
        val type = stores.type(event.type) ?: return
        val now = Instant.now()
        val named =
            event.authors.groupBy({ it.source }, { it.author }).flatMap { (source, authors) ->
                val s = type.source(source) ?: return@flatMap emptyList()
                val facet = authorFacetOf(s) ?: return@flatMap emptyList()
                s.items.itemsWith(facet, authors).values.flatten().distinct().map { ItemKey(source, it) }
            }
        if (named.isNotEmpty()) type.works.changed(named, WorkDatum.GROUPS, now)
        if (event.works.isNotEmpty()) {
            val series = TypeSeries.around(type, event.works)
            type.works.worksChanged(event.works + series.all.flatMap { it.works }, WorkDatum.GROUPS, now)
        }
    }
}

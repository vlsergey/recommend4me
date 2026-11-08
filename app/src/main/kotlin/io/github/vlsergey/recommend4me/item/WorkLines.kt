package io.github.vlsergey.recommend4me.item

import io.github.vlsergey.recommend4me.api.model.Prediction
import io.github.vlsergey.recommend4me.api.model.RelatedWork
import io.github.vlsergey.recommend4me.correction.Corrected
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.TypeStore
import org.springframework.stereotype.Component

/**
 * WORKS AS A LINE OF A LIST — among a work's author's works, in a series: their titles as the user corrected them, the
 * user's grades, the model's predictions; no card, so none of their values and chances read.
 */
@Component
class WorkLines(private val cards: ItemCards) {

    /** The works [ids] of the [store] as lines: item to its line, of the items there are. */
    fun of(type: TypeStore, store: SourceStore, ids: List<String>): Map<String, RelatedWork> {
        if (ids.isEmpty()) return emptyMap()
        val heads = store.items.heads(ids)
        val fields = store.corrections.fieldsOf(ids)
        val graded = cards.graded(store.ratings.ofItems(ids), heads.mapValues { it.value.version })
        val predicted = type.predictions.predicted(store.id, ids)
        return ids.mapNotNull { id ->
                heads[id]?.let { head ->
                    id to
                        RelatedWork(
                            source = store.id,
                            item = id,
                            title = Corrected.title(head.title, fields[id].orEmpty()),
                            grade = graded[id]?.grade,
                            prediction = predicted[id]?.let { Prediction(it.score) },
                        )
                }
            }
            .toMap()
    }
}

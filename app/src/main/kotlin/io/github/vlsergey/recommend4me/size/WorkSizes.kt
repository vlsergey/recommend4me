package io.github.vlsergey.recommend4me.size

import io.github.vlsergey.recommend4me.api.model.SizeNumber
import io.github.vlsergey.recommend4me.correction.Corrected
import io.github.vlsergey.recommend4me.duplicate.ComparedValues
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.worklist.ListIndexes
import org.springframework.stereotype.Service

/**
 * The size of an item as it is shown: its words and characters, each stated by its site or worked out; none when
 * neither is known.
 */
class ItemSize(val words: SizeNumber?, val chars: SizeNumber?)

/**
 * HOW LONG EVERY WORK IS, IN WORDS AND IN CHARACTERS, whichever of them its site states: a site tells one of them, so
 * the other comes of how many words a site item has to a character ([KeptRatios], fitted by [WordsPerCharFitting]).
 */
@Service
class WorkSizes(private val indexes: ListIndexes) {

    /**
     * The sizes of the items [ids] of the source, of their [numbers] with the user's word over them: what the site
     * states as it is, the other worked out of it by the item's ratio ([KeptRatios]) of its values the index keeps, by
     * their keys. Read for the operation.
     */
    fun ofItems(
        type: TypeStore,
        s: SourceStore,
        ids: List<String>,
        numbers: Map<String, Map<String, Double>>,
        fields: Map<String, Map<String, String>>,
    ): Map<String, ItemSize> {
        if (s.wordsKey() == null && s.charsKey() == null) return emptyMap()
        val compared = ComparedValues(s, indexes.of(type.id)).of(ids, emptyList())
        val ratios = type.sizes.model().of(s.id, compared) { s.parts.counts.counted(ids) }
        return sized(s, ids, numbers, fields, ratiosOf(compared.ids, ratios))
    }

    /** The [ratios] of the items [ids] by their places, by the items; none of no model. */
    private fun ratiosOf(ids: List<String>, ratios: DoubleArray?): Map<String, Double> =
        if (ratios == null) emptyMap() else ids.indices.associate { ids[it] to ratios[it] }

    /** The sizes of the items [ids] of what is read of them: their numbers, the fields the user wrote, their ratios. */
    private fun sized(
        s: SourceStore,
        ids: List<String>,
        numbers: Map<String, Map<String, Double>>,
        fields: Map<String, Map<String, String>>,
        ratios: Map<String, Double>,
    ): Map<String, ItemSize> {
        val words = s.wordsKey()
        val chars = s.charsKey()
        return ids.associateWith { id ->
            val own = Corrected.numbers(numbers[id].orEmpty(), fields[id].orEmpty())
            val statedWords = words?.let { own[it] }?.takeIf { it > 0 }
            val statedChars = chars?.let { own[it] }?.takeIf { it > 0 }
            val ratio = ratios[id]
            ItemSize(
                statedWords?.let { SizeNumber(it, true) }
                    ?: ratio?.let { r -> statedChars?.let { SizeNumber(it * r, false) } },
                statedChars?.let { SizeNumber(it, true) }
                    ?: ratio?.let { r -> statedWords?.let { SizeNumber(it / r, false) } },
            )
        }
    }
}

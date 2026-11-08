package io.github.vlsergey.recommend4me.model

import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.suggestion.Chance

/**
 * A WORK AS A READING OF IT HAS READ IT ALREADY: its items ([members], the item read first), and their chances of the
 * facets the model of the grades reads at least — what the model's input of it is made of, not read again
 * ([Scoring.inputOf]).
 */
class ReadWork(val members: List<ItemKey>, val chances: Map<ItemKey, Map<String, Map<String, Chance>>>)

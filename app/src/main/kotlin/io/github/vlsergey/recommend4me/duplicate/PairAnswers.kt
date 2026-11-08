package io.github.vlsergey.recommend4me.duplicate

import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.work.Works
import org.springframework.stereotype.Component

/**
 * The user's word on a pair: one work; different works; or related works ([related]) — one the TRANSLATION of the
 * other, one holding a PARTIAL of the other's text (a site's fewer chapters, a collection), two works of one SERIES
 * (the volumes of a cycle) — not linked, each its own grade and interest. A translation and a part are left out of the
 * model of one work ([PairModels.fit]): a translation's authors differ while its tags and description are the other's —
 * neither one work nor two; two works of one series are two works, and what the model of a series learns from.
 */
enum class DuplicateAnswer {
    SAME,
    DIFFERENT,
    TRANSLATION,
    PARTIAL,
    SERIES;

    /**
     * Related works, kept by their kind ([io.github.vlsergey.recommend4me.correction.TypeLinkRepository.relate]): not
     * linked.
     */
    val related: Boolean
        get() = this == TRANSLATION || this == PARTIAL || this == SERIES
}

/**
 * The user's word on every pair, the smaller key first ([words]), and of them the pairs of one series by a chain of
 * words "one series" ([derived], [io.github.vlsergey.recommend4me.correction.PairWords.inSeries]): weighing as one
 * said, told apart only to be shown so.
 */
class Said(private val words: Map<Pair<ItemKey, ItemKey>, DuplicateAnswer>, val derived: Set<Pair<ItemKey, ItemKey>>) :
    Map<Pair<ItemKey, ItemKey>, DuplicateAnswer> by words

/**
 * THE USER'S WORD ON EVERY PAIR of works across the sites of a type, read anew at every asking: a word is on the two
 * normalized works, of every pair of their items alike; two works of one series by a chain of words are of one series
 * as if said so.
 */
@Component
class PairAnswers(private val works: Works) {

    /**
     * The user's word on every pair across the sites, the smaller key first: one work (linked, by any way), different,
     * related — one series by the chain too.
     */
    fun said(type: TypeStore): Said {
        val out = HashMap<Pair<ItemKey, ItemKey>, DuplicateAnswer>()
        type.links.distincts().forEach { out[it] = DuplicateAnswer.DIFFERENT }
        type.links.relations().forEach { (pair, kind) ->
            runCatching { DuplicateAnswer.valueOf(kind) }.getOrNull()?.takeIf { it.related }?.let { out[pair] = it }
        }
        val derived = type.links.inSeries().filter { it !in out }.toSet()
        derived.forEach { out[it] = DuplicateAnswer.SERIES }
        works.of(type).linkedWorks().forEach { members ->
            members.forEachIndexed { k, a ->
                // Of one site too: the pairs are looked for on every site, its own among them
                members.drop(k + 1).forEach { b -> out[type.links.ordered(a, b)] = DuplicateAnswer.SAME }
            }
        }
        return Said(out, derived)
    }
}

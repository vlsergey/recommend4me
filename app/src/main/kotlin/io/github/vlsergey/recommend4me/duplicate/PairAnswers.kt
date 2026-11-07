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
 * THE USER'S WORD ON EVERY PAIR of works across the sites of a type, read anew at every asking: a word is on the two
 * normalized works, of every pair of their items alike.
 */
@Component
class PairAnswers(private val works: Works) {

    /**
     * The user's word on every pair across the sites, the smaller key first: one work (linked, by any way), different,
     * related.
     */
    fun said(type: TypeStore): Map<Pair<ItemKey, ItemKey>, DuplicateAnswer> {
        val out = HashMap<Pair<ItemKey, ItemKey>, DuplicateAnswer>()
        type.links.distincts().forEach { out[it] = DuplicateAnswer.DIFFERENT }
        type.links.relations().forEach { (pair, kind) ->
            runCatching { DuplicateAnswer.valueOf(kind) }.getOrNull()?.takeIf { it.related }?.let { out[pair] = it }
        }
        works.of(type).linkedWorks().forEach { members ->
            members.forEachIndexed { k, a ->
                members
                    .drop(k + 1)
                    .filter { it.source != a.source }
                    .forEach { b -> out[type.links.ordered(a, b)] = DuplicateAnswer.SAME }
            }
        }
        return out
    }
}

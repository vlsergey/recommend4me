package io.github.vlsergey.recommend4me.duplicate

import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.settings.Settings
import io.github.vlsergey.recommend4me.source.TypeStore
import kotlin.math.ln
import org.springframework.stereotype.Component

/**
 * THE CHANCES OF A PAIR by the models of the user's answers, as kept: that it is one work ([same] — the model before
 * any answer when none is fitted) and that it is one series of works ([series] — none before any answer that a pair is
 * one). [learnt]: the model of one work is of the user's answers.
 */
class PairChances(val same: FittedPairModel, val series: FittedPairModel?, val learnt: Boolean) {
    fun same(c: DuplicateCandidate): Double = same.chance(c.layers)

    fun series(c: DuplicateCandidate): Double? = series?.chance(c.layers)

    /** The chance the pair is one work on the scale a logistic model adds up on. */
    fun logit(c: DuplicateCandidate): Double = scale(same(c))

    /** The chance the pair is one series on the scale of [logit]; none without a model of one series. */
    fun seriesLogit(c: DuplicateCandidate): Double? = series(c)?.let(::scale)

    /**
     * Where on the scale of the chance [at] — [logit], [seriesLogit] — the user's answers change: between the mean of
     * the [pairs] said to be of its kind and of those said not ([said], null of one not answered). The chance is of the
     * plugin's making, a half not promised to be where the answers change: what is in doubt is told by the answers.
     * Nought with neither kind of answer.
     */
    fun boundary(
        pairs: Collection<DuplicateCandidate>,
        at: (DuplicateCandidate) -> Double?,
        said: (DuplicateCandidate) -> Boolean?,
    ): Double {
        val yes = pairs.filter { said(it) == true }.mapNotNull(at)
        val no = pairs.filter { said(it) == false }.mapNotNull(at)
        return if (yes.isEmpty() || no.isEmpty()) 0.0 else (yes.average() + no.average()) / 2
    }

    private companion object {
        /** A chance of nought or one has no place on the scale of [logit]. */
        const val LEAST_CHANCE = 1e-12

        /** The [chance] on the scale a logistic model adds up on. */
        fun scale(chance: Double): Double = chance.coerceIn(LEAST_CHANCE, 1 - LEAST_CHANCE).let { ln(it / (1 - it)) }
    }
}

/**
 * THE MODELS OF THE PAIRS, a plugin's ([PairModel]): one a kind — one work, one series of works — fitted the same way
 * on the user's answers, kept with the search ([DuplicateSearch]) and read back by the plugin that made them.
 */
@Component
class PairModels(private val plugins: Plugins, private val settings: Settings) {

    /**
     * The plugin of pairs a content type's pairs are looked for and compared by: the one named by the setting
     * `duplicates.model.<type>`, else the first; null when none is installed — no pair is looked for then.
     */
    fun model(typeId: String): PairModel? =
        plugins.pairModels.firstOrNull { it.id == settings.get(setting(typeId)) } ?: plugins.pairModels.firstOrNull()

    /** The plugin of pairs [id] made the one of the type; false of no such plugin. */
    fun choose(typeId: String, id: String): Boolean {
        if (plugins.pairModels.none { it.id == id }) return false
        settings.set(setting(typeId), id)
        return true
    }

    /**
     * The models kept of the [type], unpacked by the plugin in use when it made them; else the model before any answer
     * of one work, and none of a series. Null without a plugin of pairs.
     */
    fun of(type: TypeStore): PairChances? {
        val model = model(type.id) ?: return null
        val search = type.duplicates.search()?.takeIf { it.model == model.id }
        val same = search?.same?.let(model::unpack)
        return PairChances(same ?: model.prior(), search?.series?.let(model::unpack), same != null)
    }

    companion object {
        /** The setting the plugin of pairs of a content type is named by. */
        fun setting(typeId: String) = "duplicates.model.$typeId"

        /**
         * The models of the user's answers on the [pairs] ([said]) fitted by the [model]: of one work, every pair not
         * answered on a likely different one, a pair said one series a different one, the translations and the parts
         * left out (user, 2026-10-30: a translation's authors differ while its tags and description are the other's, a
         * part is neither one nor two); of one series, every answer but the series a pair not of one. None of a kind no
         * pair was said to be of.
         */
        fun fit(
            model: PairModel,
            pairs: Collection<DuplicateCandidate>,
            said: Map<Pair<ItemKey, ItemKey>, DuplicateAnswer>,
        ): FittedPairs {
            val ofSame = pairs.filter {
                said[it.pair]?.let { a -> a == DuplicateAnswer.TRANSLATION || a == DuplicateAnswer.PARTIAL } != true
            }
            val same =
                model.fit(
                    ofSame.map { it.layers },
                    ofSame.map { c -> said[c.pair]?.let { it == DuplicateAnswer.SAME } },
                )
            val all = pairs.toList()
            val series =
                model.fit(all.map { it.layers }, all.map { c -> said[c.pair]?.let { it == DuplicateAnswer.SERIES } })
            return FittedPairs(same, series)
        }
    }
}

/** The models of the kinds of pairs a fitting made: none of a kind no pair was said to be of. */
class FittedPairs(val same: FittedPairModel?, val series: FittedPairModel?)

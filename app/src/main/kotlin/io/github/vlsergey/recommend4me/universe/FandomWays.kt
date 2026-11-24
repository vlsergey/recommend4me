package io.github.vlsergey.recommend4me.universe

import io.github.vlsergey.recommend4me.settings.Settings
import io.github.vlsergey.recommend4me.tag.TagVectors
import org.springframework.stereotype.Component

/**
 * The ways a site's fandom is read for its likeness: its name alone, its description a synonym of the name — a universe
 * alike by either —, or its description added to its name — the sum of their unit vectors.
 */
enum class FandomWay {
    NAMES,
    SYNONYMS,
    ADDED;

    /** The vectors of a site's fandom [v] read this way, of unit length. */
    fun of(v: FandomVectorsOf): List<FloatArray> =
        when (this) {
            NAMES -> listOf(v.name)
            SYNONYMS -> v.all
            ADDED ->
                listOf(
                    v.description?.let { d -> TagVectors.unit(FloatArray(v.name.size) { v.name[it] + d[it] }) }
                        ?: v.name
                )
        }
}

/**
 * THE WAY THE UNIVERSES ALIKE ONE ARE FOUND BY ([UniverseViews.similar]): the user's choice of the page of the models,
 * where the ways are compared ([FandomWayComparisons]); the description a synonym of the name till another is chosen
 * (user, 2026-11-14).
 */
@Component
class FandomWays(private val settings: Settings) {

    fun chosen(): FandomWay =
        settings.get(SETTING)?.let { name -> FandomWay.entries.firstOrNull { it.name == name } } ?: FandomWay.SYNONYMS

    fun choose(way: FandomWay) = settings.set(SETTING, way.name)

    private companion object {
        const val SETTING = "universes.fandomWay"
    }
}

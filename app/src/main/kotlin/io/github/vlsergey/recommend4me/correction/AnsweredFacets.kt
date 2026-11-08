package io.github.vlsergey.recommend4me.correction

import io.github.vlsergey.recommend4me.api.model.AnsweredFacet
import io.github.vlsergey.recommend4me.api.model.NamedValue
import io.github.vlsergey.recommend4me.api.model.ValueAnswer
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.universe.UniverseFacet

/**
 * WHAT THE USER'S ANSWERS MAKE OF A FACET OF A WORK, read of the answers alone — no chance, nothing of the model — for
 * the page to show an answer whole at once: of an answer on the [facet], that facet and its other facet of characters,
 * where the answer takes a character from ([Answering.write]). Of every one, the user's answers on it, the tags the
 * work has by a tag under it the user said it has, and the values shown no more: the tags under one the user said it
 * has not, the characters of the other facet ([UniverseFacet.leftOut]) — as the work read anew shows them.
 */
object AnsweredFacets {

    fun of(store: SourceStore, item: String, facet: String): List<AnsweredFacet> {
        val corrections = store.corrections.facetsOf(item)
        val hierarchies = store.hierarchies()
        val said = corrections.filter { it.added }.groupBy({ it.facet }, { it.key })
        return listOfNotNull(facet, UniverseFacet.otherCharacters(facet)).map { f ->
            val own = corrections.filter { it.facet == f }
            val answered = own.map { it.key }.toSet()
            val hierarchy = hierarchies[f]
            // As the hierarchy reads the answers (TagHierarchy.expand): a tag said not the work's takes every tag
            // under it, a tag said the work's gives it every tag above it
            val rejected =
                own.filter { !it.added }.flatMap { listOf(it.key) + hierarchy?.descendants(it.key).orEmpty() }.toSet()
            val implied =
                own.filter { it.added && it.key !in rejected }
                    .flatMap { hierarchy?.ancestors(it.key).orEmpty() }
                    .toSet() - rejected - answered
            val out = UniverseFacet.leftOut(f, { said[it].orEmpty() }, { said[it].orEmpty() })
            val names = store.names(f, answered + implied)
            AnsweredFacet(
                facet = f,
                answers = own.map { ValueAnswer(it.key, it.added, names[it.key] ?: it.name) },
                implied = implied.map { NamedValue(it, names[it] ?: it, store.nameKey(f, it)) },
                hidden = ((rejected + out) - answered).toList(),
            )
        }
    }
}

package io.github.vlsergey.recommend4me.cast

import io.github.vlsergey.recommend4me.suggestion.InputBlock

/**
 * WHAT THE WORKS' LINES OF CHARACTERS AND PAIRINGS MAKE OF A FACET'S TASK, BY THE WAY CHOSEN ([CastAsked]): its
 * [inputs] — the line encoded whole taken out, the way's blocks in its place — of every work the columns of its context
 * ([context]: the entries of the line as the site keeps them taken out, the way's columns in their place), the values
 * its line names ([named]), and the [signature] of the reading, for the basis of the facet's model: another way, more
 * names encoded, is another task.
 */
internal class TaskCast(
    val inputs: List<InputBlock>,
    val context: List<List<String>>,
    val named: List<List<String>>,
    val signature: String,
) {
    companion object {
        /**
         * The task of the works [ids] — of each what its line says ([casts]), its [inputs] and the columns of its
         * context ([context]) — read by the way [asked] for.
         */
        fun of(
            asked: CastAsked,
            ids: List<String>,
            casts: List<SaidCast>,
            inputs: List<InputBlock>,
            context: List<List<String>>,
        ): TaskCast {
            val ofEntries = asked.entries + "\u0001"
            val entries = context.map { row -> row.filter { it.startsWith(ofEntries) } }
            val line = inputs.firstOrNull { it.name == asked.line }
            val texts = casts.flatMap { asked.reader.encoded(it) }.distinct()
            val vectors = if (texts.isEmpty()) emptyMap() else asked.vectors(texts)
            val read =
                asked.reader.read(
                    CastWorks(casts, line, entries, vectors, ids.map { asked.characters(it) }, asked.paired)
                )
            require(read.context.size == ids.size) {
                "${asked.reader.id}: ${read.context.size} contexts of ${ids.size} works"
            }
            return TaskCast(
                inputs.filter { it !== line } + read.inputs,
                context.mapIndexed { i, row -> row.filterNot { it.startsWith(ofEntries) } + read.context[i] },
                read.named,
                "${asked.reader.id}:${vectors.size}",
            )
        }
    }
}

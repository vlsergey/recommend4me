package io.github.vlsergey.recommend4me.flow

import io.github.vlsergey.recommend4me.Application
import io.github.vlsergey.recommend4me.source.Source
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.fail

/**
 * THE FLOW OF THE EVENTS AND THE WORKS IS WHOLE AND ENDS (owner, 2026-11-19), as the code makes it ([EventFlow]):
 * - nothing hangs: every event and every kind of work has an arrow into it from something but the start — a request, a
 *   loop of the background, an event, a work; the start only plans again what the last run left;
 * - nothing goes round: no event or work leads back to itself, through others or directly — a work planning itself
 *   again is a loop of the queue no change ends.
 */
class EventFlowTest {
    @Test
    fun `every event and every work has an arrow into it`() {
        val into = flow.edges.filterKeys { it != Node.Outside(EventFlow.START) }.values.flatten().toSet()
        val hanging = flow.nodes.filter { it !in into }.sorted()
        if (hanging.isNotEmpty()) fail("Events and works nothing but the start leads to: ${hanging.map { it.label }}")
    }

    @Test
    fun `no event or work leads back to itself`() {
        val inner = flow.edges.filterKeys { it is Node.Event || it is Node.Task }
        val rounds = Cycles.of(inner)
        val told = rounds.map { c -> c.joinToString(" -> ") { it.label } }
        if (rounds.isNotEmpty()) fail("Events and works leading back to themselves: $told")
    }

    private companion object {
        val flow: EventFlow by lazy {
            fun of(c: Class<*>) = Path.of(c.protectionDomain.codeSource.location.toURI())
            EventFlow(CodeRead.of(of(Application::class.java), of(Source::class.java)))
        }
    }
}

/** The rounds of a graph: of every group of nodes leading to one another, one round through them. */
internal object Cycles {
    /** Of every strongly connected group of [edges] of more than one node, or of a node leading to itself, a round. */
    fun of(edges: Map<Node, Set<Node>>): List<List<Node>> {
        val index = HashMap<Node, Int>()
        val low = HashMap<Node, Int>()
        val stack = ArrayDeque<Node>()
        val out = ArrayList<List<Node>>()
        var next = 0
        fun visit(v: Node) {
            index[v] = next
            low[v] = next
            next++
            stack.addLast(v)
            edges[v].orEmpty().forEach { w ->
                if (w !in index) {
                    visit(w)
                    low[v] = minOf(low.getValue(v), low.getValue(w))
                } else if (w in stack) low[v] = minOf(low.getValue(v), index.getValue(w))
            }
            if (low[v] == index[v]) {
                val group = ArrayList<Node>()
                do {
                    val w = stack.removeLast()
                    group += w
                } while (w != v)
                if (group.size > 1 || v in edges[v].orEmpty()) out += group.sorted()
            }
        }
        edges.keys.sorted().forEach { if (it !in index) visit(it) }
        return out.sortedBy { it.first() }
    }
}

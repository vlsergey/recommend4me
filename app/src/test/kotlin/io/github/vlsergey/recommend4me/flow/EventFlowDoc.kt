package io.github.vlsergey.recommend4me.flow

/**
 * THE DOCUMENT OF THE FLOW ([EventFlow]), docs/events.md: a diagram of what the start, the loops of the background, the
 * events and the works lead to (Mermaid, drawn by GitHub), and a table of what every request leads to — the requests
 * many, each a row, not a box of the diagram.
 */
internal object EventFlowDoc {
    fun of(edges: Map<Node, Set<Node>>): String {
        val out = StringBuilder()
        out.append(
            """
            |# The events and the works of the application
            |
            |Made of the compiled code by `EventFlowDocsTest` (`gradlew :app:test --tests "*EventFlowDocsTest*"`): a change
            |of the code that changes what leads to what fails the test, and the test writes this file anew — commit it.
            |What it reads is said in `EventFlow`: an event is an object of a class some `@EventListener` listens to; a work
            |is planned by `Tasks.plan` or run by `Tasks.run`, its kind named where it is planned.
            |
            |An arrow is "may lead to, directly": the events a thing makes, the works it plans. A request's events and
            |works go on as the diagram shows.
            |
            |## Legend
            |
            |```mermaid
            |flowchart LR
            |    outside(("from outside: the start, a loop of the background")) --> event["an event of the application"]
            |    event --> task{{"a task of the queue, by its kind"}}
            |    task --> event
            |```
            |
            |- a circle: what comes from outside the flow — `start`, the start of the application (the listeners of
            |  `ApplicationReadyEvent`) deciding of the data; `left by the last run`, the works the last run left in the
            |  queue planned again by their owners (`Tasks.resume`); a loop of the background by its name
            |  (`Background.start`, `every`, `endless`);
            |- a box: an event of the application, by its class;
            |- a hexagon: a task of the queue, by its `TaskKind`;
            |- a request of the interface or the extension: a row of the table below, by its operation's id.
            |
            |## What leads to what
            |
            |```mermaid
            |flowchart LR
            |"""
                .trimMargin()
        )
        out.append('\n')
        val inner = edges.filterKeys { it !is Node.Request }
        val nodes = (inner.keys + inner.values.flatten() + edges.values.flatten()).toSortedSet()
        nodes.forEach { out.append("    ").append(box(it)).append('\n') }
        inner.toSortedMap().forEach { (from, to) ->
            to.forEach { out.append("    ").append(id(from)).append(" --> ").append(id(it)).append('\n') }
        }
        out.append("```\n\n## The requests\n\n| Request | Events | Works |\n|---|---|---|\n")
        edges
            .filterKeys { it is Node.Request }
            .toSortedMap()
            .forEach { (from, to) ->
                fun names(of: List<Node>) = of.joinToString(", ") { it.label }.ifEmpty { "—" }
                out.append("| `${from.label}` | ")
                    .append(names(to.filterIsInstance<Node.Event>()))
                    .append(" | ")
                    .append(names(to.filterIsInstance<Node.Task>()))
                    .append(" |\n")
            }
        return out.toString()
    }

    private fun id(node: Node): String =
        when (node) {
            is Node.Request -> "r_"
            is Node.Outside -> "o_"
            is Node.Event -> "e_"
            is Node.Task -> "w_"
        } + node.label.replace(Regex("[^A-Za-z0-9]"), "_")

    /** A node drawn by its kind: the outside round, an event a box, a work a hexagon. */
    private fun box(node: Node): String =
        id(node) +
            when (node) {
                is Node.Request -> "[/\"${node.label}\"/]"
                is Node.Outside -> "((\"${node.label}\"))"
                is Node.Event -> "[\"${node.label}\"]"
                is Node.Task -> "{{\"${node.label}\"}}"
            }
}

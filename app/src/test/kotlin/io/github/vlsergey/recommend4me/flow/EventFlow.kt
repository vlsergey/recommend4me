package io.github.vlsergey.recommend4me.flow

import io.github.vlsergey.recommend4me.api.model.TaskKind
import org.springframework.asm.Type

/** What the flow goes through: a thing from outside, an event of the application, a work of the queue. */
internal sealed interface Node : Comparable<Node> {
    val label: String

    /** Its place in the order of the documents: the outside first, then the events, then the works. */
    val rank: Int

    override fun compareTo(other: Node): Int = compareValuesBy(this, other, { it.rank }, { it.label })

    /** A request of the interface or the extension, by its operation's id. */
    data class Request(override val label: String) : Node {
        override val rank = 0
    }

    /** The start of the application, or a loop of the background by its name. */
    data class Outside(override val label: String) : Node {
        override val rank = 1
    }

    /** An event of the application, by its class's simple name. */
    data class Event(override val label: String) : Node {
        override val rank = 2
    }

    /** A work of the queue, by its kind ([io.github.vlsergey.recommend4me.api.model.TaskKind]). */
    data class Task(override val label: String) : Node {
        override val rank = 3
    }
}

/**
 * THE FLOW OF THE APPLICATION, READ OF ITS CODE ([CodeRead]): what every thing from outside — a request, the start, a
 * loop of the background — leads to, what every event leads to, what every work of the queue leads to: the events it
 * makes and the works it plans, directly (owner, 2026-11-19: "a procedure that generates the diagram by the code of the
 * service: which outer event leads to which inner events, and to the planning of which background works").
 *
 * READ SO, the code is written so (AGENTS.md): an event is an object of a class some `@EventListener` listens to, made
 * where it is told; a work is planned by `Tasks.plan` or run by `Tasks.run`, its [TaskKind] named in the method that
 * plans it — or in a method it calls that gives a `Task`, a field of a `Task` it reads — and its body the lambda made
 * right before; a method handed a `Task` to plan is a planner too, the kind named by its callers. A call goes to the
 * method named and to every one overriding it; a lambda not a work's body is taken as run where it is made. A place
 * planning a work whose kind is named nowhere so is told ([unnamed]): the code is to name it.
 *
 * [TaskKind]: io.github.vlsergey.recommend4me.api.model.TaskKind
 */
internal class EventFlow(private val code: CodeRead) {
    /** The events: the classes of the application some listener listens to. */
    private val events: Set<String> =
        code.classes.values
            .flatMap { c -> c.methods.values.flatMap { it.listens.orEmpty() } }
            .filter { it.startsWith(PACKAGE) }
            .toSet()

    /** Of every field of a `Task` or a `TaskKind`, the kinds of work it is made of. */
    private val fieldKinds: Map<String, Set<String>> = run {
        val out = HashMap<String, MutableSet<String>>()
        code.classes.values.forEach { c ->
            c.methods.values.forEach { m ->
                val pending = HashSet<String>()
                m.steps.forEach { s ->
                    when {
                        s is Step.Kind -> pending += s.kind
                        // A field made of another: `val WORK = Task(WHAT)`, WHAT a kind
                        s is Step.Field && !s.write && s.desc in NAMING ->
                            pending += out["${s.owner}.${s.name}"].orEmpty()
                        s is Step.Field && s.write && s.desc in NAMING -> {
                            out.getOrPut("${s.owner}.${s.name}") { HashSet() } += pending
                            pending.clear()
                        }
                    }
                }
            }
        }
        out
    }

    /** Of every method giving a `Task` or a `TaskKind`, the kinds of the works it may give. */
    private val given = HashMap<MethodKey, Set<String>>()

    private fun givenBy(key: MethodKey, seen: MutableSet<MethodKey> = HashSet()): Set<String> =
        given[key]
            ?: if (!seen.add(key)) emptySet()
            else code.method(key)?.let { kindsOf(it.steps, seen) }.orEmpty().also { given[key] = it }

    /** The kinds the [steps] name: directly, by a field of a `Task`, by a method giving one. */
    private fun kindsOf(steps: List<Step>, seen: MutableSet<MethodKey> = HashSet()): Set<String> =
        steps
            .flatMap { s ->
                when {
                    s is Step.Kind -> listOf(s.kind)
                    s is Step.Field && !s.write && s.desc in NAMING -> fieldKinds["${s.owner}.${s.name}"].orEmpty()
                    s is Step.Call && Type.getReturnType(s.target.desc).descriptor in NAMING -> givenBy(s.target, seen)
                    else -> emptyList()
                }
            }
            .toSet()

    /** A place planning or running a work: the method, the index of its call, the kinds, the lambdas before it. */
    private class Site(
        val method: MethodRead,
        val at: Int,
        val kinds: Set<String>,
        val lambdas: List<MethodKey>,
        /** Whether it runs the work now ([Tasks.run]) rather than plans it: inside a work of the kind, that work. */
        val runs: Boolean,
    )

    /** The places planning works, by their method and index. */
    private val sites: Map<Pair<MethodKey, Int>, Site>

    /** The places planning a work whose kind is named nowhere: of the code, to be named. */
    val unnamed: List<String>

    /** The bodies of the works, by their lambda: of which kinds. */
    private val bodies = HashMap<MethodKey, MutableSet<String>>()

    /** The loops of the background, by their lambda: their names. */
    private val loops = HashMap<MethodKey, String>()

    /**
     * The lambdas handed to the constructor of a class, by the class: run where its methods call a function value — the
     * repository of a source's items telling the items written by the lambda it was made with.
     */
    private val callbacks = HashMap<String, MutableSet<MethodKey>>()

    /** The lambdas the flow does not go into: how a work the last run left is planned again ([RESUMPTION]). */
    private val hidden = HashSet<MethodKey>()

    init {
        // The methods handed a Task that plan it are planners too: the kind named by their callers
        val planning = HashSet<MethodKey>()
        val methods = code.classes.values.flatMap { it.methods.values }
        fun isPlanner(key: MethodKey) =
            (key.owner == WORKLOAD && key.name.substringBefore('$') in PLAN) || key in planning
        var grew = true
        while (grew) {
            grew = false
            methods.forEach { m ->
                if (m.key in planning || WORK_DESC !in Type.getArgumentTypes(m.key.desc).map { it.descriptor })
                    return@forEach
                // Of its calls of a planner and its lambdas that are planners, one naming no kind: it is handed it
                val own =
                    segments(m) {
                        (it is Step.Call && isPlanner(it.target)) || (it is Step.Lambda && it.body in planning)
                    }
                if (own.any { (_, steps) -> kindsOf(steps).isEmpty() }) grew = planning.add(m.key) || grew
            }
        }
        // A step handing a planner its work: a call of it, or a lambda of it made — a lambda given a Task it captured
        fun hands(s: Step) = (s is Step.Call && isPlanner(s.target)) || (s is Step.Lambda && s.body in planning)
        // The kinds every planner of the application is handed, by its callers — of a caller a planner, its own
        val handed = HashMap<MethodKey, MutableSet<String>>()
        var more = true
        while (more) {
            more = false
            methods.forEach { m ->
                segments(m, ::hands).forEach { (at, steps) ->
                    val s = m.steps[at]
                    val target = if (s is Step.Call) s.target else (s as Step.Lambda).body
                    if (target !in planning) return@forEach
                    val kinds = kindsOf(steps).ifEmpty { kindsOf(m.steps) }.ifEmpty { handed[m.key].orEmpty() }
                    more = handed.getOrPut(target) { HashSet() }.addAll(kinds) || more
                }
            }
        }
        val found = HashMap<Pair<MethodKey, Int>, Site>()
        val lost = ArrayList<String>()
        methods.forEach { m ->
            if (m.key.owner in INTERNAL) return@forEach
            segments(m, ::hands).forEach { (at, steps) ->
                val kinds = kindsOf(steps).ifEmpty { kindsOf(m.steps) }.ifEmpty { handed[m.key].orEmpty() }
                val lambdas = steps.filterIsInstance<Step.Lambda>().map { it.body }
                if (kinds.isEmpty()) lost += "${m.key}"
                val target = m.steps[at]
                val runs =
                    target is Step.Call && target.target.owner == WORKLOAD && target.target.name.startsWith("run")
                found[m.key to at] = Site(m, at, kinds, lambdas, runs)
                lambdas.forEach { bodies.getOrPut(it) { HashSet() } += kinds }
            }
            // The loops of the background: their lambdas, named by the text before
            segments(m) {
                    it is Step.Call && it.target.owner == BACKGROUND && it.target.name.substringBefore('$') in LOOPS
                }
                .forEach { (_, steps) ->
                    val name =
                        steps.filterIsInstance<Step.Text>().firstOrNull { LOOP_NAME.matches(it.text) }?.text
                            ?: "background"
                    steps.filterIsInstance<Step.Lambda>().forEach { loops[it.body] = name }
                }
        }
        // How a work the last run left is planned again (Resumption.resumes): the restart's, not the flow's — not shown
        methods.forEach { m ->
            segments(m) { it is Step.Call && it.target.owner == RESUMPTION && it.target.name == "resumes" }
                .forEach { (_, steps) -> steps.filterIsInstance<Step.Lambda>().forEach { hidden += it.body } }
        }
        // The lambdas handed to a constructor: the next call after them makes the object
        methods.forEach { m ->
            m.steps.forEachIndexed { i, s ->
                if (s !is Step.Lambda || s.body in bodies || s.body in loops) return@forEachIndexed
                val next = m.steps.drop(i + 1).firstOrNull { it is Step.Call } as Step.Call?
                if (next != null && next.target.name == "<init>")
                    callbacks.getOrPut(next.target.owner) { HashSet() } += s.body
            }
        }
        sites = found
        unnamed = lost.distinct().sorted()
    }

    /** The steps of [m] that are [of] the kind asked, each with its index and the steps since the one before it. */
    private fun segments(m: MethodRead, of: (Step) -> Boolean): List<Pair<Int, List<Step>>> {
        val out = ArrayList<Pair<Int, List<Step>>>()
        var from = 0
        m.steps.forEachIndexed { i, s ->
            if (of(s)) {
                out += i to m.steps.subList(from, i)
                from = i + 1
            }
        }
        return out
    }

    /** Every event and every kind of work there is: the events listened to, every kind of the API. */
    val nodes: Set<Node> by lazy {
        events.map { Node.Event(simple(it)) }.toSet<Node>() + TaskKind.entries.map { Node.Task(it.value) }
    }

    /** Every edge of the flow: what each thing leads to directly. */
    val edges: Map<Node, Set<Node>> by lazy {
        val out = HashMap<Node, MutableSet<Node>>()
        fun from(node: Node, method: MethodKey) {
            out.getOrPut(node) { sortedSetOf() } += reached(method, node)
        }
        code.classes.values.forEach { c ->
            c.methods.values.forEach { m ->
                m.listens?.forEach { e ->
                    when {
                        e == READY -> from(Node.Outside(START), m.key)
                        e in events -> from(Node.Event(simple(e)), m.key)
                    }
                }
            }
            if (REST in c.annotations) requests(c).forEach { from(Node.Request(it.key.name), it.key) }
        }
        bodies.forEach { (body, kinds) -> kinds.forEach { from(Node.Task(value(it)), body) } }
        loops.forEach { (body, name) -> from(Node.Outside(name), body) }
        out.filterValues { it.isNotEmpty() }
    }

    /** The methods of the controller [c] that answer the requests of the API. */
    private fun requests(c: ClassRead): List<MethodRead> {
        val api =
            c.interfaces.filter { it.startsWith(API) }.flatMap { code.classes[it]?.methods?.keys.orEmpty() }.toSet()
        return c.methods.values.filter { (it.key.name to it.key.desc) in api }
    }

    /** A walk of [reached] from what [self] runs: what it found, what it is to go into. */
    private class Walk(val self: Node, start: MethodKey) {
        val out = HashSet<Node>()
        val queue = ArrayDeque(listOf(start))
    }

    /**
     * What running [start] — of [self] — leads to: the events made, the works planned; not into a work's body or a
     * listener. A work run of its own kind inside it is that work itself ([Tasks.run] inside its own turn).
     */
    private fun reached(start: MethodKey, self: Node): Set<Node> {
        val walk = Walk(self, start)
        val seen = HashSet<MethodKey>()
        while (walk.queue.isNotEmpty()) {
            val m = code.method(walk.queue.removeFirst())
            if (m != null && m.key.owner !in INTERNAL && seen.add(m.key))
                m.steps.forEachIndexed { i, s -> step(m, i, s, walk) }
        }
        return walk.out
    }

    /** The step [s], the [i]-th of [m]: what it leads to put out of the [walk], the methods it runs queued in it. */
    private fun step(m: MethodRead, i: Int, s: Step, walk: Walk) {
        val out = walk.out
        val queue = walk.queue
        // A place planning a work: the work, not what the planner runs
        val site = sites[m.key to i]
        if (site != null) {
            site.kinds.map { Node.Task(value(it)) }.filterNot { site.runs && it == walk.self }.forEach { out += it }
            return
        }
        when (s) {
            is Step.Made,
            is Step.Field -> told(s)?.let { out += it }
            is Step.Call -> (code.runs(s.target) + pluginCalls(s.target)).forEach { queue += it.key }
            is Step.Lambda -> if (runsWhereMade(s.body)) queue += s.body
            Step.Invoke -> queue += callbacks[m.key.owner].orEmpty()
            else -> Unit
        }
    }

    /** The event the step [s] tells: an object of an event's class made, or an event of no data, an object, taken. */
    private fun told(s: Step): Node? =
        when {
            s is Step.Made && s.type in events -> Node.Event(simple(s.type))
            s is Step.Field && !s.write && s.name == INSTANCE && s.owner in events -> Node.Event(simple(s.owner))
            else -> null
        }

    /** Whether the lambda [body] is taken as run where it is made: not a work's body, a loop's, a constructor's. */
    private fun runsWhereMade(body: MethodKey) = body !in bodies && body !in loops && body !in hidden && !handed(body)

    private fun handed(body: MethodKey) = callbacks.values.any { body in it }

    /**
     * A method of an interface of the plugins' API called: a plugin's own may run — a source parsing, downloading —
     * which may call back every method of what it is handed (the source's context, its store of items) and of what
     * those give it: the methods of the application implementing them.
     */
    private fun pluginCalls(target: MethodKey): List<MethodRead> {
        if (code.classes[target.owner]?.let { it.api && it.isInterface } != true) return emptyList()
        val types = HashSet<String>()
        val queue = ArrayDeque(Type.getArgumentTypes(target.desc).map { it.internalName })
        while (queue.isNotEmpty()) {
            val t = queue.removeFirst()
            val c = code.classes[t]?.takeIf { it.api && it.isInterface } ?: continue
            if (types.add(t)) {
                c.methods.values.forEach { m -> queue += Type.getReturnType(m.key.desc).internalName }
                // And what it extends: the context of a source is its stores of items, facets, parts
                queue += c.interfaces
            }
        }
        return types
            .flatMap { t -> code.classes.getValue(t).methods.values.flatMap { code.runs(it.key) } }
            .filter {
                code.classes[it.key.owner]?.api == false
            }
    }

    companion object {
        private const val PACKAGE = "io/github/vlsergey/recommend4me/"
        private const val API = "${PACKAGE}api/"
        private const val WORKLOAD = "${PACKAGE}task/Tasks"
        private const val BACKGROUND = "${PACKAGE}background/Background"
        private const val RESUMPTION = "${PACKAGE}task/Resumption"
        private const val WORK_DESC = "L${PACKAGE}task/Task;"

        /** What names a kind of work: a `Task`, a `TaskKind` — kept in a field, given by a method. */
        private val NAMING = setOf(WORK_DESC, "L${PACKAGE}api/model/TaskKind;")
        private const val READY = "org/springframework/boot/context/event/ApplicationReadyEvent"
        private const val REST = "Lorg/springframework/web/bind/annotation/RestController;"
        const val START = "start"
        private const val INSTANCE = "INSTANCE"

        /** The queue's own classes: its planning and running, not followed. */
        private val INTERNAL = setOf(WORKLOAD, "${PACKAGE}task/Plans", "${PACKAGE}task/Turns")

        /** The queue's methods that plan or run a work. */
        private val PLAN = setOf("plan", "run")

        /** The background's methods that start a loop or a thread of its own. */
        private val LOOPS = setOf("start", "every", "endless")

        /**
         * The name of a loop as the code gives it: small letters and hyphens ("set-vectors", "job-*" of "job-<source>")
         * — not a parameter's name the compiler checks for null before it.
         */
        private val LOOP_NAME = Regex("[a-z]+(-[a-z*]*)*")

        private fun simple(name: String) = name.substringAfterLast('/').substringAfterLast('$')

        /** A kind of work by its value, as the interface words it: "TAG_VECTORS" is "tagVectors". */
        private fun value(kind: String): String = TaskKind.valueOf(kind).value
    }
}

package io.github.vlsergey.recommend4me.flow

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.inputStream
import org.springframework.asm.AnnotationVisitor
import org.springframework.asm.ClassReader
import org.springframework.asm.ClassVisitor
import org.springframework.asm.Handle
import org.springframework.asm.MethodVisitor
import org.springframework.asm.Opcodes
import org.springframework.asm.Type

/** A method of the code by its class (internal name), name and descriptor. */
internal data class MethodKey(val owner: String, val name: String, val desc: String) {
    override fun toString() = "${owner.substringAfterLast('/')}.$name"
}

/** One step of a method's code the flow is read of, in the order of the code. */
internal sealed interface Step {
    /** A call of [target], as the code names it — the method itself or one overriding it is what runs. */
    data class Call(val target: MethodKey) : Step

    /** A lambda or a reference made of the method [body]: run later, by whoever is handed it. */
    data class Lambda(val body: MethodKey) : Step

    /** An object of the class [type] made. */
    data class Made(val type: String) : Step

    /** A kind of work of the queue named ([io.github.vlsergey.recommend4me.api.model.WorkKind]). */
    data class Kind(val kind: String) : Step

    /** A field read, [write]n. */
    data class Field(val owner: String, val name: String, val desc: String, val write: Boolean) : Step

    /** A text constant: the name of a loop of the background. */
    data class Text(val text: String) : Step

    /** A function value called: a lambda the object was handed runs here. */
    data object Invoke : Step
}

/** A method read: its steps, and the events it listens to when it is a listener ([listens]). */
internal class MethodRead(val key: MethodKey, val access: Int) {
    val steps = ArrayList<Step>()

    /** The classes of the events it listens to, when it is an `@EventListener`; null of a method that is not one. */
    var listens: List<String>? = null
}

/** A class read: what it extends and implements, its annotations and methods. */
internal class ClassRead(
    val name: String,
    val superName: String?,
    val interfaces: List<String>,
    val access: Int,
) {
    val annotations = HashSet<String>()

    /** Whether it is of the plugins' API, not of the application. */
    var api = false
    val methods = LinkedHashMap<Pair<String, String>, MethodRead>()

    val isInterface: Boolean
        get() = access and Opcodes.ACC_INTERFACE != 0
}

/**
 * THE APPLICATION'S CODE AS IT WAS COMPILED, read of its class files: of every method the calls, the lambdas, the
 * objects made, the kinds of work named and the fields read and written, in order — what the flow of the events and the
 * works is made of ([EventFlow]). The class files are read, not the sources: what runs is what is read.
 */
internal class CodeRead(val classes: Map<String, ClassRead>) {
    /** Of every class, the classes extending or implementing it, directly. */
    private val below: Map<String, List<String>> =
        classes.values
            .flatMap { c -> (listOfNotNull(c.superName) + c.interfaces).map { it to c.name } }
            .groupBy({ it.first }, { it.second })

    fun method(key: MethodKey): MethodRead? = classes[key.owner]?.methods?.get(key.name to key.desc)

    /**
     * The methods of the application a call of [target] may run: the one it names or the one it inherits, and every one
     * overriding it in a class below — of an interface, every one implementing it.
     */
    fun runs(target: MethodKey): List<MethodRead> {
        val out = ArrayList<MethodRead>()
        inherited(target.owner, target.name, target.desc)?.let(out::add)
        val seen = HashSet<String>()
        val queue = ArrayDeque(below[target.owner].orEmpty())
        while (queue.isNotEmpty()) {
            val c = queue.removeFirst()
            if (!seen.add(c)) continue
            classes[c]?.methods?.get(target.name to target.desc)?.let(out::add)
            queue += below[c].orEmpty()
        }
        return out.distinctBy { it.key }
    }

    /** The method [name] of the class [owner] or of the first class above it that has it. */
    private fun inherited(owner: String, name: String, desc: String): MethodRead? =
        generateSequence(classes[owner]) { c -> c.superName?.let(classes::get) }
            .firstNotNullOfOrNull { it.methods[name to desc] }

    /** Every class above [name], itself among them: its superclasses and interfaces. */
    fun above(name: String): Set<String> {
        val out = LinkedHashSet<String>()
        val queue = ArrayDeque(listOf(name))
        while (queue.isNotEmpty()) {
            val c = queue.removeFirst()
            if (!out.add(c)) continue
            classes[c]?.let { r -> queue += listOfNotNull(r.superName) + r.interfaces }
        }
        return out
    }

    companion object {
        private const val PACKAGE = "io/github/vlsergey/recommend4me/"
        private const val LISTENER = "Lorg/springframework/context/event/EventListener;"
        private const val WORK_KIND = "${PACKAGE}api/model/WorkKind"
        private const val FUNCTIONS = "kotlin/jvm/functions/Function"
        private const val INTRINSICS = "kotlin/jvm/internal/Intrinsics"

        /** Where a value made at run time goes in a text made of parts. */
        private const val ARGUMENT = ''

        /** Every class file under [root], the compiled application. */
        fun of(app: Path, api: Path): CodeRead {
            val classes = HashMap<String, ClassRead>()
            fun walk(root: Path, api: Boolean) =
                Files.walk(root).use { paths ->
                    paths
                        .filter { it.extension == "class" }
                        .forEach { p ->
                            p.inputStream()
                                .use { read(ClassReader(it)) }
                                ?.let { classes[it.name] = it.also { c -> c.api = api } }
                        }
                }
            walk(app, false)
            // The plugins' API, a jar or the folder of its classes: what the application hands a plugin, read too
            if (Files.isDirectory(api)) walk(api, true)
            else FileSystems.newFileSystem(api).use { fs -> fs.rootDirectories.forEach { walk(it, true) } }
            return CodeRead(classes)
        }

        private fun read(reader: ClassReader): ClassRead? {
            var out: ClassRead? = null
            reader.accept(
                object : ClassVisitor(Opcodes.ASM9) {
                    override fun visit(
                        version: Int,
                        access: Int,
                        name: String,
                        signature: String?,
                        superName: String?,
                        interfaces: Array<out String>?,
                    ) {
                        out = ClassRead(name, superName, interfaces.orEmpty().toList(), access)
                    }

                    override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor? {
                        out?.annotations?.add(descriptor)
                        return null
                    }

                    override fun visitMethod(
                        access: Int,
                        name: String,
                        descriptor: String,
                        signature: String?,
                        exceptions: Array<out String>?,
                    ): MethodVisitor {
                        val m = MethodRead(MethodKey(out!!.name, name, descriptor), access)
                        out!!.methods[name to descriptor] = m
                        return Steps(m)
                    }
                },
                ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
            )
            return out
        }

        /** The steps of a method read in order. */
        private class Steps(private val m: MethodRead) : MethodVisitor(Opcodes.ASM9) {
            override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor? {
                if (descriptor != LISTENER) return null
                val named = ArrayList<String>()
                m.listens = named
                return object : AnnotationVisitor(Opcodes.ASM9) {
                    override fun visitArray(name: String?): AnnotationVisitor =
                        object : AnnotationVisitor(Opcodes.ASM9) {
                            override fun visit(name: String?, value: Any?) {
                                if (value is Type) named += value.internalName
                            }
                        }

                    override fun visitEnd() {
                        // A listener of no class named listens to the class of its one parameter
                        if (named.isEmpty())
                            Type.getArgumentTypes(m.key.desc).firstOrNull()?.let { named += it.internalName }
                    }
                }
            }

            override fun visitMethodInsn(opcode: Int, owner: String, name: String, desc: String, itf: Boolean) {
                when {
                    owner.startsWith(PACKAGE) -> m.steps += Step.Call(MethodKey(owner, name, desc))
                    // The name of a parameter the compiler checks for null: no text of the code's own
                    owner == INTRINSICS && m.steps.lastOrNull() is Step.Text -> m.steps.removeLast()
                    owner.startsWith(FUNCTIONS) && name == "invoke" -> m.steps += Step.Invoke
                }
            }

            override fun visitInvokeDynamicInsn(name: String, desc: String, bsm: Handle, vararg args: Any?) {
                // A text made of parts ("job-$source"): its constant parts, a part made at run time a star
                if (bsm.name == "makeConcatWithConstants")
                    (args.firstOrNull() as? String)?.let { m.steps += Step.Text(it.replace(ARGUMENT, '*')) }
                args
                    .filterIsInstance<Handle>()
                    .filter { it.owner.startsWith(PACKAGE) }
                    .forEach { m.steps += Step.Lambda(MethodKey(it.owner, it.name, it.desc)) }
            }

            override fun visitTypeInsn(opcode: Int, type: String) {
                if (opcode == Opcodes.NEW && type.startsWith(PACKAGE)) m.steps += Step.Made(type)
            }

            override fun visitFieldInsn(opcode: Int, owner: String, name: String, desc: String) {
                when {
                    owner == WORK_KIND && opcode == Opcodes.GETSTATIC -> m.steps += Step.Kind(name)
                    owner.startsWith(PACKAGE) ->
                        m.steps +=
                            Step.Field(owner, name, desc, opcode == Opcodes.PUTSTATIC || opcode == Opcodes.PUTFIELD)
                }
            }

            override fun visitLdcInsn(value: Any?) {
                if (value is String) m.steps += Step.Text(value)
            }
        }
    }
}

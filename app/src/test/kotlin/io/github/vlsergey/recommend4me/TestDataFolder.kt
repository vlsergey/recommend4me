package io.github.vlsergey.recommend4me

import java.io.File
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.test.context.TestPropertySource

/**
 * THE DATA FOLDER OF AN APPLICATION OF THE TESTS: the classes of one kind — of one set of plugins — name one folder in
 * their base class (`@TestPropertySource(properties = ["recommend4me.data-dir=build/test-data/<name>-<the worker>"])`),
 * so Spring keeps one application for them all in a process of the tests and starts it once (user, 2026-11-18), a
 * folder of its own a process; it starts empty, emptied once in a process. The classes of one application share its
 * data: a test reads what it wrote by its own keys, never "every work".
 */
object TestDataFolder {
    private const val PROPERTY = "recommend4me.data-dir="

    /** The system property Gradle names a worker of the tests by. */
    private const val WORKER = "org.gradle.test.worker"

    /** The folders emptied in this process: each once, before its application opens a file in it. */
    private val cleaned = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * The folder the class [testClass] names, emptied — once in a process: the classes of one application share it, the
     * application kept by Spring for them all, its files open.
     */
    fun clean(testClass: Class<*>) {
        val folder =
            AnnotatedElementUtils.findMergedAnnotation(testClass, TestPropertySource::class.java)
                ?.properties
                ?.firstNotNullOfOrNull { it.takeIf { p -> p.startsWith(PROPERTY) }?.removePrefix(PROPERTY) }
        checkNotNull(folder) { "${testClass.simpleName} names no data folder of its own" }
        // The process's own folder: the number of the worker of the tests in it, as Spring reads the property
        val own = folder.replace("\${$WORKER:0}", System.getProperty(WORKER, "0"))
        if (cleaned.add(own)) File(own).deleteRecursively()
    }
}

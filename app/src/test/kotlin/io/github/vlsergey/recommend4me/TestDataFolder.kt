package io.github.vlsergey.recommend4me

import java.io.File
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.test.context.TestPropertySource

/**
 * THE DATA FOLDER OF A CLASS OF TESTS: every class that starts the whole application names a folder of its own
 * (`@TestPropertySource(properties = ["recommend4me.data-dir=build/test-data/<name>"])`) — an application of its own,
 * no file shared with another class, so the classes run in any order and in processes side by side — and starts from it
 * empty.
 */
object TestDataFolder {
    private const val PROPERTY = "recommend4me.data-dir="

    /** The folder the class [testClass] names, emptied: before its application opens a file in it. */
    fun clean(testClass: Class<*>) {
        val folder =
            AnnotatedElementUtils.findMergedAnnotation(testClass, TestPropertySource::class.java)
                ?.properties
                ?.firstNotNullOfOrNull { it.takeIf { p -> p.startsWith(PROPERTY) }?.removePrefix(PROPERTY) }
        checkNotNull(folder) { "${testClass.simpleName} names no data folder of its own" }
        File(folder).deleteRecursively()
    }
}

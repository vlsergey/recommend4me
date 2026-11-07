// Every module configures itself through the conventions of build-logic. Loaded here once, not applied: every
// module reads the conventions' plugins (Kotlin, detekt, Spotless) of one class loader — a module with plugins of
// its own (the app's jOOQ and OpenAPI) loaded them again in a loader of its own, and their shared services clashed
plugins {
    id("recommend4me.kotlin") apply false
}

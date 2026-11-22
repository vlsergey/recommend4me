import org.openapitools.generator.gradle.plugin.tasks.GenerateTask

plugins {
    id("recommend4me.spring")
    application
    alias(libs.plugins.jooq.codegen)
    alias(libs.plugins.openapi.generator)
}

dependencies {
    implementation(platform(libs.spring.boot.bom))
    api(project(":plugin-api"))
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.jackson.kotlin)
    implementation(libs.kotlin.reflect)
    implementation(libs.jooq)
    implementation(libs.flyway.core)
    implementation(libs.hikari)
    // Its own classes too: a file is compacted while open through them (Database.compact)
    implementation(libs.h2)
    // The store of the search of duplicates of a content type: a table of millions of candidates written whole by every
    // search and read by a page's filter and sort, of no index (DuplicateStore)
    implementation(libs.duckdb.jdbc)
    // The texts of the works' parts and their vectors, outside the database: a store of keys and values, the texts
    // compressed by zstd with a dictionary of their own (PartStore)
    implementation(libs.rocksdbjni)
    // Shared with the source plugins: the pages they read
    implementation(libs.jsoup)
    // Pictures: WebP is not among the formats ImageIO reads by itself, and some sites keep every
    // original as AVIF, read by javif, a pure Java AV1 decoder
    implementation(libs.imageio.webp)
    implementation(libs.javif)

    jooqCodegen(platform(libs.spring.boot.bom))
    jooqCodegen(libs.h2)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.h2)
    // The suggestions of the facets worked out by the cheapest plugin of this build: every test of the application runs
    // the fastest plugins (user, 2026-11-18; recommend4me.suggester of the tests' config) — the bilinear suggester,
    // fitted by up to 1800 steps a facet, 4-5 s on thirty works, by the scenarios of what it works out alone
    testImplementation(project(":plugins:suggester-bayes"))
    testImplementation(project(":plugins:suggester-bilinear"))
    // A model to explain: the pairwise scorer of this build
    testImplementation(project(":plugins:scorer-pairwise"))
    // The search of the works by their words, of this build
    testImplementation(project(":plugins:search-lucene"))
    // The pairs of works across the sites, by the model of this build
    testImplementation(project(":plugins:pair-logistic"))
    // The ways of reading the sites' lines of characters and pairings, as the application is installed with them
    testImplementation(project(":plugins:cast-reading"))
}

// --- jOOQ: the classes of the tables of every kind of database file, generated from its migrations ---

val migrations = layout.projectDirectory.dir("src/main/resources/db")

/** Every kind of database file, by the folder of its migrations: the package of its classes. */
val schemas = listOf("app", "source", "corrections", "ratings", "model", "dictionaries", "preferences")

/**
 * H2 ITSELF READS THE SCHEMA: the generator is given an empty database in memory that runs every migration of the kind
 * in order as it opens, so the statements are parsed by the parser that runs them in the application.
 */
fun h2Of(kind: String): String {
    val scripts =
        migrations
            .dir(kind)
            .asFile
            .listFiles { f -> f.name.matches(Regex("V\\d+__.*\\.sql")) }!!
            .sortedBy { it.name.substringAfter('V').substringBefore("__").toInt() }
            .joinToString("\\;") { "RUNSCRIPT FROM '${it.absolutePath.replace('\\', '/')}' CHARSET 'UTF-8'" }
    return "jdbc:h2:mem:jooq_$kind;INIT=$scripts"
}

/**
 * The DuckDB files, each made by its one script (DuckDatabase): the store of the search of duplicates and the index of
 * the list, by the package of their classes. H2 reads a script for the generator alike, as DuckDB runs it in the
 * application.
 */
val duckStores =
    mapOf(
        "duplicates" to layout.projectDirectory.file("src/main/resources/duplicate/store.sql"),
        "worklist" to layout.projectDirectory.file("src/main/resources/worklist/store.sql"),
    )

/** Of every schema jOOQ's classes are made of, the database in memory the generator reads. */
val generated: Map<String, String> =
    schemas.associateWith(::h2Of) +
        duckStores.mapValues { (kind, script) ->
            "jdbc:h2:mem:jooq_$kind;INIT=RUNSCRIPT FROM '${script.asFile.absolutePath.replace('\\', '/')}' CHARSET 'UTF-8'"
        }

jooq {
    executions {
        generated.forEach { (kind, h2) ->
            create(kind) {
                configuration {
                    jdbc {
                        driver = "org.h2.Driver"
                        url = h2
                    }
                    generator {
                        name = "org.jooq.codegen.KotlinGenerator"
                        database {
                            name = "org.jooq.meta.h2.H2Database"
                            inputSchema = "PUBLIC"
                            // Flyway's own table is not the application's
                            excludes = "flyway_schema_history"
                            forcedTypes {
                                forcedType {
                                    name = "INSTANT"
                                    // The pattern is read with COMMENTS on: a plain space is no space
                                    includeTypes = "(?i:TIMESTAMP(\\(\\d\\))?\\ WITH\\ TIME\\ ZONE)"
                                }
                            }
                        }
                        generate {
                            isRecords = true
                            isPojos = false
                            isDaos = false
                            isJavaTimeTypes = true
                            isKotlinNotNullRecordAttributes = false
                        }
                        target {
                            packageName = "io.github.vlsergey.recommend4me.database.$kind"
                            directory = layout.buildDirectory.dir("generated/jooq").get().asFile.absolutePath
                        }
                    }
                }
            }
        }
    }
}

tasks
    .matching { it.name.startsWith("jooqCodegen") }
    .configureEach {
        inputs.dir(migrations)
        duckStores.values.forEach { inputs.file(it) }
    }

// --- OpenAPI: the contract in api/openapi.yaml is the single source of truth ---

val openApiSpec = rootProject.layout.projectDirectory.file("api/openapi.yaml")
val generatedApiDir = layout.buildDirectory.dir("generated/openapi")

val generateApi =
    tasks.register<GenerateTask>("generateApi") {
        generatorName = "kotlin-spring"
        remoteInputSpec = openApiSpec.asFile.toURI().toString()
        inputs.file(openApiSpec)
        outputDir = generatedApiDir
        apiPackage = "io.github.vlsergey.recommend4me.api"
        modelPackage = "io.github.vlsergey.recommend4me.api.model"
        cleanupOutput = true
        globalProperties = mapOf("apis" to "", "models" to "", "supportingFiles" to "false")
        configOptions =
            mapOf(
                "interfaceOnly" to "true",
                "skipDefaultInterface" to "true",
                "useSpringBoot3" to "true",
                "useTags" to "true",
                "documentationProvider" to "none",
                "annotationLibrary" to "none",
                "useBeanValidation" to "false",
                "enumPropertyNaming" to "UPPERCASE",
                "serializationLibrary" to "jackson",
            )
    }

sourceSets {
    main {
        kotlin.srcDir(generatedApiDir.map { it.dir("src/main/kotlin") })
        kotlin.srcDir(layout.buildDirectory.dir("generated/jooq"))
    }
}

tasks.named("compileKotlin") { dependsOn(generateApi, "jooqCodegen") }

// --- Frontend: built by npm and served as static resources ---

val frontendDir = rootProject.layout.projectDirectory.dir("frontend")

fun npm(): String = if (System.getProperty("os.name").lowercase().contains("windows")) "npm.cmd" else "npm"

val npmInstall =
    tasks.register<Exec>("npmInstall") {
        workingDir = frontendDir.asFile
        commandLine(npm(), "install", "--no-audit", "--no-fund")
        inputs.file(frontendDir.file("package.json"))
        outputs.dir(frontendDir.dir("node_modules"))
    }

// The rules of the web interface's and of the extension's scripts (biome.json: no file longer than 600 lines),
// checked by Biome before they are built — the Kotlin of every module by detekt
val lintScripts =
    tasks.register<Exec>("lintScripts") {
        dependsOn(npmInstall)
        workingDir = frontendDir.asFile
        commandLine(npm(), "run", "lint")
        inputs.dir(frontendDir.dir("src"))
        inputs.dir(rootProject.layout.projectDirectory.dir("browser-extension/extension"))
        inputs.file(rootProject.layout.projectDirectory.file("biome.jsonc"))
        // Up to date while none of them changed: a mark of the last check that passed
        val passed = layout.buildDirectory.file("lint/scripts.passed")
        outputs.file(passed)
        doLast {
            passed.get().asFile.apply {
                parentFile.mkdirs()
                writeText("")
            }
        }
    }

tasks.named("check") { dependsOn(lintScripts) }

val buildFrontend =
    tasks.register<Exec>("buildFrontend") {
        dependsOn(npmInstall)
        workingDir = frontendDir.asFile
        commandLine(npm(), "run", "build")
        inputs.dir(frontendDir.dir("src"))
        inputs.file(frontendDir.file("index.html"))
        inputs.file(frontendDir.file("package.json"))
        inputs.file(frontendDir.file("vite.config.ts"))
        inputs.file(openApiSpec)
        outputs.dir(frontendDir.dir("dist"))
    }

// --- The Firefox extension of the browser tracking mode: a folder of the distribution, extension/ ---

val extensionDir = rootProject.layout.projectDirectory.dir("browser-extension/extension")

tasks.named<ProcessResources>("processResources") {
    if (!project.hasProperty("skipFrontend")) {
        dependsOn(buildFrontend)
        from(frontendDir.dir("dist")) { into("static") }
    }
}

// --- The distribution: lib/ the application, plugins/<name>/ every plugin of this build ---

val pluginProjects = rootProject.subprojects.filter { it.path.startsWith(":plugins:") }

pluginProjects.forEach { evaluationDependsOn(it.path) }

val pluginDists = pluginProjects.map { it.tasks.named("pluginDist") }

/** The Vector API is incubating, and JavaCPP and the off-heap segments call native code. */
val jvmFlags = listOf("--add-modules", "jdk.incubator.vector", "--enable-native-access=ALL-UNNAMED")

application {
    applicationName = "recommend4me"
    mainClass = "io.github.vlsergey.recommend4me.launcher.LauncherKt"
    // 12 GB: the pages H2 keeps of every source's file, up to a gigabyte each (Databases.SOURCE_CACHE_MB), beside the
    // 4-5 GB the rest takes; off the heap as much, the JVM's default. An OutOfMemoryError the JVM raises of the heap
    // leaves its dump in data\ (run.bat starts in the repository, the folder kept out of git): what took the memory is
    // read of it. Not of the memory off the heap: that one Java's own code raises, and no dump holds it
    applicationDefaultJvmArgs = jvmFlags + listOf("-Xmx12g", "-XX:+HeapDumpOnOutOfMemoryError", "-XX:HeapDumpPath=data")
}

distributions {
    main {
        distributionBaseName = "recommend4me"
        contents {
            // Every plugin in a folder of its own: plugins/<name>/
            pluginProjects.forEach { p -> from(p.tasks.named("pluginDist")) { into("plugins/${p.name}") } }
            // Firefox loads the extension from its manifest.json on the disk
            from(extensionDir) { into("extension") }
        }
    }
}

// The archives of the distribution — 3 GB each, the CUDA libraries in them — made only when one is asked for by name
// (the CI's `build distZip`, the release): `build` packs neither, minutes of a build at home spent on archives no one
// opens; `installDist` lays the same out as folders
val archivesAsked = gradle.startParameter.taskNames.map { it.substringAfterLast(':') }.toSet()

listOf("distZip", "distTar").forEach { name ->
    tasks.named(name) {
        val asked = name in archivesAsked
        onlyIf("asked for by name") { asked }
    }
}

tasks.named<JavaExec>("run") {
    dependsOn(pluginDists)
    workingDir = rootDir
    // The plugins of this build, and those of other builds named by -PpluginDirs=<dir>;<dir>
    val dirs =
        pluginProjects.map { it.layout.buildDirectory.dir("plugin").get().asFile.absolutePath } +
            (project.findProperty("pluginDirs") as String?).orEmpty().split(';').filter { it.isNotBlank() }
    systemProperty("recommend4me.plugin-dirs", dirs.joinToString(File.pathSeparator))
    systemProperty("recommend4me.extension-dir", extensionDir.asFile.absolutePath)
    // The data folder of the checkout, as run.bat's: data/, ignored by git
    systemProperty("recommend4me.data-dir", rootDir.resolve("data").absolutePath)
}

tasks.withType<Test>().configureEach {
    systemProperty("recommend4me.data-dir", layout.buildDirectory.dir("test-data").get().asFile.absolutePath)
    // The tests read the browser extension's files beside the module: a change of them runs them again
    inputs.dir(rootProject.layout.projectDirectory.dir("browser-extension/extension")).withPropertyName("extension")
    // And the documents of the databases' schemas
    inputs.dir(rootProject.layout.projectDirectory.dir("docs")).withPropertyName("docs")
    // H2's own assertions off, as on the user's machine: a hole of the file across the bound the moving
    // of chunks keeps to, one used block after it, breaks one of them (FreeSpaceBitSet.allocate, 2.4.240)
    // while the chunk is put in that free space all the same
    jvmArgs("-da:org.h2...")
    // Every class of tests of the whole application its own application over its own data folder (TestDataFolder),
    // every test of it standing alone: the classes run in processes side by side, each of them a Spring context
    maxParallelForks = TEST_PROCESSES
}

/** The processes the tests of the module run in side by side: four applications of 2 GB, beside the other modules'. */
val TEST_PROCESSES = 4

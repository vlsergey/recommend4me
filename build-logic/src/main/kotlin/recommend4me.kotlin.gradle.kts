import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/*
 * Every Kotlin module: JDK 25, the Vector API, JUnit 5; its code formatted by ktfmt and checked by detekt.
 */
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("dev.detekt")
    id("com.diffplug.spotless")
}

val catalog = the<VersionCatalogsExtension>().named("libs")

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_25
        freeCompilerArgs.addAll(
            "-Xjsr305=strict",
            "-Xannotation-default-target=param-property",
            "-Xadd-modules=jdk.incubator.vector",
        )
    }
}

// THE FORM OF THE CODE (AGENTS.md): ktfmt, kotlinlang's style, lines of 120 at most — `spotlessApply` formats,
// `spotlessCheck` fails what is not formatted so
spotless {
    kotlin {
        target("src/**/*.kt")
        ktfmt(catalog.findVersion("ktfmt").get().requiredVersion).kotlinlangStyle().configure { it.setMaxWidth(120) }
    }
    kotlinGradle {
        target("*.gradle.kts")
        ktfmt(catalog.findVersion("ktfmt").get().requiredVersion).kotlinlangStyle().configure { it.setMaxWidth(120) }
    }
}

// THE RULES OF THE CODE (AGENTS.md): detekt's default profile as it is, of the module's sources and tests. Before
// the tests, with the form: every test run checks both
detekt {
    buildUponDefaultConfig = true
    parallel = true
}
tasks.withType<Test>().configureEach { dependsOn(tasks.named("detekt"), tasks.named("spotlessCheck")) }

dependencies {
    "testImplementation"(platform(catalog.findLibrary("spring-boot-bom").get()))
    "testImplementation"(catalog.findLibrary("kotlin-test").get())
    "testRuntimeOnly"(catalog.findLibrary("junit-launcher").get())
}

tasks.withType<Test>().configureEach {
    // The tests of more than a few seconds, tagged "ci", run in CI alone: the tests of a local build take a minute at
    // most together (user, 2026-11-18). CI (GitHub sets CI) runs every one; -PallTests runs every one here too
    val local = System.getenv("CI") == null && !project.hasProperty("allTests")
    useJUnitPlatform { if (local) excludeTags("ci") }
    // The Vector API is incubating; JavaCPP and the off-heap segments call native code
    jvmArgs("--add-modules", "jdk.incubator.vector", "--enable-native-access=ALL-UNNAMED")
    maxHeapSize = "2g"
    // A test that hangs fails after so long, where it stood told, instead of holding the build till
    // the runner's six hours are up; every test's start and end in the log, the one that hung named
    systemProperty("junit.jupiter.execution.timeout.default", "10 m")
    systemProperty("junit.jupiter.execution.timeout.thread.mode.default", "SEPARATE_THREAD")
    // The data folder of this checkout's runs (run.bat, :app:run): the tests take the machine's encoders and CUDA
    // libraries from it — never its data, every test keeping its own
    systemProperty("recommend4me.local-data-dir", rootDir.resolve("data").absolutePath)
    testLogging {
        events("started", "passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

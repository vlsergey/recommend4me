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
    // FOR A WHILE: what broke the default profile when it was turned on (2026-11-07), being fixed a rule after
    // another; a module's file goes once nothing is left in it
    baseline = rootProject.file("config/detekt/${project.path.removePrefix(":").replace(':', '-')}.xml")
    parallel = true
}
tasks.withType<Test>().configureEach { dependsOn(tasks.named("detekt"), tasks.named("spotlessCheck")) }

dependencies {
    "testImplementation"(platform(catalog.findLibrary("spring-boot-bom").get()))
    "testImplementation"(catalog.findLibrary("kotlin-test").get())
    "testRuntimeOnly"(catalog.findLibrary("junit-launcher").get())
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // The Vector API is incubating; JavaCPP and the off-heap segments call native code
    jvmArgs("--add-modules", "jdk.incubator.vector", "--enable-native-access=ALL-UNNAMED")
    maxHeapSize = "2g"
    // A test that hangs fails after so long, where it stood told, instead of holding the build till
    // the runner's six hours are up; every test's start and end in the log, the one that hung named
    systemProperty("junit.jupiter.execution.timeout.default", "10 m")
    systemProperty("junit.jupiter.execution.timeout.thread.mode.default", "SEPARATE_THREAD")
    testLogging {
        events("started", "passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

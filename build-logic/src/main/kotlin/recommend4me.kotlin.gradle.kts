import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/*
 * Every Kotlin module: JDK 25, the Vector API, JUnit 5.
 */
plugins {
    id("org.jetbrains.kotlin.jvm")
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
}

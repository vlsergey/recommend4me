plugins {
    id("recommend4me.kotlin")
    `java-library`
}

/** The natives of OpenBLAS for the machine the build runs on: Windows at home, Linux in CI. */
val nativePlatform = when {
    System.getProperty("os.name").lowercase().contains("windows") -> "windows-x86_64"
    System.getProperty("os.name").lowercase().contains("mac") ->
        if (System.getProperty("os.arch") == "aarch64") "macosx-arm64" else "macosx-x86_64"
    else -> "linux-x86_64"
}

dependencies {
    api(libs.javacpp)
    api(variantOf(libs.javacpp) { classifier(nativePlatform) })
    api(libs.openblas)
    api(variantOf(libs.openblas) { classifier(nativePlatform) })
    implementation(platform(libs.spring.boot.bom))
    implementation(libs.slf4j.api)
}

base.archivesName = "recommend4me-matrix"

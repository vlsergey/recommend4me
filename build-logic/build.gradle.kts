plugins {
    `kotlin-dsl`
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")
    implementation("org.jetbrains.kotlin:kotlin-allopen:${libs.versions.kotlin.get()}")
    // The rules of the Kotlin of every module: detekt's default profile
    implementation("dev.detekt:detekt-gradle-plugin:${libs.versions.detekt.get()}")
    // The Kotlin formatted by ktfmt, kotlinlang's style, 120 wide
    implementation("com.diffplug.spotless:spotless-plugin-gradle:${libs.versions.spotless.get()}")
}

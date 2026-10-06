plugins {
    id("recommend4me.addon")
}

dependencies {
    compileOnly(libs.jackson.kotlin)
    testImplementation(libs.jackson.kotlin)
}

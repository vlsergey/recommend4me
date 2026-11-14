plugins {
    id("recommend4me.addon")
}

dependencies {
    testImplementation(testFixtures(project(":plugin-api")))
    // The plugin's own table of the fandoms in a database of memory
    testImplementation(platform(libs.spring.boot.bom))
    testImplementation(libs.h2)
}

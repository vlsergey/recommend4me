plugins {
    id("recommend4me.addon")
}

dependencies {
    compileOnly(libs.jackson.kotlin)
    // The migrations of the source's file written in code (db/ficbook): the application's Flyway runs them
    compileOnly(libs.flyway.core)
    testImplementation(libs.jackson.kotlin)
    testImplementation(libs.flyway.core)
    testImplementation(libs.h2)
    testImplementation(testFixtures(project(":plugin-api")))
}

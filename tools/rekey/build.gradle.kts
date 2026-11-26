/*
 * THE ONE-TIME MOVE OF EVERY STORE TO THE APPLICATION'S NUMBERS OF THE WORKS (2026-11-26): run once, the application
 * stopped, before the version that reads them starts; removed once it has run (AGENTS.md: one-time work is never a part
 * of the application).
 */
plugins {
    id("recommend4me.kotlin")
    application
}

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(libs.h2)
    implementation(libs.duckdb.jdbc)
    implementation(libs.lucene.core)
    implementation(libs.rocksdbjni)
}

application { mainClass = "io.github.vlsergey.recommend4me.rekey.RekeyKt" }

// The script of the index of the list, as the application makes a new one
tasks.processResources { from("../../app/src/main/resources/worklist/store.sql") { into("worklist") } }

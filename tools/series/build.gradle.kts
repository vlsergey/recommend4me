/*
 * THE ONE-TIME MAKING OF THE NORMALIZED SERIES OF THE DATA KEPT (2026-11-27): run once, the application stopped, after
 * the version that makes their tables (dictionaries V44) has started once; removed once it has run (AGENTS.md: one-time
 * work is never a part of the application).
 */
plugins {
    id("recommend4me.kotlin")
    application
}

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(libs.h2)
}

application { mainClass = "io.github.vlsergey.recommend4me.series.MakeSeriesKt" }

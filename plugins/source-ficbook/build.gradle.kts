plugins {
    id("recommend4me.addon")
}

dependencies {
    compileOnly(libs.jackson.kotlin)
    // The files of a dump of the site (Parquet) read by DuckDB: one jar with its native library, of the plugin alone
    implementation(libs.duckdb.jdbc)
    testImplementation(libs.jackson.kotlin)
    testImplementation(testFixtures(project(":plugin-api")))
}

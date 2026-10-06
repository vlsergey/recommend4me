plugins {
    id("recommend4me.kotlin")
    `java-library`
    `java-test-fixtures`
}

dependencies {
    api(project(":libs:matrix"))
    api(libs.jooq)
    // The pages the sources read, and the helpers of PageText
    api(libs.jsoup)
}

base.archivesName = "recommend4me-plugin-api"

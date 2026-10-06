plugins {
    id("recommend4me.kotlin")
    `java-library`
}

dependencies {
    api(project(":libs:matrix"))
    api(libs.jooq)
}

base.archivesName = "recommend4me-plugin-api"

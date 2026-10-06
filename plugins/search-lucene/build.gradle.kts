plugins {
    id("recommend4me.addon")
}

dependencies {
    implementation(libs.lucene.core)
    implementation(libs.lucene.analysis.common)
    // The piece of a found text shown with a search result
    implementation(libs.lucene.highlighter)
}

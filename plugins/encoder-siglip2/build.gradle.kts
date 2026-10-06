plugins {
    id("recommend4me.addon")
}

dependencies {
    implementation(project(":libs:onnx"))
    testImplementation(project(":libs:onnx"))
    testImplementation(platform(libs.spring.boot.bom))
    testImplementation(libs.jackson.kotlin)
}

tasks.withType<Test>().configureEach {
    // -Pgpu: the vectors of the test made on the card, with the libraries of tools/fetch-cuda.ps1
    systemProperty("recommend4me.gpu", project.hasProperty("gpu").toString())
}

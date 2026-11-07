plugins {
    id("recommend4me.addon")
}

dependencies {
    testImplementation(testFixtures(project(":plugin-api")))
    // The card of the tests, found as the application finds it: the CUDA libraries of the one ONNX runtime
    testImplementation(project(":libs:onnx"))
}

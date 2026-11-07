plugins {
    id("recommend4me.kotlin")
    `java-library`
}

dependencies {
    // The nets on the CPU or, asked for at launch, on an NVIDIA card: the GPU build runs on the CPU
    // too. 1.20 and not the newest: Windows builds from 1.22 on fail at DLL initialisation inside
    // the JVM, and the graphs need opset 18 at most
    api(libs.onnxruntime.gpu)
    // The products' library on the card, opened of the runtime's CUDA libraries ([CardSource])
    implementation(project(":libs:matrix"))
    compileOnly(platform(libs.spring.boot.bom))
    compileOnly(libs.spring.boot.autoconfigure)
    compileOnly(libs.slf4j.api)
}

base.archivesName = "recommend4me-onnx"

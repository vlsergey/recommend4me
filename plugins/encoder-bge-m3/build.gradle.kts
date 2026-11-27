plugins {
    id("recommend4me.addon")
}

dependencies {
    implementation(project(":libs:onnx"))
    // The SentencePiece tokenizer of XLM-RoBERTa: DJL's binding of the Rust `tokenizers` library,
    // the one the model was trained with
    implementation(libs.djl.tokenizers)
    testImplementation(project(":libs:onnx"))
    // The memory of a run of the graph measured and checked: RunMemoryRecord
    testImplementation(testFixtures(project(":libs:onnx")))
}

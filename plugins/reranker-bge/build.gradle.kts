plugins {
    id("recommend4me.addon")
}

dependencies {
    implementation(project(":libs:onnx"))
    // The SentencePiece tokenizer of XLM-RoBERTa, as the reranker was trained with
    implementation(libs.djl.tokenizers)
    testImplementation(project(":libs:onnx"))
    // The memory of a run of the graph measured and checked: RunMemoryRecord
    testImplementation(testFixtures(project(":libs:onnx")))
}

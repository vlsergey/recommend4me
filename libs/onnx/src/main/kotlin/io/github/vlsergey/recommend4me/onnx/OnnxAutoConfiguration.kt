package io.github.vlsergey.recommend4me.onnx

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import java.nio.file.Path

/**
 * The one ONNX runtime of the process, for every encoder plugin that brings this library: the
 * graphs in `<data-dir>/models`, the CUDA libraries in `<data-dir>/cuda`, the card used when
 * `recommend4me.gpu` is on.
 */
@AutoConfiguration
class OnnxAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    fun onnx(
        @Value("\${recommend4me.models-dir:\${recommend4me.data-dir}/models}") modelsDir: String,
        @Value("\${recommend4me.gpu:false}") gpu: Boolean,
        @Value("\${recommend4me.cuda-dir:\${recommend4me.data-dir}/cuda}") cudaDir: String,
    ): Onnx = Onnx(Path.of(modelsDir), gpu, Path.of(cudaDir))
}

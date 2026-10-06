package io.github.vlsergey.recommend4me.encoder

import io.github.vlsergey.recommend4me.onnx.Onnx
import io.github.vlsergey.recommend4me.onnx.OnnxAutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.context.annotation.Bean

/** SigLIP2 NaFlex as the picture encoder: the files of tools/export_siglip2_naflex.py in the models folder. */
@AutoConfiguration(after = [OnnxAutoConfiguration::class])
class Siglip2AutoConfiguration {
    @Bean
    fun siglip2Encoder(onnx: Onnx): ImageEncoder = Siglip2Encoder(onnx)
}

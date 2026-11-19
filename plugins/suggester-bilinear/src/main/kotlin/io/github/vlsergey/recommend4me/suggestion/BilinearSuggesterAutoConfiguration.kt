package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.onnx.Onnx
import io.github.vlsergey.recommend4me.onnx.OnnxAutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.context.annotation.Bean

@AutoConfiguration(after = [OnnxAutoConfiguration::class])
class BilinearSuggesterAutoConfiguration {
    /**
     * The steps of a fitting on the card through the process's one ONNX runtime: on CUDA when the launch asks for the
     * card.
     */
    @Bean fun bilinearSuggester(onnx: Onnx): FacetSuggester = BilinearSuggester(onnx)

    /** The same model, a facet of a portion's works searched over them whole ([BilinearSuggester.whole]). */
    @Bean fun wholeBilinearSuggester(onnx: Onnx): FacetSuggester = BilinearSuggester(onnx, whole = true)
}

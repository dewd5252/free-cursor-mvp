package com.freecursor.app.core

class CommandOrchestrator(
    private val modelManager: ModelManager,
    private val parser: InferenceResponseParser = InferenceResponseParser(),
) {
    fun infer(request: InferenceRequestDto): InferenceResponseDto {
        return try {
            modelManager.ensureModelLoaded()
            val rawOutput = modelManager.generateInferenceJson(request)
            parser.parse(rawOutput, request.allowedActions)
        } catch (error: Throwable) {
            InferenceResponseDto(
                action = "noop",
                confidence = 0.0,
                reason = "Inference failed: ${error.message}",
            )
        }
    }
}

package com.freecursor.app.core

import org.json.JSONObject
import java.io.File
import java.lang.reflect.Method
import java.util.Locale
import kotlin.math.exp

class OnnxTensorIoAdapter(
    private val promptBuilder: OnnxPromptBuilder = OnnxPromptBuilder(),
) {
    data class Outcome(
        val json: String?,
        val failureReason: String? = null,
    )

    fun run(
        environment: Any,
        session: Any,
        request: InferenceRequestDto,
        modelBundleDir: File,
    ): Outcome {
        return try {
            val inputMetas = readInputMetadata(session)
            if (inputMetas.isEmpty()) {
                return Outcome(json = null, failureReason = "Model has no input metadata.")
            }

            val prompt = promptBuilder.buildPrompt(request)

            if (inputMetas.any { it.type.contains("STRING") }) {
                runStringModel(environment, session, inputMetas, prompt, request.allowedActions)
            } else {
                val tokenizer = QwenTokenizer.fromBundle(modelBundleDir)
                if (tokenizer != null && inputMetas.any { it.name.contains("input_ids", ignoreCase = true) }) {
                    runAutoregressiveModel(
                        environment = environment,
                        session = session,
                        inputMetas = inputMetas,
                        tokenizer = tokenizer,
                        prompt = prompt,
                        allowedActions = request.allowedActions,
                    )
                } else {
                    runLegacyNumericModel(environment, session, inputMetas, prompt, request.allowedActions)
                }
            }
        } catch (error: Throwable) {
            Outcome(json = null, failureReason = "Adapter failed: ${error.message}")
        }
    }

    private fun runStringModel(
        environment: Any,
        session: Any,
        inputMetas: List<InputMeta>,
        prompt: String,
        allowedActions: List<String>,
    ): Outcome {
        val closeables = mutableListOf<Any>()
        return try {
            val feeds = mutableMapOf<String, Any>()
            inputMetas.forEach { meta ->
                val tensor = createTensor(environment, when {
                    meta.type.contains("STRING") -> arrayOf(prompt)
                    meta.type.contains("INT64") -> arrayOf(LongArray(1) { 1L })
                    meta.type.contains("INT32") -> arrayOf(IntArray(1) { 1 })
                    meta.type.contains("FLOAT") -> arrayOf(FloatArray(1) { 0f })
                    else -> null
                })
                if (tensor != null) {
                    feeds[meta.name] = tensor
                    closeables += tensor
                }
            }

            if (feeds.isEmpty()) {
                return Outcome(json = null, failureReason = "No tensors for string model path.")
            }

            val result = runSession(session, feeds)
            closeables += result

            val textOutput = extractJsonStringOutput(result)
            if (textOutput != null) {
                return Outcome(json = textOutput)
            }

            val numericJson = deriveJsonFromNumericOutputs(result, allowedActions)
            if (numericJson != null) {
                return Outcome(json = numericJson)
            }

            Outcome(json = null, failureReason = "String-model outputs not decodable.")
        } finally {
            closeables.forEach { safeClose(it) }
        }
    }

    private fun runLegacyNumericModel(
        environment: Any,
        session: Any,
        inputMetas: List<InputMeta>,
        prompt: String,
        allowedActions: List<String>,
    ): Outcome {
        val closeables = mutableListOf<Any>()
        return try {
            val feeds = mutableMapOf<String, Any>()
            inputMetas.forEach { meta ->
                val tensor = createTensorForInput(environment, meta, prompt)
                if (tensor != null) {
                    feeds[meta.name] = tensor
                    closeables += tensor
                }
            }

            if (feeds.isEmpty()) {
                return Outcome(json = null, failureReason = "No tensors for numeric model path.")
            }

            val result = runSession(session, feeds)
            closeables += result

            val stringOutput = extractJsonStringOutput(result)
            if (stringOutput != null) {
                return Outcome(json = stringOutput)
            }

            val numericJson = deriveJsonFromNumericOutputs(result, allowedActions)
            if (numericJson != null) {
                return Outcome(json = numericJson)
            }

            Outcome(json = null, failureReason = "Numeric-model outputs not decodable.")
        } finally {
            closeables.forEach { safeClose(it) }
        }
    }

    private fun runAutoregressiveModel(
        environment: Any,
        session: Any,
        inputMetas: List<InputMeta>,
        tokenizer: QwenTokenizer,
        prompt: String,
        allowedActions: List<String>,
    ): Outcome {
        val maxPromptTokens = inputMetas
            .firstOrNull { it.name.contains("input_ids", ignoreCase = true) }
            ?.shape
            ?.lastOrNull { it > 0 }
            ?.toInt()
            ?.coerceIn(128, 4096)
            ?: 1024

        val promptIds = tokenizer.encode(prompt, maxPromptTokens).toMutableList()
        if (promptIds.isEmpty()) {
            return Outcome(json = null, failureReason = "Tokenizer produced empty prompt ids.")
        }

        val generated = mutableListOf<Long>()
        var pastValues = emptyMap<String, Any>()
        var usePast = false
        val eosIds = tokenizer.eosTokenIds() + DEFAULT_EOS_IDS

        val maxNewTokens = 220
        repeat(maxNewTokens) {
            val currentInput = if (usePast && promptIds.isNotEmpty()) {
                longArrayOf(promptIds.last())
            } else {
                promptIds.toLongArray()
            }

            val step = runDecoderStep(
                environment = environment,
                session = session,
                inputMetas = inputMetas,
                inputIds = currentInput,
                fullTokenCount = promptIds.size,
                usePast = usePast,
                pastValues = pastValues,
            ) ?: return Outcome(json = null, failureReason = "Failed to run decoder step.")

            step.textOutput?.let { text ->
                normalizeJsonFromText(text)?.let { return Outcome(json = it) }
            }

            if (step.logits.isEmpty()) {
                return Outcome(json = null, failureReason = "Decoder step produced empty logits.")
            }

            val nextToken = argmax(step.logits).toLong()
            generated += nextToken
            promptIds += nextToken

            pastValues = step.pastValues
            usePast = pastValues.isNotEmpty() && inputMetas.any { isPastInput(it.name) }

            if (eosIds.contains(nextToken)) {
                return@repeat
            }
        }

        val decoded = tokenizer.decode(generated)
        val json = normalizeJsonFromText(decoded)
        if (json != null) {
            return Outcome(json = json)
        }

        val fallbackJson = normalizeJsonFromText(tokenizer.decode(promptIds))
        if (fallbackJson != null) {
            return Outcome(json = fallbackJson)
        }

        return Outcome(json = null, failureReason = "Autoregressive decode did not produce valid JSON.")
    }

    private fun runDecoderStep(
        environment: Any,
        session: Any,
        inputMetas: List<InputMeta>,
        inputIds: LongArray,
        fullTokenCount: Int,
        usePast: Boolean,
        pastValues: Map<String, Any>,
    ): DecoderStepResult? {
        val tensorsToClose = mutableListOf<Any>()
        val feeds = mutableMapOf<String, Any>()

        try {
            val pastInputNames = inputMetas
                .map { it.name }
                .filter { isPastInput(it) }

            inputMetas.forEach { meta ->
                val payload = when {
                    meta.name.contains("input_ids", ignoreCase = true) -> {
                        arrayOf(inputIds)
                    }
                    meta.name.contains("attention_mask", ignoreCase = true) -> {
                        val length = if (usePast) fullTokenCount else inputIds.size
                        arrayOf(LongArray(length) { 1L })
                    }
                    meta.name.contains("position_ids", ignoreCase = true) -> {
                        if (usePast) {
                            arrayOf(longArrayOf((fullTokenCount - 1).toLong()))
                        } else {
                            arrayOf(LongArray(inputIds.size) { index -> index.toLong() })
                        }
                    }
                    meta.name.contains("cache_position", ignoreCase = true) -> {
                        arrayOf(longArrayOf((fullTokenCount - 1).toLong()))
                    }
                    meta.name.contains("use_cache", ignoreCase = true) ||
                        meta.name.contains("use_cache_branch", ignoreCase = true) -> {
                        booleanPayload(meta.shape, usePast)
                    }
                    isPastInput(meta.name) -> {
                        val raw = resolvePastValue(meta.name, pastValues)
                        raw ?: zeroPayloadForMeta(meta)
                    }
                    meta.type.contains("FLOAT") -> zeroPayloadForMeta(meta)
                    meta.type.contains("INT64") || meta.type.contains("LONG") -> zeroPayloadForMeta(meta)
                    meta.type.contains("INT32") -> zeroPayloadForMeta(meta)
                    else -> null
                }

                val tensor = createTensor(environment, payload)
                if (tensor != null) {
                    feeds[meta.name] = tensor
                    tensorsToClose += tensor
                }
            }

            if (feeds.isEmpty()) {
                return null
            }

            val result = runSession(session, feeds)
            val outputs = extractOutputs(result)

            val textOutput = extractJsonStringOutput(result)
            val logits = extractLastLogits(outputs)

            val rawPastOutputs = outputs
                .filter { (name, _) -> isPastOutput(name) }
                .associate { (name, value) ->
                    name to (invokeNoArg(value, "getValue") ?: value)
                }

            safeClose(result)

            val mappedPast = mapPastOutputsToInputs(
                pastInputNames = pastInputNames,
                pastOutputValues = rawPastOutputs,
            )

            return DecoderStepResult(
                logits = logits,
                textOutput = textOutput,
                pastValues = mappedPast,
            )
        } finally {
            tensorsToClose.forEach { safeClose(it) }
        }
    }

    private fun mapPastOutputsToInputs(
        pastInputNames: List<String>,
        pastOutputValues: Map<String, Any>,
    ): Map<String, Any> {
        if (pastInputNames.isEmpty() || pastOutputValues.isEmpty()) {
            return emptyMap()
        }

        val mapped = mutableMapOf<String, Any>()

        pastInputNames.forEach { inputName ->
            if (pastOutputValues.containsKey(inputName)) {
                mapped[inputName] = pastOutputValues.getValue(inputName)
                return@forEach
            }

            val suffix = inputName.substringAfter('.', inputName)
            val candidate = pastOutputValues.entries.firstOrNull { (outputName, _) ->
                outputName.endsWith(suffix)
            }
            if (candidate != null) {
                mapped[inputName] = candidate.value
            }
        }

        if (mapped.size < pastInputNames.size && pastOutputValues.isNotEmpty()) {
            val leftovers = pastOutputValues.values.toList()
            var idx = 0
            pastInputNames.forEach { inputName ->
                if (!mapped.containsKey(inputName) && idx < leftovers.size) {
                    mapped[inputName] = leftovers[idx]
                    idx += 1
                }
            }
        }

        return mapped
    }

    private fun booleanPayload(shape: LongArray?, value: Boolean): Any {
        val safe = sanitizeShape(shape)
        return when (safe.size) {
            0, 1 -> booleanArrayOf(value)
            else -> arrayOf(booleanArrayOf(value))
        }
    }

    private fun createTensorForInput(
        environment: Any,
        inputMeta: InputMeta,
        prompt: String,
    ): Any? {
        return when {
            inputMeta.type.contains("STRING") -> {
                createTensor(environment, arrayOf(prompt))
            }
            inputMeta.type.contains("INT64") || inputMeta.type.contains("LONG") -> {
                val seqLen = resolveSequenceLength(inputMeta.shape)
                val tokens = fallbackTokenize(prompt, seqLen)

                val values = when {
                    inputMeta.name.contains("attention", ignoreCase = true) -> {
                        arrayOf(LongArray(seqLen) { index -> if (tokens[index] == 0L) 0L else 1L })
                    }
                    inputMeta.name.contains("position", ignoreCase = true) -> {
                        arrayOf(LongArray(seqLen) { it.toLong() })
                    }
                    else -> {
                        arrayOf(tokens)
                    }
                }
                createTensor(environment, values)
            }
            inputMeta.type.contains("INT32") -> {
                val seqLen = resolveSequenceLength(inputMeta.shape)
                val tokens = fallbackTokenize(prompt, seqLen)
                val intTokens = IntArray(seqLen) { index -> tokens[index].toInt() }
                createTensor(environment, arrayOf(intTokens))
            }
            inputMeta.type.contains("FLOAT") -> {
                val seqLen = resolveSequenceLength(inputMeta.shape)
                createTensor(environment, arrayOf(FloatArray(seqLen) { 0f }))
            }
            else -> null
        }
    }

    private fun resolveSequenceLength(shape: LongArray?): Int {
        if (shape == null || shape.isEmpty()) {
            return 128
        }

        val candidate = shape.lastOrNull { it > 0L } ?: 128L
        return candidate.toInt().coerceIn(16, 4096)
    }

    private fun runSession(session: Any, feeds: Map<String, Any>): Any {
        val run = session.javaClass.getMethod("run", Map::class.java)
        return run.invoke(session, feeds)
            ?: error("ONNX session returned null result.")
    }

    private fun extractJsonStringOutput(result: Any): String? {
        val outputs = extractOutputs(result)

        outputs.forEach { (_, value) ->
            val raw = invokeNoArg(value, "getValue") ?: return@forEach
            val text = firstString(raw) ?: return@forEach
            val json = normalizeJsonFromText(text)
            if (json != null) {
                return json
            }
        }

        return null
    }

    private fun extractLastLogits(outputs: List<Pair<String, Any>>): DoubleArray {
        val logitsOutput = outputs.firstOrNull { (name, _) ->
            name.lowercase(Locale.US).contains("logits")
        } ?: outputs.firstOrNull()

        val raw = logitsOutput?.second?.let { invokeNoArg(it, "getValue") ?: it }
            ?: return DoubleArray(0)

        return toLastDoubleVector(raw)
    }

    private fun toLastDoubleVector(value: Any): DoubleArray {
        return when (value) {
            is FloatArray -> DoubleArray(value.size) { index -> value[index].toDouble() }
            is DoubleArray -> value
            is Array<*> -> {
                if (value.isEmpty()) {
                    DoubleArray(0)
                } else {
                    val last = value.lastOrNull() ?: return DoubleArray(0)
                    toLastDoubleVector(last)
                }
            }
            else -> DoubleArray(0)
        }
    }

    private fun deriveJsonFromNumericOutputs(
        result: Any,
        allowedActions: List<String>,
    ): String? {
        val outputs = extractOutputs(result)
        val normalized = outputs.associate { (name, value) ->
            name.lowercase(Locale.US) to (invokeNoArg(value, "getValue") ?: value)
        }

        val actionLabels = allowedActions.ifEmpty {
            listOf(
                "click",
                "scroll",
                "long_press",
                "swipe",
                "type",
                "launch_app",
                "back",
                "home",
                "recent_apps",
                "open_notifications",
                "open_quick_settings",
                "noop",
            )
        }

        var action = "noop"
        var confidence = 0.0

        val logitsOutput = normalized.entries.firstOrNull { (name, _) ->
            name.contains("action") || name.contains("logit") || name.contains("prob")
        }

        if (logitsOutput != null) {
            val vector = toDoubleVector(logitsOutput.value)
            if (vector.isNotEmpty()) {
                val probs = softmax(vector)
                val maxIndex = probs.indices.maxByOrNull { probs[it] } ?: 0
                action = actionLabels.getOrElse(maxIndex.coerceIn(0, actionLabels.lastIndex)) { "noop" }
                confidence = probs[maxIndex]
            }
        }

        val targetId = normalized.entries.firstOrNull { (name, _) ->
            name.contains("target") || name.contains("node") || name.contains("id")
        }?.value?.let { toFirstInt(it) }

        if (action == "noop" && targetId == null && confidence == 0.0) {
            return null
        }

        val isSystemAction = action in SYSTEM_ACTIONS

        return JSONObject().apply {
            put("action", action)
            put("target_id", targetId)
            put("text", JSONObject.NULL)
            put("direction", JSONObject.NULL)
            put("start_id", JSONObject.NULL)
            put("end_id", JSONObject.NULL)
            put("app_name", JSONObject.NULL)
            put("package_name", JSONObject.NULL)
            put("requires_cursor", !isSystemAction)
            put("execution_mode", if (isSystemAction) "system_direct" else "ui_cursor")
            put("confidence", confidence.coerceIn(0.0, 1.0))
            put("reason", "Generated from numeric model outputs.")
        }.toString()
    }

    private fun normalizeJsonFromText(text: String): String? {
        val direct = runCatching { JSONObject(text) }.getOrNull()
        if (direct != null && direct.has("action")) {
            return direct.toString()
        }

        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start >= 0 && end > start) {
            val snippet = text.substring(start, end + 1)
            val parsed = runCatching { JSONObject(snippet) }.getOrNull()
            if (parsed != null && parsed.has("action")) {
                return parsed.toString()
            }
        }

        return null
    }

    private fun firstString(value: Any): String? {
        return when (value) {
            is String -> value
            is Array<*> -> {
                if (value.isEmpty()) {
                    null
                } else {
                    val first = value.firstOrNull()
                    when (first) {
                        is String -> first
                        is Array<*> -> first.firstOrNull()?.toString()
                        else -> first?.toString()
                    }
                }
            }
            else -> null
        }
    }

    private fun extractOutputs(result: Any): List<Pair<String, Any>> {
        val output = mutableListOf<Pair<String, Any>>()

        val iterable = result as? Iterable<*>
        iterable?.forEach { item ->
            val entry = item as? Map.Entry<*, *> ?: return@forEach
            val name = entry.key?.toString() ?: return@forEach
            val value = entry.value ?: return@forEach
            output += name to value
        }

        return output
    }

    private fun readInputMetadata(session: Any): List<InputMeta> {
        val getInputInfo = session.javaClass.getMethod("getInputInfo")
        val rawInfo = getInputInfo.invoke(session)
        val map = rawInfo as? Map<*, *> ?: return emptyList()

        return map.mapNotNull { entry ->
            val name = entry.key?.toString() ?: return@mapNotNull null
            val nodeInfo = entry.value ?: return@mapNotNull null
            val info = invokeNoArg(nodeInfo, "getInfo") ?: nodeInfo
            val type = readType(info)
            val shape = readShape(info)
            InputMeta(name, type, shape)
        }
    }

    private fun readType(info: Any): String {
        val typeObj = invokeNoArg(info, "getType") ?: invokeNoArg(info, "type")
        return typeObj?.toString()?.uppercase(Locale.US)
            ?: info.toString().uppercase(Locale.US)
    }

    private fun readShape(info: Any): LongArray? {
        val shapeObj = invokeNoArg(info, "getShape") ?: invokeNoArg(info, "shape")
        return when (shapeObj) {
            is LongArray -> shapeObj
            is IntArray -> LongArray(shapeObj.size) { shapeObj[it].toLong() }
            is Array<*> -> {
                val values = shapeObj.mapNotNull {
                    when (it) {
                        is Number -> it.toLong()
                        else -> null
                    }
                }
                if (values.isEmpty()) null else values.toLongArray()
            }
            else -> null
        }
    }

    private fun createTensor(environment: Any, payload: Any?): Any? {
        if (payload == null) {
            return null
        }

        return try {
            val tensorClass = Class.forName("ai.onnxruntime.OnnxTensor")
            val method = findCreateTensorMethod(tensorClass) ?: return null
            method.invoke(null, environment, payload)
        } catch (_: Throwable) {
            null
        }
    }

    private fun findCreateTensorMethod(tensorClass: Class<*>): Method? {
        return tensorClass.methods.firstOrNull {
            it.name == "createTensor" &&
                it.parameterTypes.size == 2 &&
                it.parameterTypes[0].name == "ai.onnxruntime.OrtEnvironment"
        } ?: tensorClass.methods.firstOrNull {
            it.name == "createTensor" && it.parameterTypes.size == 2
        }
    }

    private fun invokeNoArg(target: Any, name: String): Any? {
        return runCatching {
            val method = target.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 }
            method?.invoke(target)
        }.getOrNull()
    }

    private fun safeClose(target: Any) {
        runCatching {
            val closeMethod = target.javaClass.methods.firstOrNull {
                it.name == "close" && it.parameterCount == 0
            }
            closeMethod?.invoke(target)
        }
    }

    private fun toDoubleVector(value: Any): DoubleArray {
        return when (value) {
            is FloatArray -> DoubleArray(value.size) { index -> value[index].toDouble() }
            is DoubleArray -> value
            is Array<*> -> {
                if (value.isEmpty()) {
                    DoubleArray(0)
                } else {
                    val first = value.firstOrNull()
                    when (first) {
                        is FloatArray -> DoubleArray(first.size) { index -> first[index].toDouble() }
                        is DoubleArray -> first
                        is Array<*> -> first.mapNotNull { (it as? Number)?.toDouble() }.toDoubleArray()
                        else -> value.mapNotNull { (it as? Number)?.toDouble() }.toDoubleArray()
                    }
                }
            }
            else -> DoubleArray(0)
        }
    }

    private fun toFirstInt(value: Any): Int? {
        return when (value) {
            is Int -> value
            is Long -> value.toInt()
            is Float -> value.toInt()
            is Double -> value.toInt()
            is IntArray -> value.firstOrNull()
            is LongArray -> value.firstOrNull()?.toInt()
            is FloatArray -> value.firstOrNull()?.toInt()
            is DoubleArray -> value.firstOrNull()?.toInt()
            is Array<*> -> value.firstOrNull()?.let { (it as? Number)?.toInt() }
            else -> null
        }
    }

    private fun softmax(logits: DoubleArray): DoubleArray {
        if (logits.isEmpty()) {
            return DoubleArray(0)
        }

        val maxLogit = logits.maxOrNull() ?: 0.0
        val exps = DoubleArray(logits.size) { index -> exp(logits[index] - maxLogit) }
        val sum = exps.sum().takeIf { it > 0.0 } ?: 1.0
        return DoubleArray(logits.size) { index -> exps[index] / sum }
    }

    private fun argmax(logits: DoubleArray): Int {
        var best = 0
        var bestValue = Double.NEGATIVE_INFINITY
        logits.forEachIndexed { index, value ->
            if (value > bestValue) {
                bestValue = value
                best = index
            }
        }
        return best
    }

    private fun fallbackTokenize(text: String, seqLen: Int): LongArray {
        val tokens = text
            .split(Regex("[\\s,.;:!?()\\[\\]{}\\\"']+"))
            .map { it.trim() }
            .filter { it.isNotBlank() }

        val ids = LongArray(seqLen) { 0L }
        if (tokens.isEmpty()) {
            ids[0] = 1L
            return ids
        }

        val limit = minOf(tokens.size, seqLen)
        for (index in 0 until limit) {
            val hash = tokens[index].hashCode().toLong() and 0x7FFFFFFF
            ids[index] = 1L + (hash % 32000L)
        }
        return ids
    }

    private fun isPastInput(name: String): Boolean {
        val lower = name.lowercase(Locale.US)
        return lower.contains("past_key_values") ||
            (lower.contains("past") && !lower.contains("use_cache"))
    }

    private fun isPastOutput(name: String): Boolean {
        val lower = name.lowercase(Locale.US)
        return lower.contains("present") || lower.contains("past_key_values")
    }

    private fun resolvePastValue(name: String, pastValues: Map<String, Any>): Any? {
        pastValues[name]?.let { return it }

        val suffix = name.substringAfter('.', name)
        return pastValues.entries.firstOrNull { (key, _) ->
            key.endsWith(suffix)
        }?.value
    }

    private fun sanitizeShape(shape: LongArray?): IntArray {
        if (shape == null || shape.isEmpty()) {
            return intArrayOf(1)
        }
        return shape.map { dim ->
            when {
                dim <= 0 -> 1
                dim > 2048 -> 2048
                else -> dim.toInt()
            }
        }.toIntArray()
    }

    private fun zeroPayloadForMeta(meta: InputMeta): Any? {
        val shape = sanitizeShape(meta.shape)

        return when {
            meta.type.contains("FLOAT") -> createFloatPayload(shape)
            meta.type.contains("INT32") -> createIntPayload(shape)
            meta.type.contains("INT64") || meta.type.contains("LONG") -> createLongPayload(shape)
            else -> null
        }
    }

    private fun createFloatPayload(shape: IntArray): Any {
        return when (shape.size) {
            1 -> FloatArray(shape[0]) { 0f }
            2 -> Array(shape[0]) { FloatArray(shape[1]) { 0f } }
            3 -> Array(shape[0]) { Array(shape[1]) { FloatArray(shape[2]) { 0f } } }
            else -> Array(shape[0]) {
                Array(shape[1]) {
                    Array(shape[2]) {
                        FloatArray(shape[3]) { 0f }
                    }
                }
            }
        }
    }

    private fun createLongPayload(shape: IntArray): Any {
        return when (shape.size) {
            1 -> LongArray(shape[0]) { 0L }
            2 -> Array(shape[0]) { LongArray(shape[1]) { 0L } }
            3 -> Array(shape[0]) { Array(shape[1]) { LongArray(shape[2]) { 0L } } }
            else -> Array(shape[0]) {
                Array(shape[1]) {
                    Array(shape[2]) {
                        LongArray(shape[3]) { 0L }
                    }
                }
            }
        }
    }

    private fun createIntPayload(shape: IntArray): Any {
        return when (shape.size) {
            1 -> IntArray(shape[0]) { 0 }
            2 -> Array(shape[0]) { IntArray(shape[1]) { 0 } }
            3 -> Array(shape[0]) { Array(shape[1]) { IntArray(shape[2]) { 0 } } }
            else -> Array(shape[0]) {
                Array(shape[1]) {
                    Array(shape[2]) {
                        IntArray(shape[3]) { 0 }
                    }
                }
            }
        }
    }

    private data class InputMeta(
        val name: String,
        val type: String,
        val shape: LongArray?,
    )

    private data class DecoderStepResult(
        val logits: DoubleArray,
        val textOutput: String?,
        val pastValues: Map<String, Any>,
    )

    companion object {
        private val DEFAULT_EOS_IDS = setOf(151643L, 151645L)
        private val SYSTEM_ACTIONS = setOf(
            "launch_app",
            "back",
            "home",
            "recent_apps",
            "open_notifications",
            "open_quick_settings",
        )
    }
}

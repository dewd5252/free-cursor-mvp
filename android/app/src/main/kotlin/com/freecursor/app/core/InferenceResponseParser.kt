package com.freecursor.app.core

import org.json.JSONObject

class InferenceResponseParser(
    private val confidenceThreshold: Double = 0.35,
) {
    fun parse(rawJson: String, allowedActions: List<String>): InferenceResponseDto {
        return try {
            val payload = JSONObject(rawJson)
            val action = payload.optString("action", "noop")

            if (!allowedActions.contains(action)) {
                return InferenceResponseDto(
                    action = "noop",
                    confidence = 0.0,
                    reason = "Model returned unsupported action '$action'.",
                )
            }

            val parsed = InferenceResponseDto(
                action = action,
                targetId = payload.optIntOrNull("target_id"),
                text = payload.optNullableString("text"),
                direction = payload.optNullableString("direction"),
                startId = payload.optIntOrNull("start_id"),
                endId = payload.optIntOrNull("end_id"),
                appName = payload.optNullableString("app_name"),
                packageName = payload.optNullableString("package_name"),
                requiresCursor = payload.optBooleanOrNull("requires_cursor"),
                executionMode = payload.optNullableString("execution_mode"),
                confidence = payload.optDouble("confidence", 0.0),
                reason = payload.optString("reason", ""),
            )

            if (parsed.confidence < confidenceThreshold) {
                parsed.copy(
                    action = "noop",
                    reason = "Confidence below threshold (${parsed.confidence}).",
                )
            } else {
                parsed
            }
        } catch (error: Throwable) {
            InferenceResponseDto(
                action = "noop",
                confidence = 0.0,
                reason = "Invalid model output: ${error.message}",
            )
        }
    }

    private fun JSONObject.optNullableString(key: String): String? {
        if (!has(key) || isNull(key)) {
            return null
        }
        val value = optString(key, "")
        return value.takeIf { it.isNotBlank() }
    }

    private fun JSONObject.optIntOrNull(key: String): Int? {
        if (isNull(key) || !has(key)) {
            return null
        }
        return try {
            getInt(key)
        } catch (_: Throwable) {
            null
        }
    }

    private fun JSONObject.optBooleanOrNull(key: String): Boolean? {
        if (isNull(key) || !has(key)) {
            return null
        }
        return try {
            getBoolean(key)
        } catch (_: Throwable) {
            null
        }
    }
}

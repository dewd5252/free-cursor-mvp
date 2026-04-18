package com.freecursor.app.core

import org.json.JSONArray
import org.json.JSONObject

class OnnxPromptBuilder {
    fun buildPrompt(request: InferenceRequestDto): String {
        val payload = JSONObject().apply {
            put("task", "screen_action_selection")
            put("instruction", request.userCommand)
            put("allowed_actions", JSONArray(request.allowedActions))
            put("screen_nodes", JSONArray().also { array ->
                request.screenData.forEach { node ->
                    array.put(JSONObject(node.toMap()))
                }
            })
            put(
                "expected_output_schema",
                JSONObject().apply {
                    put("action", "string")
                    put("target_id", "number|null")
                    put("text", "string|null")
                    put("direction", "up|down|left|right|null")
                    put("start_id", "number|null")
                    put("end_id", "number|null")
                    put("app_name", "string|null")
                    put("package_name", "string|null")
                    put("requires_cursor", "boolean|null")
                    put("execution_mode", "system_direct|ui_cursor|null")
                    put("confidence", "number[0..1]")
                    put("reason", "string")
                },
            )
        }

        return buildString {
            appendLine("You are a mobile UI action planner.")
            appendLine("Return ONLY one JSON object using the required schema.")
            appendLine("Do not include markdown, comments, or explanations outside JSON.")
            append(payload.toString())
        }
    }
}

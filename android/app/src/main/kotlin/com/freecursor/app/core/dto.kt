package com.freecursor.app.core

data class BoundsDto(
    val l: Int,
    val t: Int,
    val r: Int,
    val b: Int,
) {
    fun centerX(): Int = (l + r) / 2

    fun centerY(): Int = (t + b) / 2

    fun toMap(): Map<String, Int> {
        return mapOf(
            "l" to l,
            "t" to t,
            "r" to r,
            "b" to b,
        )
    }
}

data class ScreenNodeDto(
    val id: Int,
    val role: String,
    val text: String,
    val hint: String,
    val clickable: Boolean,
    val enabled: Boolean,
    val editable: Boolean,
    val bounds: BoundsDto,
    val packageName: String,
    val className: String,
    val actions: List<String>,
) {
    fun toMap(): Map<String, Any> {
        return mapOf(
            "id" to id,
            "role" to role,
            "text" to text,
            "hint" to hint,
            "clickable" to clickable,
            "enabled" to enabled,
            "editable" to editable,
            "bounds" to bounds.toMap(),
            "packageName" to packageName,
            "className" to className,
            "actions" to actions,
        )
    }
}

data class InferenceRequestDto(
    val userCommand: String,
    val screenData: List<ScreenNodeDto>,
    val allowedActions: List<String>,
)

data class InferenceResponseDto(
    val action: String = "noop",
    val targetId: Int? = null,
    val text: String? = null,
    val direction: String? = null,
    val startId: Int? = null,
    val endId: Int? = null,
    val appName: String? = null,
    val packageName: String? = null,
    val requiresCursor: Boolean? = null,
    val executionMode: String? = null,
    val confidence: Double = 0.0,
    val reason: String = "",
) {
    fun toMap(): Map<String, Any?> {
        return mapOf(
            "action" to action,
            "target_id" to targetId,
            "text" to text,
            "direction" to direction,
            "start_id" to startId,
            "end_id" to endId,
            "app_name" to appName,
            "package_name" to packageName,
            "requires_cursor" to requiresCursor,
            "execution_mode" to executionMode,
            "confidence" to confidence,
            "reason" to reason,
        )
    }
}

data class ActionCommandDto(
    val action: String,
    val targetId: Int? = null,
    val text: String? = null,
    val direction: String? = null,
    val startId: Int? = null,
    val endId: Int? = null,
    val appName: String? = null,
    val packageName: String? = null,
    val requiresCursor: Boolean? = null,
    val executionMode: String? = null,
)

fun InferenceResponseDto.toActionCommand(): ActionCommandDto {
    return ActionCommandDto(
        action = action,
        targetId = targetId,
        text = text,
        direction = direction,
        startId = startId,
        endId = endId,
        appName = appName,
        packageName = packageName,
        requiresCursor = requiresCursor,
        executionMode = executionMode,
    )
}

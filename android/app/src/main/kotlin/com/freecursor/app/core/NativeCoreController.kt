package com.freecursor.app.core

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.freecursor.app.bridge.BridgeEventEmitter

class NativeCoreController(
    private val appContext: Context,
) {
    private val prefs = appContext.getSharedPreferences("free_cursor_prefs", Context.MODE_PRIVATE)
    private val overlayController = OverlayController(appContext)
    private val modelManager = ModelManager.get(appContext)
    private val orchestrator = CommandOrchestrator(modelManager)

    @Volatile
    private var pendingAction: InferenceResponseDto? = null

    @Volatile
    private var coreRunning: Boolean = false

    private var scopeAllApps: Boolean
        get() = prefs.getBoolean(KEY_SCOPE_ALL_APPS, true)
        set(value) {
            prefs.edit().putBoolean(KEY_SCOPE_ALL_APPS, value).apply()
        }

    private var cursorEnabled: Boolean
        get() = prefs.getBoolean(KEY_CURSOR_ENABLED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_CURSOR_ENABLED, value).apply()
        }

    fun initialize(): Map<String, Any> {
        return buildStatusMap()
    }

    fun requestOverlayPermission(): Map<String, Any> {
        if (!Settings.canDrawOverlays(appContext)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${appContext.packageName}"),
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appContext.startActivity(intent)
        }

        val map = buildStatusMap()
        BridgeEventEmitter.emit("permission_changed", map)
        return map
    }

    fun openAccessibilitySettings(): Map<String, Any> {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        appContext.startActivity(intent)

        val map = buildStatusMap()
        BridgeEventEmitter.emit("permission_changed", map)
        return map
    }

    fun startCore(): Map<String, Any> {
        coreRunning = true
        ModelForegroundService.start(appContext)

        if (cursorEnabled && Settings.canDrawOverlays(appContext)) {
            overlayController.show()
        }

        val map = buildStatusMap()
        BridgeEventEmitter.emit("core_state_changed", map)
        return map
    }

    fun stopCore(): Map<String, Any> {
        coreRunning = false
        pendingAction = null
        overlayController.hide()
        ModelForegroundService.stop(appContext)

        val map = buildStatusMap()
        BridgeEventEmitter.emit("core_state_changed", map)
        return map
    }

    fun submitCommand(userInput: String): Map<String, Any> {
        if (userInput.isBlank()) {
            return emitError("Empty command is not allowed.")
        }

        val service = FreeCursorAccessibilityService.instance
            ?: return emitError("Accessibility service is not connected.")

        val snapshot = service.captureSnapshot()
        if (snapshot.isEmpty()) {
            return emitError("No screen nodes available from accessibility tree.")
        }

        val request = InferenceRequestDto(
            userCommand = userInput,
            screenData = snapshot,
            allowedActions = ALLOWED_ACTIONS,
        )

        val response = orchestrator.infer(request)
        pendingAction = response

        BridgeEventEmitter.emit("proposed_action", response.toMap())
        return buildStatusMap(
            extra = mapOf("proposed_action" to response.toMap()),
        )
    }

    fun confirmAction(): Map<String, Any> {
        val action = pendingAction ?: return emitError("No pending action to confirm.")
        val service = FreeCursorAccessibilityService.instance
            ?: return emitError("Accessibility service is not connected.")

        val result = service.execute(action.toActionCommand())
        pendingAction = null

        BridgeEventEmitter.emit("action_result", result)
        return buildStatusMap(extra = mapOf("action_result" to result))
    }

    fun cancelAction(): Map<String, Any> {
        pendingAction = null
        return buildStatusMap()
    }

    fun toggleCursor(enabled: Boolean): Map<String, Any> {
        cursorEnabled = enabled
        if (enabled && Settings.canDrawOverlays(appContext)) {
            overlayController.show()
        } else {
            overlayController.hide()
        }
        return buildStatusMap()
    }

    fun getSnapshot(): Map<String, Any> {
        val service = FreeCursorAccessibilityService.instance
            ?: return emitError("Accessibility service is not connected.")

        val snapshotJson = service.captureSnapshotJson()
        return buildStatusMap(extra = mapOf("snapshot_json" to snapshotJson))
    }

    fun downloadModel(url: String): Map<String, Any> {
        if (url.isBlank()) {
            return emitError("Model URL is required.")
        }

        modelManager.markDownloading()
        ModelForegroundService.download(appContext, url)
        return buildStatusMap()
    }

    fun getModelStatus(): Map<String, Any> {
        return buildStatusMap()
    }

    fun setScopeAllApps(enabled: Boolean): Map<String, Any> {
        scopeAllApps = enabled
        return buildStatusMap()
    }

    private fun buildStatusMap(extra: Map<String, Any?> = emptyMap()): Map<String, Any> {
        val coreStatus = when {
            !coreRunning -> "stopped"
            FreeCursorAccessibilityService.instance == null -> "starting"
            else -> "running"
        }

        val map = linkedMapOf<String, Any>(
            "permission_status" to permissionStatus(),
            "core_status" to coreStatus,
            "model_status" to modelManager.getModelStatus(),
            "bundle_has_tokenizer" to modelManager.hasTokenizerBundle(),
            "cursor_enabled" to (cursorEnabled && overlayController.isVisible()),
            "scope_all_apps" to scopeAllApps,
        )

        extra.forEach { (key, value) ->
            if (value != null) {
                map[key] = value
            }
        }

        return map
    }

    private fun permissionStatus(): String {
        val overlay = Settings.canDrawOverlays(appContext)
        val accessibility = isAccessibilityServiceEnabled()

        return when {
            overlay && accessibility -> "ready"
            !overlay && !accessibility -> "all_denied"
            !overlay -> "overlay_denied"
            else -> "accessibility_denied"
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expected = ComponentName(appContext, FreeCursorAccessibilityService::class.java)
            .flattenToString()
            .lowercase()

        val enabled = Settings.Secure.getString(
            appContext.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false

        return enabled.lowercase().split(':').any { it == expected }
    }

    private fun emitError(message: String): Map<String, Any> {
        BridgeEventEmitter.emit("error", mapOf("message" to message))
        return buildStatusMap(extra = mapOf("error" to message))
    }

    companion object {
        private const val KEY_CURSOR_ENABLED = "cursor_enabled"
        private const val KEY_SCOPE_ALL_APPS = "scope_all_apps"
        private val ALLOWED_ACTIONS = listOf(
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
}

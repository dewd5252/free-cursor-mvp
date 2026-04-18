package com.freecursor.app.core

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.freecursor.app.bridge.BridgeEventEmitter
import org.json.JSONArray
import org.json.JSONObject

class FreeCursorAccessibilityService : AccessibilityService() {
    private val extractor = NodeExtractor()
    private val executor by lazy { ActionExecutor(this) }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        BridgeEventEmitter.emit(
            "core_state_changed",
            mapOf(
                "core_status" to "running",
                "permission_status" to "ready",
            ),
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Accessibility events are observed passively; snapshot is collected on demand.
    }

    override fun onInterrupt() {
        // No-op for MVP.
    }

    override fun onDestroy() {
        super.onDestroy()
        NodeRegistry.clear()
        if (instance === this) {
            instance = null
        }
        BridgeEventEmitter.emit("core_state_changed", mapOf("core_status" to "stopped"))
    }

    fun captureSnapshot(): List<ScreenNodeDto> {
        val root = rootInActiveWindow ?: return emptyList()
        val extraction = extractor.extract(root)
        NodeRegistry.replace(extraction.nodeMap)
        return extraction.nodes
    }

    fun captureSnapshotJson(): String {
        val nodes = captureSnapshot()
        val array = JSONArray()
        nodes.forEach { node ->
            array.put(JSONObject(node.toMap()))
        }
        return array.toString()
    }

    fun execute(command: ActionCommandDto): Map<String, Any> {
        return executor.execute(command)
    }

    companion object {
        @Volatile
        var instance: FreeCursorAccessibilityService? = null
            private set
    }
}

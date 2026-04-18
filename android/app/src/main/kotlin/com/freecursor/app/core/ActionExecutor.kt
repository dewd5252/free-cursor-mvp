package com.freecursor.app.core

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ActionExecutor(
    private val service: AccessibilityService,
) {
    fun execute(command: ActionCommandDto): Map<String, Any> {
        return try {
            when (command.action) {
                "click" -> executeClick(command.targetId)
                "scroll" -> executeScroll(command.targetId, command.direction)
                "long_press" -> executeLongPress(command.targetId)
                "swipe" -> executeSwipe(command.startId, command.endId, command.direction)
                "type" -> executeType(command.targetId, command.text)
                "launch_app" -> executeLaunchApp(command.packageName, command.appName)
                "back" -> executeGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK, "Back")
                "home" -> executeGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME, "Home")
                "recent_apps" -> executeGlobalAction(
                    AccessibilityService.GLOBAL_ACTION_RECENTS,
                    "Recent apps",
                )
                "open_notifications" -> executeGlobalAction(
                    AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS,
                    "Notifications",
                )
                "open_quick_settings" -> executeGlobalAction(
                    AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS,
                    "Quick settings",
                )
                else -> mapOf("success" to false, "message" to "No-op action.")
            }
        } catch (error: Throwable) {
            mapOf(
                "success" to false,
                "message" to "Execution failed: ${error.message}",
            )
        }
    }

    private fun executeClick(targetId: Int?): Map<String, Any> {
        val node = targetId?.let { NodeRegistry.get(it) }
        if (node == null) {
            return mapOf("success" to false, "message" to "Target node not found for click.")
        }

        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return mapOf("success" to true, "message" to "ACTION_CLICK executed.")
        }

        val (x, y) = centerOf(node)
        val success = dispatchTap(x, y, durationMs = 80L)
        return mapOf(
            "success" to success,
            "message" to if (success) "Fallback tap gesture executed." else "Tap gesture failed.",
        )
    }

    private fun executeScroll(targetId: Int?, direction: String?): Map<String, Any> {
        val node = targetId?.let { NodeRegistry.get(it) }
        val forward = direction?.lowercase() != "up"

        if (node != null) {
            val action = if (forward) {
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            } else {
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            }
            if (node.performAction(action)) {
                return mapOf("success" to true, "message" to "Node scroll action executed.")
            }
        }

        val root = service.rootInActiveWindow
        if (root != null) {
            val rect = Rect()
            root.getBoundsInScreen(rect)
            val startY = if (forward) (rect.bottom * 0.75).toInt() else (rect.bottom * 0.25).toInt()
            val endY = if (forward) (rect.bottom * 0.25).toInt() else (rect.bottom * 0.75).toInt()
            val centerX = (rect.right * 0.5).toInt()
            val success = dispatchSwipe(centerX, startY, centerX, endY, 250L)
            root.recycle()
            return mapOf(
                "success" to success,
                "message" to if (success) "Fallback swipe scroll executed." else "Scroll swipe failed.",
            )
        }

        return mapOf("success" to false, "message" to "No node or root to perform scroll.")
    }

    private fun executeLongPress(targetId: Int?): Map<String, Any> {
        val node = targetId?.let { NodeRegistry.get(it) }
        if (node == null) {
            return mapOf("success" to false, "message" to "Target node not found for long press.")
        }

        if (node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) {
            return mapOf("success" to true, "message" to "ACTION_LONG_CLICK executed.")
        }

        val (x, y) = centerOf(node)
        val success = dispatchTap(x, y, durationMs = 700L)
        return mapOf(
            "success" to success,
            "message" to if (success) "Fallback long press gesture executed." else "Long press failed.",
        )
    }

    private fun executeSwipe(
        startId: Int?,
        endId: Int?,
        direction: String?,
    ): Map<String, Any> {
        val startNode = startId?.let { NodeRegistry.get(it) }
        val endNode = endId?.let { NodeRegistry.get(it) }

        val (startX, startY, endX, endY) = if (startNode != null && endNode != null) {
            val (sx, sy) = centerOf(startNode)
            val (ex, ey) = centerOf(endNode)
            arrayOf(sx, sy, ex, ey)
        } else {
            val root = service.rootInActiveWindow
                ?: return mapOf("success" to false, "message" to "No root window for swipe fallback.")
            val rect = Rect()
            root.getBoundsInScreen(rect)
            root.recycle()
            when (direction?.lowercase()) {
                "left" -> arrayOf(
                    (rect.right * 0.75).toInt(),
                    (rect.bottom * 0.5).toInt(),
                    (rect.right * 0.25).toInt(),
                    (rect.bottom * 0.5).toInt(),
                )
                "right" -> arrayOf(
                    (rect.right * 0.25).toInt(),
                    (rect.bottom * 0.5).toInt(),
                    (rect.right * 0.75).toInt(),
                    (rect.bottom * 0.5).toInt(),
                )
                "up" -> arrayOf(
                    (rect.right * 0.5).toInt(),
                    (rect.bottom * 0.75).toInt(),
                    (rect.right * 0.5).toInt(),
                    (rect.bottom * 0.25).toInt(),
                )
                else -> arrayOf(
                    (rect.right * 0.5).toInt(),
                    (rect.bottom * 0.25).toInt(),
                    (rect.right * 0.5).toInt(),
                    (rect.bottom * 0.75).toInt(),
                )
            }
        }

        val success = dispatchSwipe(startX, startY, endX, endY, 300L)
        return mapOf(
            "success" to success,
            "message" to if (success) "Swipe executed." else "Swipe failed.",
        )
    }

    private fun executeType(targetId: Int?, text: String?): Map<String, Any> {
        val target = targetId?.let { NodeRegistry.get(it) }
            ?: NodeRegistry.get(1)
            ?: return mapOf("success" to false, "message" to "Target node not found for typing.")

        val payload = text?.trim().orEmpty()
        if (payload.isEmpty()) {
            return mapOf("success" to false, "message" to "Typing action missing text payload.")
        }

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, payload)
        }
        val success = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        return mapOf(
            "success" to success,
            "message" to if (success) "Text typed successfully." else "Failed to type text.",
        )
    }

    private fun executeLaunchApp(packageName: String?, appName: String?): Map<String, Any> {
        val packageManager = service.packageManager

        val launchIntent = packageName
            ?.takeIf { it.isNotBlank() }
            ?.let { packageManager.getLaunchIntentForPackage(it) }

        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            service.startActivity(launchIntent)
            return mapOf(
                "success" to true,
                "message" to "Launched ${appName ?: packageName}.",
            )
        }

        return mapOf(
            "success" to false,
            "message" to "Unable to launch app. Missing or invalid package: ${packageName ?: "null"}",
        )
    }

    private fun executeGlobalAction(actionId: Int, label: String): Map<String, Any> {
        val success = service.performGlobalAction(actionId)
        return mapOf(
            "success" to success,
            "message" to if (success) "$label action executed." else "$label action failed.",
        )
    }

    private fun centerOf(node: AccessibilityNodeInfo): Pair<Int, Int> {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        return Pair((rect.left + rect.right) / 2, (rect.top + rect.bottom) / 2)
    }

    private fun dispatchTap(x: Int, y: Int, durationMs: Long): Boolean {
        val path = Path().apply {
            moveTo(x.toFloat(), y.toFloat())
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()

        return dispatchGestureSync(gesture)
    }

    private fun dispatchSwipe(
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        durationMs: Long,
    ): Boolean {
        val path = Path().apply {
            moveTo(startX.toFloat(), startY.toFloat())
            lineTo(endX.toFloat(), endY.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()

        return dispatchGestureSync(gesture)
    }

    private fun dispatchGestureSync(gesture: GestureDescription): Boolean {
        val latch = CountDownLatch(1)
        var success = false

        val callback = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                success = true
                latch.countDown()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                success = false
                latch.countDown()
            }
        }

        service.dispatchGesture(gesture, callback, Handler(Looper.getMainLooper()))
        latch.await(2, TimeUnit.SECONDS)
        return success
    }
}

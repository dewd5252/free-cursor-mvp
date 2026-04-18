package com.freecursor.app.bridge

import android.content.Context
import com.freecursor.app.core.NativeCoreController
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class FreeCursorBridge(
    appContext: Context,
    messenger: BinaryMessenger,
) : MethodChannel.MethodCallHandler, EventChannel.StreamHandler {
    private val methodChannel = MethodChannel(messenger, METHOD_CHANNEL_NAME)
    private val eventChannel = EventChannel(messenger, EVENT_CHANNEL_NAME)
    private val controller = NativeCoreController(appContext)
    private val backgroundExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    init {
        methodChannel.setMethodCallHandler(this)
        eventChannel.setStreamHandler(this)
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "initialize" -> result.success(controller.initialize())
            "requestOverlayPermission" -> result.success(controller.requestOverlayPermission())
            "openAccessibilitySettings" -> result.success(controller.openAccessibilitySettings())
            "startCore" -> result.success(controller.startCore())
            "stopCore" -> result.success(controller.stopCore())
            "toggleCursor" -> {
                val enabled = call.argument<Boolean>("enabled") ?: false
                result.success(controller.toggleCursor(enabled))
            }
            "setScopeAllApps" -> {
                val enabled = call.argument<Boolean>("enabled") ?: true
                result.success(controller.setScopeAllApps(enabled))
            }
            "getModelStatus" -> result.success(controller.getModelStatus())
            "cancelAction" -> result.success(controller.cancelAction())
            "submitCommand" -> {
                val userInput = call.argument<String>("user_input").orEmpty()
                runAsync(result) {
                    controller.submitCommand(userInput)
                }
            }
            "confirmAction" -> {
                runAsync(result) {
                    controller.confirmAction()
                }
            }
            "getSnapshot" -> {
                runAsync(result) {
                    controller.getSnapshot()
                }
            }
            "downloadModel" -> {
                val url = call.argument<String>("url").orEmpty()
                runAsync(result) {
                    controller.downloadModel(url)
                }
            }
            else -> result.notImplemented()
        }
    }

    private fun runAsync(
        result: MethodChannel.Result,
        operation: () -> Map<String, Any>,
    ) {
        backgroundExecutor.execute {
            try {
                result.success(operation())
            } catch (error: Throwable) {
                result.error(
                    "native_error",
                    error.message,
                    null,
                )
            }
        }
    }

    override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
        BridgeEventEmitter.attachSink(events)
    }

    override fun onCancel(arguments: Any?) {
        BridgeEventEmitter.attachSink(null)
    }

    companion object {
        private const val METHOD_CHANNEL_NAME = "com.freecursor.app/native"
        private const val EVENT_CHANNEL_NAME = "com.freecursor.app/events"
    }
}

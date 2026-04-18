package com.freecursor.app.bridge

import android.os.Handler
import android.os.Looper
import io.flutter.plugin.common.EventChannel

object BridgeEventEmitter {
    private val handler = Handler(Looper.getMainLooper())
    @Volatile
    private var sink: EventChannel.EventSink? = null

    fun attachSink(eventSink: EventChannel.EventSink?) {
        sink = eventSink
    }

    fun emit(type: String, payload: Any?) {
        val event = mapOf(
            "type" to type,
            "payload" to payload,
        )
        handler.post {
            sink?.success(event)
        }
    }
}

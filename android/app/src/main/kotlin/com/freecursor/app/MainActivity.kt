package com.freecursor.app

import com.freecursor.app.bridge.FreeCursorBridge
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine

class MainActivity : FlutterActivity() {
    private var bridge: FreeCursorBridge? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        bridge = FreeCursorBridge(
            applicationContext,
            flutterEngine.dartExecutor.binaryMessenger,
        )
    }
}

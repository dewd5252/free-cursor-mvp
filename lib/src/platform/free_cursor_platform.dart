import 'dart:async';

import 'package:flutter/services.dart';

class FreeCursorPlatform {
  FreeCursorPlatform._();

  static const MethodChannel _methodChannel = MethodChannel(
    'com.freecursor.app/native',
  );
  static const EventChannel _eventChannel = EventChannel(
    'com.freecursor.app/events',
  );

  static Stream<Map<dynamic, dynamic>> get events {
    return _eventChannel
        .receiveBroadcastStream()
        .where((event) => event is Map)
        .cast<Map<dynamic, dynamic>>();
  }

  static Future<Map<dynamic, dynamic>> initialize() async {
    return _invokeMap('initialize');
  }

  static Future<Map<dynamic, dynamic>> requestOverlayPermission() async {
    return _invokeMap('requestOverlayPermission');
  }

  static Future<Map<dynamic, dynamic>> openAccessibilitySettings() async {
    return _invokeMap('openAccessibilitySettings');
  }

  static Future<Map<dynamic, dynamic>> startCore() async {
    return _invokeMap('startCore');
  }

  static Future<Map<dynamic, dynamic>> stopCore() async {
    return _invokeMap('stopCore');
  }

  static Future<Map<dynamic, dynamic>> submitCommand(String userInput) async {
    return _invokeMap('submitCommand', <String, dynamic>{
      'user_input': userInput,
    });
  }

  static Future<Map<dynamic, dynamic>> confirmAction() async {
    return _invokeMap('confirmAction');
  }

  static Future<Map<dynamic, dynamic>> cancelAction() async {
    return _invokeMap('cancelAction');
  }

  static Future<Map<dynamic, dynamic>> toggleCursor(bool enabled) async {
    return _invokeMap('toggleCursor', <String, dynamic>{'enabled': enabled});
  }

  static Future<Map<dynamic, dynamic>> getSnapshot() async {
    return _invokeMap('getSnapshot');
  }

  static Future<Map<dynamic, dynamic>> downloadModel(String modelUrl) async {
    return _invokeMap('downloadModel', <String, dynamic>{'url': modelUrl});
  }

  static Future<Map<dynamic, dynamic>> getModelStatus() async {
    return _invokeMap('getModelStatus');
  }

  static Future<Map<dynamic, dynamic>> setScopeAllApps(bool enabled) async {
    return _invokeMap('setScopeAllApps', <String, dynamic>{'enabled': enabled});
  }

  static Future<Map<dynamic, dynamic>> _invokeMap(
    String method, [
    Map<String, dynamic>? arguments,
  ]) async {
    final result = await _methodChannel.invokeMethod<dynamic>(
      method,
      arguments,
    );
    if (result is Map) {
      return result;
    }
    return <dynamic, dynamic>{};
  }
}

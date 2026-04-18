import 'dart:convert';

enum PermissionStatus {
  unknown,
  ready,
  overlayDenied,
  accessibilityDenied,
  allDenied,
}

enum CoreStatus { stopped, starting, running, error }

enum ModelStatus { unavailable, downloading, ready, failed }

enum ActionType {
  click,
  scroll,
  longPress,
  swipe,
  type,
  launchApp,
  back,
  home,
  recentApps,
  openNotifications,
  openQuickSettings,
  noop,
}

class ProposedAction {
  const ProposedAction({
    required this.action,
    required this.confidence,
    this.targetId,
    this.text,
    this.direction,
    this.startId,
    this.endId,
    this.appName,
    this.packageName,
    this.requiresCursor,
    this.executionMode,
    this.reason,
  });

  final ActionType action;
  final double confidence;
  final int? targetId;
  final String? text;
  final String? direction;
  final int? startId;
  final int? endId;
  final String? appName;
  final String? packageName;
  final bool? requiresCursor;
  final String? executionMode;
  final String? reason;

  factory ProposedAction.fromMap(Map<dynamic, dynamic> map) {
    return ProposedAction(
      action: _actionFromString((map['action'] ?? 'noop').toString()),
      confidence: _toDouble(map['confidence']),
      targetId: _toIntOrNull(map['target_id']),
      text: map['text']?.toString(),
      direction: map['direction']?.toString(),
      startId: _toIntOrNull(map['start_id']),
      endId: _toIntOrNull(map['end_id']),
      appName: map['app_name']?.toString(),
      packageName: map['package_name']?.toString(),
      requiresCursor: map['requires_cursor'] is bool
          ? map['requires_cursor'] as bool
          : null,
      executionMode: map['execution_mode']?.toString(),
      reason: map['reason']?.toString(),
    );
  }

  Map<String, dynamic> toMap() {
    return <String, dynamic>{
      'action': action.name,
      'target_id': targetId,
      'text': text,
      'direction': direction,
      'start_id': startId,
      'end_id': endId,
      'app_name': appName,
      'package_name': packageName,
      'requires_cursor': requiresCursor,
      'execution_mode': executionMode,
      'confidence': confidence,
      'reason': reason,
    };
  }

  String prettyJson() => const JsonEncoder.withIndent('  ').convert(toMap());
}

class ExecutionResult {
  const ExecutionResult({
    required this.success,
    required this.message,
    this.raw = const <String, dynamic>{},
  });

  final bool success;
  final String message;
  final Map<String, dynamic> raw;

  factory ExecutionResult.fromMap(Map<dynamic, dynamic> map) {
    final raw = <String, dynamic>{};
    map.forEach((key, value) {
      raw[key.toString()] = value;
    });

    return ExecutionResult(
      success: map['success'] == true,
      message: map['message']?.toString() ?? '',
      raw: raw,
    );
  }
}

class AppState {
  const AppState({
    this.permissionStatus = PermissionStatus.unknown,
    this.coreStatus = CoreStatus.stopped,
    this.modelStatus = ModelStatus.unavailable,
    this.pendingAction,
    this.lastExecution,
    this.snapshotJson,
    this.cursorEnabled = false,
    this.scopeAllApps = true,
    this.isProcessing = false,
    this.lastError,
  });

  final PermissionStatus permissionStatus;
  final CoreStatus coreStatus;
  final ModelStatus modelStatus;
  final ProposedAction? pendingAction;
  final ExecutionResult? lastExecution;
  final String? snapshotJson;
  final bool cursorEnabled;
  final bool scopeAllApps;
  final bool isProcessing;
  final String? lastError;

  bool get permissionsReady => permissionStatus == PermissionStatus.ready;

  AppState copyWith({
    PermissionStatus? permissionStatus,
    CoreStatus? coreStatus,
    ModelStatus? modelStatus,
    ProposedAction? pendingAction,
    bool clearPendingAction = false,
    ExecutionResult? lastExecution,
    bool clearLastExecution = false,
    String? snapshotJson,
    bool clearSnapshot = false,
    bool? cursorEnabled,
    bool? scopeAllApps,
    bool? isProcessing,
    String? lastError,
    bool clearError = false,
  }) {
    return AppState(
      permissionStatus: permissionStatus ?? this.permissionStatus,
      coreStatus: coreStatus ?? this.coreStatus,
      modelStatus: modelStatus ?? this.modelStatus,
      pendingAction: clearPendingAction
          ? null
          : (pendingAction ?? this.pendingAction),
      lastExecution: clearLastExecution
          ? null
          : (lastExecution ?? this.lastExecution),
      snapshotJson: clearSnapshot ? null : (snapshotJson ?? this.snapshotJson),
      cursorEnabled: cursorEnabled ?? this.cursorEnabled,
      scopeAllApps: scopeAllApps ?? this.scopeAllApps,
      isProcessing: isProcessing ?? this.isProcessing,
      lastError: clearError ? null : (lastError ?? this.lastError),
    );
  }
}

PermissionStatus permissionStatusFromString(String value) {
  switch (value) {
    case 'ready':
      return PermissionStatus.ready;
    case 'overlay_denied':
      return PermissionStatus.overlayDenied;
    case 'accessibility_denied':
      return PermissionStatus.accessibilityDenied;
    case 'all_denied':
      return PermissionStatus.allDenied;
    default:
      return PermissionStatus.unknown;
  }
}

CoreStatus coreStatusFromString(String value) {
  switch (value) {
    case 'starting':
      return CoreStatus.starting;
    case 'running':
      return CoreStatus.running;
    case 'error':
      return CoreStatus.error;
    default:
      return CoreStatus.stopped;
  }
}

ModelStatus modelStatusFromString(String value) {
  switch (value) {
    case 'downloading':
      return ModelStatus.downloading;
    case 'ready':
      return ModelStatus.ready;
    case 'failed':
      return ModelStatus.failed;
    default:
      return ModelStatus.unavailable;
  }
}

ActionType _actionFromString(String value) {
  switch (value) {
    case 'click':
      return ActionType.click;
    case 'scroll':
      return ActionType.scroll;
    case 'long_press':
      return ActionType.longPress;
    case 'swipe':
      return ActionType.swipe;
    case 'type':
      return ActionType.type;
    case 'launch_app':
      return ActionType.launchApp;
    case 'back':
      return ActionType.back;
    case 'home':
      return ActionType.home;
    case 'recent_apps':
      return ActionType.recentApps;
    case 'open_notifications':
      return ActionType.openNotifications;
    case 'open_quick_settings':
      return ActionType.openQuickSettings;
    default:
      return ActionType.noop;
  }
}

int? _toIntOrNull(dynamic value) {
  if (value == null) {
    return null;
  }
  if (value is int) {
    return value;
  }
  return int.tryParse(value.toString());
}

double _toDouble(dynamic value) {
  if (value is double) {
    return value;
  }
  if (value is int) {
    return value.toDouble();
  }
  return double.tryParse(value?.toString() ?? '') ?? 0.0;
}

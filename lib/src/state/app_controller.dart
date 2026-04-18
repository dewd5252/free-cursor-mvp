import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../models/native_models.dart';
import '../platform/free_cursor_platform.dart';

final appControllerProvider = StateNotifierProvider<AppController, AppState>((
  ref,
) {
  return AppController();
});

class AppController extends StateNotifier<AppState> {
  AppController() : super(const AppState()) {
    _eventSubscription = FreeCursorPlatform.events.listen(_onNativeEvent);
    unawaited(refreshAll());
  }

  late final StreamSubscription<Map<dynamic, dynamic>> _eventSubscription;

  Future<void> refreshAll() async {
    try {
      final init = await FreeCursorPlatform.initialize();
      _applyStatusMap(init);

      final modelStatus = await FreeCursorPlatform.getModelStatus();
      _applyStatusMap(modelStatus);
    } catch (error) {
      state = state.copyWith(lastError: error.toString());
    }
  }

  Future<void> requestOverlayPermission() async {
    await _runAndRefresh(() => FreeCursorPlatform.requestOverlayPermission());
  }

  Future<void> openAccessibilitySettings() async {
    await _runAndRefresh(() => FreeCursorPlatform.openAccessibilitySettings());
  }

  Future<void> startCore() async {
    await _runAndRefresh(() => FreeCursorPlatform.startCore());
  }

  Future<void> stopCore() async {
    await _runAndRefresh(() => FreeCursorPlatform.stopCore());
  }

  Future<void> toggleCursor(bool enabled) async {
    await _runAndRefresh(() => FreeCursorPlatform.toggleCursor(enabled));
  }

  Future<void> setScopeAllApps(bool enabled) async {
    await _runAndRefresh(() => FreeCursorPlatform.setScopeAllApps(enabled));
  }

  Future<void> submitCommand(String command) async {
    if (command.trim().isEmpty) {
      return;
    }

    state = state.copyWith(isProcessing: true, clearError: true);
    try {
      final response = await FreeCursorPlatform.submitCommand(command.trim());
      _applyStatusMap(response);

      final actionMap = response['proposed_action'];
      if (actionMap is Map) {
        final action = ProposedAction.fromMap(actionMap);
        state = state.copyWith(pendingAction: action, isProcessing: false);
      } else {
        state = state.copyWith(
          isProcessing: false,
          lastError: 'No proposed action was returned by native core.',
        );
      }
    } catch (error) {
      state = state.copyWith(isProcessing: false, lastError: error.toString());
    }
  }

  Future<void> confirmAction() async {
    state = state.copyWith(isProcessing: true, clearError: true);
    try {
      final result = await FreeCursorPlatform.confirmAction();
      _applyStatusMap(result);
      final rawExecution = result['action_result'];
      if (rawExecution is Map) {
        state = state.copyWith(
          lastExecution: ExecutionResult.fromMap(rawExecution),
          clearPendingAction: true,
          isProcessing: false,
        );
      } else {
        state = state.copyWith(
          clearPendingAction: true,
          isProcessing: false,
          lastError: 'Action confirmed but no execution result was returned.',
        );
      }
    } catch (error) {
      state = state.copyWith(isProcessing: false, lastError: error.toString());
    }
  }

  Future<void> cancelAction() async {
    await _runAndRefresh(() => FreeCursorPlatform.cancelAction());
    state = state.copyWith(clearPendingAction: true);
  }

  Future<void> getSnapshot() async {
    await _runAndRefresh(() => FreeCursorPlatform.getSnapshot());
  }

  Future<void> downloadModel(String url) async {
    if (url.trim().isEmpty) {
      state = state.copyWith(lastError: 'Model URL is required.');
      return;
    }
    await _runAndRefresh(() => FreeCursorPlatform.downloadModel(url.trim()));
  }

  void _onNativeEvent(Map<dynamic, dynamic> event) {
    final type = event['type']?.toString();
    final payload = event['payload'];

    if (type == null) {
      return;
    }

    if (payload is Map) {
      _applyStatusMap(payload);
    }

    switch (type) {
      case 'proposed_action':
        if (payload is Map) {
          state = state.copyWith(
            pendingAction: ProposedAction.fromMap(payload),
            isProcessing: false,
          );
        }
      case 'action_result':
        if (payload is Map) {
          state = state.copyWith(
            lastExecution: ExecutionResult.fromMap(payload),
            clearPendingAction: true,
            isProcessing: false,
          );
        }
      case 'error':
        final message = payload is Map
            ? payload['message']?.toString() ?? 'Unknown native error.'
            : payload?.toString() ?? 'Unknown native error.';
        state = state.copyWith(lastError: message, isProcessing: false);
      default:
        break;
    }
  }

  Future<void> _runAndRefresh(
    Future<Map<dynamic, dynamic>> Function() operation,
  ) async {
    state = state.copyWith(isProcessing: true, clearError: true);
    try {
      final response = await operation();
      _applyStatusMap(response);
      state = state.copyWith(isProcessing: false);
    } catch (error) {
      state = state.copyWith(isProcessing: false, lastError: error.toString());
    }
  }

  void _applyStatusMap(Map<dynamic, dynamic> map) {
    PermissionStatus? permissionStatus;
    CoreStatus? coreStatus;
    ModelStatus? modelStatus;
    bool? cursorEnabled;
    bool? scopeAllApps;
    String? snapshot;

    final permissionRaw = map['permission_status']?.toString();
    if (permissionRaw != null) {
      permissionStatus = permissionStatusFromString(permissionRaw);
    }

    final coreRaw = map['core_status']?.toString();
    if (coreRaw != null) {
      coreStatus = coreStatusFromString(coreRaw);
    }

    final modelRaw = map['model_status']?.toString();
    if (modelRaw != null) {
      modelStatus = modelStatusFromString(modelRaw);
    }

    if (map.containsKey('cursor_enabled')) {
      cursorEnabled = map['cursor_enabled'] == true;
    }

    if (map.containsKey('scope_all_apps')) {
      scopeAllApps = map['scope_all_apps'] == true;
    }

    if (map['snapshot_json'] != null) {
      snapshot = map['snapshot_json'].toString();
    }

    state = state.copyWith(
      permissionStatus: permissionStatus,
      coreStatus: coreStatus,
      modelStatus: modelStatus,
      cursorEnabled: cursorEnabled,
      scopeAllApps: scopeAllApps,
      snapshotJson: snapshot,
    );
  }

  @override
  void dispose() {
    _eventSubscription.cancel();
    super.dispose();
  }
}

# Free Cursor MVP

Flutter + Kotlin MVP for on-device assistive automation using Accessibility Service, a floating cursor overlay, and an AI orchestration contract.

## Implemented Architecture

- Flutter UI + Riverpod state management
- MethodChannel: `com.freecursor.app/native`
- EventChannel: `com.freecursor.app/events`
- Kotlin native core:
  - `FreeCursorAccessibilityService`
  - `OverlayController`
  - `NodeExtractor` + semantic JSON snapshot
  - `CommandOrchestrator` + strict inference parser
  - `ActionExecutor` (`click`, `scroll`, `long_press`, `swipe`, `type`)
  - `ModelForegroundService` + `ModelManager`

## Android Settings

- `applicationId`: `com.freecursor.app`
- `minSdk`: 29
- `targetSdk`: 35
- ABI filters: `arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`

## Permissions & Services

Declared in `AndroidManifest.xml`:

- `SYSTEM_ALERT_WINDOW`
- `FOREGROUND_SERVICE`
- `FOREGROUND_SERVICE_DATA_SYNC`
- `POST_NOTIFICATIONS`
- Accessibility service metadata in `res/xml/free_cursor_accessibility_service.xml`

## Current ONNX Integration Note

The native model manager is implemented and supports model download/caching plus dynamic ONNX session loading by reflection.

If you want strict compile-time ONNX Runtime linkage, add this dependency back when Maven/network is available:

```kotlin
dependencies {
    implementation("com.microsoft.onnxruntime:onnxruntime-android:<version>")
}
```

## Build & Verify

```bash
flutter pub get
flutter analyze
flutter test
cd android && ./gradlew :app:assembleDebug
```

## Model Bundle Format (Android)

The Android runtime expects a model bundle directory containing:

- `model.onnx`
- `tokenizer.json`
- `tokenizer_config.json`
- `special_tokens_map.json`
- `generation_config.json`

You can provide either:

- a direct `.onnx` URL (legacy inference path), or
- a `.zip` bundle URL with the files above (preferred for Qwen ONNX autoregressive path).

## QLoRA + ONNX Training Pipeline

End-to-end scripts are in [`scripts/ml/`](./scripts/ml):

- `package_dataset.sh`: archive + checksum dataset
- `colab_train_qlora.py`: train/evaluate QLoRA (`Qwen/Qwen2.5-0.5B-Instruct`)
- `merge_and_export_onnx.py`: merge LoRA + export ONNX `text-generation-with-past`
- `README.md`: Colab runtime instructions

## MethodChannel API

Implemented methods:

- `initialize`
- `openAccessibilitySettings`
- `requestOverlayPermission`
- `startCore`
- `stopCore`
- `submitCommand`
- `confirmAction`
- `cancelAction`
- `toggleCursor`
- `getSnapshot`
- `downloadModel`
- `getModelStatus`
- `setScopeAllApps`

Event types emitted:

- `permission_changed`
- `core_state_changed`
- `model_state_changed`
- `proposed_action`
- `action_result`
- `error`

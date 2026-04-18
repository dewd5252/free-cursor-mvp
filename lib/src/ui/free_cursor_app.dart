import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../models/native_models.dart';
import '../state/app_controller.dart';

class FreeCursorApp extends StatelessWidget {
  const FreeCursorApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Free Cursor MVP',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: const Color(0xFF0D9488)),
        useMaterial3: true,
      ),
      home: const AppRootScreen(),
    );
  }
}

class AppRootScreen extends ConsumerStatefulWidget {
  const AppRootScreen({super.key});

  @override
  ConsumerState<AppRootScreen> createState() => _AppRootScreenState();
}

class _AppRootScreenState extends ConsumerState<AppRootScreen> {
  int _selectedIndex = 0;
  final TextEditingController _commandController = TextEditingController();
  final TextEditingController _modelUrlController = TextEditingController();

  @override
  void dispose() {
    _commandController.dispose();
    _modelUrlController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final state = ref.watch(appControllerProvider);
    final controller = ref.read(appControllerProvider.notifier);

    final pages = <Widget>[
      _CommandScreen(
        commandController: _commandController,
        state: state,
        onSubmit: () => controller.submitCommand(_commandController.text),
        onConfirm: controller.confirmAction,
        onCancel: controller.cancelAction,
      ),
      _SettingsScreen(
        modelUrlController: _modelUrlController,
        state: state,
        onDownloadModel: () =>
            controller.downloadModel(_modelUrlController.text),
        onRefresh: controller.refreshAll,
        onRequestOverlay: controller.requestOverlayPermission,
        onOpenAccessibility: controller.openAccessibilitySettings,
        onStartCore: controller.startCore,
        onStopCore: controller.stopCore,
        onToggleCursor: controller.toggleCursor,
        onToggleScope: controller.setScopeAllApps,
        onSnapshot: controller.getSnapshot,
      ),
    ];

    return Scaffold(
      appBar: AppBar(
        title: const Text('Free Cursor MVP'),
        actions: [
          IconButton(
            onPressed: controller.refreshAll,
            icon: const Icon(Icons.refresh),
          ),
        ],
      ),
      body: state.permissionsReady
          ? IndexedStack(index: _selectedIndex, children: pages)
          : _OnboardingScreen(
              state: state,
              onRequestOverlay: controller.requestOverlayPermission,
              onOpenAccessibility: controller.openAccessibilitySettings,
              onRefresh: controller.refreshAll,
              onStartCore: controller.startCore,
            ),
      bottomNavigationBar: state.permissionsReady
          ? NavigationBar(
              selectedIndex: _selectedIndex,
              onDestinationSelected: (value) {
                setState(() {
                  _selectedIndex = value;
                });
              },
              destinations: const [
                NavigationDestination(
                  icon: Icon(Icons.smart_toy_outlined),
                  selectedIcon: Icon(Icons.smart_toy),
                  label: 'Command',
                ),
                NavigationDestination(
                  icon: Icon(Icons.settings_outlined),
                  selectedIcon: Icon(Icons.settings),
                  label: 'Settings',
                ),
              ],
            )
          : null,
    );
  }
}

class _OnboardingScreen extends StatelessWidget {
  const _OnboardingScreen({
    required this.state,
    required this.onRequestOverlay,
    required this.onOpenAccessibility,
    required this.onRefresh,
    required this.onStartCore,
  });

  final AppState state;
  final VoidCallback onRequestOverlay;
  final VoidCallback onOpenAccessibility;
  final VoidCallback onRefresh;
  final VoidCallback onStartCore;

  @override
  Widget build(BuildContext context) {
    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        const Text(
          'Onboarding',
          style: TextStyle(fontSize: 28, fontWeight: FontWeight.bold),
        ),
        const SizedBox(height: 12),
        Text(
          'Permission status: ${state.permissionStatus.name}',
          style: Theme.of(context).textTheme.titleMedium,
        ),
        const SizedBox(height: 12),
        _ActionTile(
          title: 'Grant overlay permission',
          subtitle: 'Required for the floating cursor widget.',
          onPressed: onRequestOverlay,
          icon: Icons.layers,
        ),
        _ActionTile(
          title: 'Open accessibility settings',
          subtitle: 'Enable Free Cursor accessibility service.',
          onPressed: onOpenAccessibility,
          icon: Icons.accessibility_new,
        ),
        _ActionTile(
          title: 'Refresh status',
          subtitle: 'Re-check permission and service states.',
          onPressed: onRefresh,
          icon: Icons.refresh,
        ),
        _ActionTile(
          title: 'Start core services',
          subtitle: 'Starts model and orchestration services.',
          onPressed: onStartCore,
          icon: Icons.play_arrow,
        ),
        if (state.lastError != null) ...[
          const SizedBox(height: 16),
          Text(state.lastError!, style: const TextStyle(color: Colors.red)),
        ],
      ],
    );
  }
}

class _CommandScreen extends StatelessWidget {
  const _CommandScreen({
    required this.commandController,
    required this.state,
    required this.onSubmit,
    required this.onConfirm,
    required this.onCancel,
  });

  final TextEditingController commandController;
  final AppState state;
  final VoidCallback onSubmit;
  final VoidCallback onConfirm;
  final VoidCallback onCancel;

  @override
  Widget build(BuildContext context) {
    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        const Text(
          'Command Console',
          style: TextStyle(fontSize: 24, fontWeight: FontWeight.bold),
        ),
        const SizedBox(height: 12),
        TextField(
          controller: commandController,
          minLines: 1,
          maxLines: 4,
          decoration: const InputDecoration(
            border: OutlineInputBorder(),
            hintText: 'e.g. Open the first message',
            labelText: 'User command',
          ),
        ),
        const SizedBox(height: 12),
        FilledButton.icon(
          onPressed: state.isProcessing ? null : onSubmit,
          icon: state.isProcessing
              ? const SizedBox(
                  width: 14,
                  height: 14,
                  child: CircularProgressIndicator(strokeWidth: 2),
                )
              : const Icon(Icons.send),
          label: const Text('Submit command'),
        ),
        const SizedBox(height: 20),
        _StatusGrid(state: state),
        const SizedBox(height: 20),
        if (state.pendingAction != null)
          Card(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Text(
                    'Proposed action (needs confirmation)',
                    style: TextStyle(fontWeight: FontWeight.bold),
                  ),
                  const SizedBox(height: 8),
                  SelectableText(state.pendingAction!.prettyJson()),
                  const SizedBox(height: 12),
                  Row(
                    children: [
                      Expanded(
                        child: FilledButton.icon(
                          onPressed: state.isProcessing ? null : onConfirm,
                          icon: const Icon(Icons.check),
                          label: const Text('Confirm'),
                        ),
                      ),
                      const SizedBox(width: 8),
                      Expanded(
                        child: OutlinedButton.icon(
                          onPressed: state.isProcessing ? null : onCancel,
                          icon: const Icon(Icons.close),
                          label: const Text('Cancel'),
                        ),
                      ),
                    ],
                  ),
                ],
              ),
            ),
          ),
        if (state.lastExecution != null) ...[
          const SizedBox(height: 16),
          Card(
            child: ListTile(
              title: Text(state.lastExecution!.success ? 'Success' : 'Failed'),
              subtitle: Text(state.lastExecution!.message),
              trailing: Icon(
                state.lastExecution!.success ? Icons.check_circle : Icons.error,
                color: state.lastExecution!.success ? Colors.green : Colors.red,
              ),
            ),
          ),
        ],
        if (state.lastError != null) ...[
          const SizedBox(height: 12),
          Text(state.lastError!, style: const TextStyle(color: Colors.red)),
        ],
      ],
    );
  }
}

class _SettingsScreen extends StatelessWidget {
  const _SettingsScreen({
    required this.modelUrlController,
    required this.state,
    required this.onDownloadModel,
    required this.onRefresh,
    required this.onRequestOverlay,
    required this.onOpenAccessibility,
    required this.onStartCore,
    required this.onStopCore,
    required this.onToggleCursor,
    required this.onToggleScope,
    required this.onSnapshot,
  });

  final TextEditingController modelUrlController;
  final AppState state;
  final VoidCallback onDownloadModel;
  final VoidCallback onRefresh;
  final VoidCallback onRequestOverlay;
  final VoidCallback onOpenAccessibility;
  final VoidCallback onStartCore;
  final VoidCallback onStopCore;
  final ValueChanged<bool> onToggleCursor;
  final ValueChanged<bool> onToggleScope;
  final VoidCallback onSnapshot;

  @override
  Widget build(BuildContext context) {
    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        const Text(
          'Settings',
          style: TextStyle(fontSize: 24, fontWeight: FontWeight.bold),
        ),
        const SizedBox(height: 12),
        _StatusGrid(state: state),
        const SizedBox(height: 16),
        SwitchListTile(
          value: state.cursorEnabled,
          onChanged: onToggleCursor,
          title: const Text('Floating cursor enabled'),
        ),
        SwitchListTile(
          value: state.scopeAllApps,
          onChanged: onToggleScope,
          title: const Text('Scope: all apps'),
          subtitle: const Text('MVP is configured for all applications.'),
        ),
        const SizedBox(height: 8),
        TextField(
          controller: modelUrlController,
          decoration: const InputDecoration(
            border: OutlineInputBorder(),
            labelText: 'Model URL',
            hintText: 'https://example.com/free_cursor_onnx_bundle.zip',
          ),
        ),
        const SizedBox(height: 8),
        FilledButton.tonalIcon(
          onPressed: state.isProcessing ? null : onDownloadModel,
          icon: const Icon(Icons.download),
          label: const Text('Download model'),
        ),
        const SizedBox(height: 16),
        Wrap(
          spacing: 8,
          runSpacing: 8,
          children: [
            OutlinedButton.icon(
              onPressed: onRequestOverlay,
              icon: const Icon(Icons.layers),
              label: const Text('Overlay Permission'),
            ),
            OutlinedButton.icon(
              onPressed: onOpenAccessibility,
              icon: const Icon(Icons.accessibility_new),
              label: const Text('Accessibility'),
            ),
            OutlinedButton.icon(
              onPressed: onStartCore,
              icon: const Icon(Icons.play_arrow),
              label: const Text('Start Core'),
            ),
            OutlinedButton.icon(
              onPressed: onStopCore,
              icon: const Icon(Icons.stop),
              label: const Text('Stop Core'),
            ),
            OutlinedButton.icon(
              onPressed: onSnapshot,
              icon: const Icon(Icons.document_scanner),
              label: const Text('Snapshot'),
            ),
            OutlinedButton.icon(
              onPressed: onRefresh,
              icon: const Icon(Icons.refresh),
              label: const Text('Refresh'),
            ),
          ],
        ),
        if (state.snapshotJson != null) ...[
          const SizedBox(height: 16),
          Card(
            child: Padding(
              padding: const EdgeInsets.all(12),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Text(
                    'Latest screen snapshot',
                    style: TextStyle(fontWeight: FontWeight.bold),
                  ),
                  const SizedBox(height: 8),
                  SelectableText(state.snapshotJson!),
                ],
              ),
            ),
          ),
        ],
      ],
    );
  }
}

class _StatusGrid extends StatelessWidget {
  const _StatusGrid({required this.state});

  final AppState state;

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        Expanded(
          child: _StatusTile(
            label: 'Permission',
            value: state.permissionStatus.name,
            color: state.permissionsReady ? Colors.green : Colors.orange,
          ),
        ),
        const SizedBox(width: 8),
        Expanded(
          child: _StatusTile(
            label: 'Core',
            value: state.coreStatus.name,
            color: state.coreStatus == CoreStatus.running
                ? Colors.green
                : Colors.blueGrey,
          ),
        ),
        const SizedBox(width: 8),
        Expanded(
          child: _StatusTile(
            label: 'Model',
            value: state.modelStatus.name,
            color: state.modelStatus == ModelStatus.ready
                ? Colors.green
                : Colors.deepOrange,
          ),
        ),
      ],
    );
  }
}

class _StatusTile extends StatelessWidget {
  const _StatusTile({
    required this.label,
    required this.value,
    required this.color,
  });

  final String label;
  final String value;
  final Color color;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(10),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.12),
        borderRadius: BorderRadius.circular(10),
        border: Border.all(color: color.withValues(alpha: 0.5)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(label, style: Theme.of(context).textTheme.bodySmall),
          const SizedBox(height: 4),
          Text(value, style: const TextStyle(fontWeight: FontWeight.bold)),
        ],
      ),
    );
  }
}

class _ActionTile extends StatelessWidget {
  const _ActionTile({
    required this.title,
    required this.subtitle,
    required this.onPressed,
    required this.icon,
  });

  final String title;
  final String subtitle;
  final VoidCallback onPressed;
  final IconData icon;

  @override
  Widget build(BuildContext context) {
    return Card(
      child: ListTile(
        leading: Icon(icon),
        title: Text(title),
        subtitle: Text(subtitle),
        trailing: const Icon(Icons.chevron_right),
        onTap: onPressed,
      ),
    );
  }
}

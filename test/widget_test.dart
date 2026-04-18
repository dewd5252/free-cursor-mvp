import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'package:free_cursor_app/src/ui/free_cursor_app.dart';

void main() {
  testWidgets('app boots with title', (WidgetTester tester) async {
    await tester.pumpWidget(const ProviderScope(child: FreeCursorApp()));

    expect(find.text('Free Cursor MVP'), findsOneWidget);
  });
}

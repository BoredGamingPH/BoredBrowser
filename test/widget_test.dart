import 'package:boredbrowser/main.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  testWidgets('BoredBrowser renders manual floating browser screen', (
    tester,
  ) async {
    SharedPreferences.setMockInitialValues({});
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
          const MethodChannel('boredbrowser/overlay_methods'),
          (call) async => switch (call.method) {
            'hasOverlayPermission' => true,
            'showFloatingBrowser' => true,
            _ => null,
          },
        );

    await tester.pumpWidget(const BoredBrowserApp());
    await tester.pumpAndSettle();

    expect(find.text('BoredBrowser'), findsOneWidget);
    expect(find.text('Launch floating browser on app start'), findsOneWidget);
    expect(find.text('Open Floating Browser'), findsOneWidget);

    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
          const MethodChannel('boredbrowser/overlay_methods'),
          null,
        );
  });
}

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'core/theme/app_theme.dart';
import 'features/home/home_screen.dart';

void main() {
  runApp(const BoredBrowserApp());
}

class BoredBrowserApp extends StatelessWidget {
  const BoredBrowserApp({super.key});

  @override
  Widget build(BuildContext context) {
    return ProviderScope(
      child: MaterialApp(
        debugShowCheckedModeBanner: false,
        title: 'BoredBrowser',
        theme: AppTheme.dark,
        home: const HomeScreen(),
      ),
    );
  }
}

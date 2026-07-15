import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../../core/theme/app_colors.dart';
import '../overlay/services/overlay_platform_service.dart';

class HomeScreen extends ConsumerStatefulWidget {
  const HomeScreen({super.key});

  @override
  ConsumerState<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends ConsumerState<HomeScreen>
    with WidgetsBindingObserver {
  static const _autoLaunchKey = 'auto_launch_floating_browser';
  static const _showBubbleKey = 'show_bubble_icon';
  static const _kofiUrl = 'https://ko-fi.com/boredgamingph';
  static const _bubbleOpacityKey = 'bubble_opacity';

  bool _busy = true;
  bool _hasOverlayPermission = false;
  bool _autoLaunch = false;
  bool _showBubbleIcon = true;
  double _bubbleOpacity = 1;
  bool _requestedPermission = false;
  bool _autoStartedThisRun = false;
  bool _overlaySettingsCanSync = false;
  String _status = 'Preparing BoredBrowser...';

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    WidgetsBinding.instance.addPostFrameCallback((_) => _loadStartupOption());
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      _refreshPermissionAfterResume();
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: SafeArea(
        child: SingleChildScrollView(
          child: Center(
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 520),
              child: Padding(
                padding: const EdgeInsets.all(24),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    Icon(
                      Icons.picture_in_picture_alt,
                      size: 56,
                      color: _hasOverlayPermission
                          ? AppColors.arcadeBlue
                          : AppColors.warmOrange,
                    ),
                    const SizedBox(height: 18),
                    Text(
                      'BoredBrowser',
                      textAlign: TextAlign.center,
                      style: Theme.of(context).textTheme.displaySmall?.copyWith(
                        color: AppColors.text,
                        fontWeight: FontWeight.w900,
                      ),
                    ),
                    const SizedBox(height: 8),
                    Text(
                      _status,
                      textAlign: TextAlign.center,
                      style: Theme.of(context).textTheme.titleMedium?.copyWith(
                        color: AppColors.secondaryText,
                        height: 1.35,
                      ),
                    ),
                    const SizedBox(height: 24),
                    SwitchListTile.adaptive(
                      contentPadding: EdgeInsets.zero,
                      value: _autoLaunch,
                      onChanged: _busy ? null : _setAutoLaunch,
                      title: const Text('Launch floating browser on app start'),
                      subtitle: const Text(
                        'When enabled, BoredBrowser opens the floating window automatically.',
                      ),
                    ),
                    const SizedBox(height: 12),
                    SwitchListTile.adaptive(
                      contentPadding: EdgeInsets.zero,
                      value: _showBubbleIcon,
                      onChanged: _busy ? null : _setShowBubbleIcon,
                      title: const Text('Show BB bubble icon'),
                      subtitle: const Text(
                        'When off, the bubble becomes almost transparent but still stays where you placed it.',
                      ),
                    ),
                    const SizedBox(height: 8),
                    Text(
                      'Bubble opacity: ${(_bubbleOpacity * 100).round()}%',
                      style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                        color: AppColors.secondaryText,
                      ),
                    ),
                    Slider(
                      value: _bubbleOpacity,
                      min: 0.10,
                      max: 1,
                      divisions: 18,
                      label: '${(_bubbleOpacity * 100).round()}%',
                      onChanged: _busy || !_showBubbleIcon
                          ? null
                          : (value) => setState(() => _bubbleOpacity = value),
                      onChangeEnd: _busy || !_showBubbleIcon
                          ? null
                          : _setBubbleOpacity,
                    ),
                    const SizedBox(height: 12),
                    if (_busy) const LinearProgressIndicator(),
                    if (!_busy) ...[
                      FilledButton.icon(
                        onPressed: _openFloatingBrowser,
                        icon: const Icon(Icons.open_in_new),
                        label: const Text('Open Floating Browser'),
                      ),
                      const SizedBox(height: 10),
                      TextButton.icon(
                        onPressed: _stopFloatingBrowser,
                        icon: const Icon(Icons.stop_circle),
                        label: const Text('Stop Floating Browser'),
                      ),
                      const SizedBox(height: 10),
                      TextButton.icon(
                        onPressed: _openKofi,
                        icon: const Icon(Icons.local_cafe_outlined),
                        label: const Text('Support on Ko-fi'),
                      ),
                    ],
                    const SizedBox(height: 18),
                    Text(
                      'Tip: hold a floating browser toolbar button to hear or see what it does.',
                      textAlign: TextAlign.center,
                      style: Theme.of(context).textTheme.bodySmall?.copyWith(
                        color: AppColors.secondaryText,
                      ),
                    ),
                    const SizedBox(height: 10),
                    Text(
                      _hasOverlayPermission
                          ? 'Use Open Floating Browser to start the BB window. The main app will minimize when the overlay opens.'
                          : 'Overlay permission is required before BoredBrowser can float over other apps.',
                      textAlign: TextAlign.center,
                      style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                        color: AppColors.secondaryText,
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }

  Future<void> _loadStartupOption() async {
    final prefs = await SharedPreferences.getInstance();
    final autoLaunch = prefs.getBool(_autoLaunchKey) ?? false;
    final showBubble = prefs.getBool(_showBubbleKey) ?? true;
    final bubbleOpacity = prefs.getDouble(_bubbleOpacityKey) ?? 1;
    final overlay = ref.read(overlayPlatformServiceProvider);
    final hasPermission = await overlay.hasOverlayPermission();
    final overlayRunning = hasPermission && await overlay.isOverlayRunning();
    if (!mounted) return;

    setState(() {
      _autoLaunch = autoLaunch;
      _showBubbleIcon = showBubble;
      _bubbleOpacity = bubbleOpacity.clamp(0.10, 1).toDouble();
      _hasOverlayPermission = hasPermission;
      _overlaySettingsCanSync = overlayRunning;
      _busy = false;
      _status = overlayRunning
          ? 'Floating browser is running.'
          : hasPermission
          ? 'Ready to open your floating browser.'
          : 'Overlay permission is needed to use the floating browser.';
    });

    if (autoLaunch && hasPermission && !_autoStartedThisRun) {
      await _launchFloatingBrowser(autoStarted: true);
    }
  }

  Future<void> _refreshPermissionAfterResume() async {
    final overlay = ref.read(overlayPlatformServiceProvider);
    final hasPermission = await overlay.hasOverlayPermission();
    final overlayRunning = hasPermission && await overlay.isOverlayRunning();
    if (!mounted) return;

    setState(() {
      _hasOverlayPermission = hasPermission;
      _overlaySettingsCanSync = overlayRunning;
      if (!_busy) {
        _status = overlayRunning
            ? 'Floating browser is running.'
            : hasPermission
            ? 'Ready to open your floating browser.'
            : 'Overlay permission is needed to use the floating browser.';
      }
    });

    if (_requestedPermission &&
        _autoLaunch &&
        hasPermission &&
        !_autoStartedThisRun) {
      await _launchFloatingBrowser(autoStarted: true);
    }
  }

  Future<void> _openKofi() async {
    final opened = await ref
        .read(overlayPlatformServiceProvider)
        .openExternalUrl(_kofiUrl);
    if (!mounted || opened) return;
    setState(() {
      _status = 'Could not open Ko-fi. Please visit ko-fi.com/boredgamingph.';
    });
  }

  Future<void> _setAutoLaunch(bool value) async {
    setState(() {
      _autoLaunch = value;
      _busy = true;
      _status = value
          ? 'Saving auto-launch preference...'
          : 'Turning off auto-launch...';
    });

    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool(_autoLaunchKey, value);
    if (!mounted) return;

    setState(() {
      _busy = false;
      _status = value
          ? 'Auto-launch is on. BoredBrowser will open the floating window next time the app starts.'
          : 'Auto-launch is off. Use the button when you want the floating browser.';
    });
  }

  Future<void> _setShowBubbleIcon(bool value) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool(_showBubbleKey, value);
    if (!mounted) return;
    setState(() {
      _showBubbleIcon = value;
      _status = value
          ? 'The BB bubble will show when the floating browser starts.'
          : 'Bubble is 10% visible. Remember where you placed it so you can tap or drag it later.';
    });
    await _syncOverlaySettingsIfRunning();
  }

  Future<void> _setBubbleOpacity(double value) async {
    final opacity = value.clamp(0.10, 1).toDouble();
    final prefs = await SharedPreferences.getInstance();
    await prefs.setDouble(_bubbleOpacityKey, opacity);
    if (!mounted) return;
    setState(() {
      _bubbleOpacity = opacity;
      _status = 'Bubble opacity saved.';
    });
    await _syncOverlaySettingsIfRunning();
  }

  Future<void> _syncOverlaySettingsIfRunning() async {
    if (!_overlaySettingsCanSync || !_hasOverlayPermission) return;
    await ref
        .read(overlayPlatformServiceProvider)
        .updateOverlaySettings(
          showBubble: _showBubbleIcon,
          bubbleOpacity: _bubbleOpacity,
        );
  }

  Future<void> _openFloatingBrowser() async {
    await _launchFloatingBrowser(autoStarted: false);
  }

  Future<void> _launchFloatingBrowser({required bool autoStarted}) async {
    final overlay = ref.read(overlayPlatformServiceProvider);
    setState(() {
      _busy = true;
      _status = 'Checking overlay permission...';
    });

    final hasPermission = await overlay.hasOverlayPermission();
    if (!mounted) return;
    _hasOverlayPermission = hasPermission;

    if (!hasPermission) {
      setState(() {
        _busy = false;
        _status = 'Android will open the overlay permission screen.';
      });
      if (!_requestedPermission) {
        _requestedPermission = true;
        await overlay.requestOverlayPermission();
      }
      return;
    }

    setState(() => _status = 'Opening floating browser...');
    final started = await overlay.showFloatingBrowser(
      showBubble: _showBubbleIcon,
      bubbleOpacity: _bubbleOpacity,
    );
    if (!mounted) return;
    setState(() {
      _busy = false;
      _autoStartedThisRun = autoStarted || _autoStartedThisRun;
      _overlaySettingsCanSync = started || _overlaySettingsCanSync;
      _status = started
          ? 'Floating browser is running.'
          : 'Could not start floating browser. Try again or reopen the app.';
    });
  }

  Future<void> _stopFloatingBrowser() async {
    setState(() {
      _busy = true;
      _status = 'Stopping floating browser...';
    });
    await ref.read(overlayPlatformServiceProvider).stopOverlay();
    if (!mounted) return;
    setState(() {
      _busy = false;
      _autoStartedThisRun = false;
      _overlaySettingsCanSync = false;
      _status = 'Floating browser stopped.';
    });
  }
}

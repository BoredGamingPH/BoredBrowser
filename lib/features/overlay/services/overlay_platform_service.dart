import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

final overlayPlatformServiceProvider = Provider<OverlayPlatformService>((ref) {
  return const OverlayPlatformService();
});

class OverlayPlatformService {
  const OverlayPlatformService();

  static const MethodChannel _channel = MethodChannel(
    'boredbrowser/overlay_methods',
  );

  Future<bool> hasOverlayPermission() async {
    return await _channel.invokeMethod<bool>('hasOverlayPermission') ?? false;
  }

  Future<void> requestOverlayPermission() {
    return _channel.invokeMethod<void>('requestOverlayPermission');
  }

  Future<bool> isOverlayRunning() async {
    return await _channel.invokeMethod<bool>('isOverlayRunning') ?? false;
  }

  Future<bool> openExternalUrl(String url) async {
    return await _channel.invokeMethod<bool>('openExternalUrl', {'url': url}) ??
        false;
  }

  Future<bool> startOverlay({
    String? url,
    bool showBubble = true,
    double bubbleOpacity = 1,
  }) async {
    return await _channel.invokeMethod<bool>('startOverlay', {
          'url': url,
          'showBubble': showBubble,
          'bubbleOpacity': bubbleOpacity,
        }) ??
        false;
  }

  Future<void> stopOverlay() {
    return _channel.invokeMethod<void>('stopOverlay');
  }

  Future<bool> showFloatingBrowser({
    String? url,
    bool showBubble = true,
    double bubbleOpacity = 1,
  }) async {
    return await _channel.invokeMethod<bool>('showFloatingBrowser', {
          'url': url,
          'showBubble': showBubble,
          'bubbleOpacity': bubbleOpacity,
        }) ??
        false;
  }

  Future<void> hideFloatingBrowser() {
    return _channel.invokeMethod<void>('hideFloatingBrowser');
  }

  Future<bool> updateOverlaySettings({
    required bool showBubble,
    required double bubbleOpacity,
  }) async {
    return await _channel.invokeMethod<bool>('updateOverlaySettings', {
          'showBubble': showBubble,
          'bubbleOpacity': bubbleOpacity,
        }) ??
        false;
  }
}

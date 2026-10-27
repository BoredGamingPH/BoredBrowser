# BoredBrowser

BoredBrowser is a lightweight Android floating browser built with Flutter and native Kotlin overlay code. It opens as a draggable chat-head style bubble so users can keep a small browser available while using other apps.

The project is intentionally local-first and simple. There are no cloud features, accounts, analytics, or remote configuration.

## Current Features

- Floating Android browser window powered by a native WebView
- Draggable bubble icon with remembered position
- Tap bubble to show or hide the browser window
- Drag bubble to the bottom X target, or long-press it, to close the overlay service
- Launcher controls for overlay permission, auto-launch, bubble visibility, and opacity
- Browser toolbar with Home, Back, address/search field, tabs, and a compact three-dot menu
- Up to 4 tabs, restored with their scroll position when the browser reopens
- Three-dot menu actions: Forward, Reload, Find in Page, Add Bookmark, Bookmarks, Ad Blocker, History, Downloads, Donate, and Launcher
- Find in page with match count and previous/next navigation
- Bookmarks saved on the device
- Downloads handed to Android's download manager, saved to the Downloads folder
- Adaptive Android launcher icon and custom floating bubble icon

## Project Structure

```text
lib/
  core/theme/                     Flutter Material theme
  features/home/                  Launcher and settings UI
  features/overlay/services/      Flutter method-channel wrapper

android/app/src/main/kotlin/com/boredgaming/boredbrowser/
  MainActivity.kt                 Flutter <-> Android method channel
  overlay/FloatingGuideService.kt Native floating bubble and WebView overlay
  overlay/TextDrawable.kt         Lightweight text drawable for toolbar buttons

assets/branding/                  Source logo files used to generate Android icons
```

## Requirements

- Flutter 3.44 or newer
- Android Studio / Android SDK
- Android device or emulator that supports overlay permission

## Build

```powershell
flutter pub get
flutter analyze
flutter test
flutter build apk --release --split-per-abi
```

The main release APK for modern Android phones is usually:

```text
build/app/outputs/flutter-apk/app-arm64-v8a-release.apk
```

## Android Permissions

BoredBrowser uses:

- `SYSTEM_ALERT_WINDOW` for the floating browser overlay
- `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_SPECIAL_USE` to keep the overlay alive while visible
- `INTERNET` for browsing
- `POST_NOTIFICATIONS` for newer Android foreground-service notification behavior

The app asks the user to grant overlay permission before starting the floating browser.

## Future Features
- PDF loading if you have pdf guides
- Saving Web Pages
- Creating Collections for Game guides
- No need for RA i think because you can just open the site, but I will add shortcuts to it in the browser options too
  
## Branding

Source logo files live in `assets/branding/`. Android launcher and bubble resources are generated into `android/app/src/main/res/`.

## Ko-Fi
https://ko-fi.com/boredgamingph

## AI Disclaimer
Current usages of AI is the generation of the Icons, UX designing and refinement
and translation of this git Readme for grammar correction and conciseness

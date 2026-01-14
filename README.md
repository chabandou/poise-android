# Poise Android

A high-performance voice isolation and audio routing tool for Android.


https://github.com/user-attachments/assets/8e3fee6d-7403-45eb-a369-58e04c96c704


## Features
- **ML Voice Isolation**: Real-time background noise suppression for clear audio.
- **Advanced Audio Routing**: Designed to work with "Separate App Sound" on Samsung devices.
- **Immersive UI**: Edge-to-edge dark aesthetics with live audio metrics.

> **Note**: The app need casting permissions to access the audio stream and route it properly.

## Installation

Untill I officially publish on the Play Store, you can install the app from the [GitHub releases](https://github.com/chabandou/poise-android/releases).

## Getting Started

### Prerequisites
- Android SDK & JDK 17+
- Android Device (Samsung recommended for specific routing features)

### Build & Install
```bash
# Build the debug APK
./gradlew assembleDebug

# Install on connected device
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

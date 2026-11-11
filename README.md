# CROPCAST — Record only what matters.

CROPCAST is a selective-region screen recording studio for Android. Instead of
capturing the whole screen, you draw the exact rectangle you want — CROPCAST
records only that region, cropped at capture time. No full-screen intermediate
is ever saved.

- **Native Android** — Kotlin + Jetpack Compose, Modular Clean Architecture + MVVM
- **minSdk 26 (Android 8.1)**, targetSdk 36 — runs on older devices, including
  32-bit phones
- **Capture pipeline** — MediaProjection → VirtualDisplay → SurfaceTexture →
  OpenGL ES crop (normalized coordinates) → MediaCodec H.264 → MediaMuxer MP4
- **Audio** — silent, microphone, or internal audio (API 29+)
- **Player** — built-in playback with share, rename and delete
- **Privacy first** — fresh screen-capture consent every session, FLAG_SECURE
  content is never bypassed, recording is always visible (notification +
  on-screen indicator), **no INTERNET permission, no API keys, no accounts**

## Status — v1.0.0 (2026-11-11)

Working debug build: region selection, recording engine, player, library,
mic/internal audio. Device-tested on Pixel 7 / Android 14 (recording and
playback verified). Floating controller and trim editor are in progress.

## Build

```bash
./build.sh :app:testDebugUnitTest :app:assembleDebug
```

Requirements: JDK 17, Android SDK with compileSdk 37, Gradle 9.6.0
(provided via the wrapper). The APK lands at
`app/build/outputs/apk/debug/app-debug.apk`.

Release signing is configured only with owner-provided credentials — never
commit a keystore or passwords.

## License

MIT — see [LICENSE](LICENSE).

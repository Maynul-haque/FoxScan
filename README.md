# FoxScan Lite

A ground-up lightweight document scanner for Android.

Design goals:
- Tiny APK (~1.7 MB), no OpenCV, no ML Kit, no Play Services requirement
- Pure-Kotlin document quad detector (Sobel + hysteresis + component analysis)
- Temporal auto-capture state machine: 90-frame ring buffer, 3/20/40-frame
  stability windows with 0.05/0.025 normalized deviation thresholds,
  1.5 s ABOUT_TO_TRIGGER hold, 1.0 s TRIGGER hold
- Compose UI: live quad overlay, auto/manual capture, 4-corner crop editor,
  magic/gray/B&W filters, PDF export + share

Build: `./gradlew assembleRelease` (AGP 9.1.1, Kotlin 2.2.10, minSdk 24)

Architecture and rebuild guide: `BLUEPRINT.md`

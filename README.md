# Akki Draw

An offline-first Android vector editor starter project built with Kotlin and Jetpack Compose.

## Phase 1 features
- Minimal editor workspace
- Freehand drawing
- Rectangle and ellipse tools
- Pinch zoom and canvas pan
- Undo
- Save and reopen the latest local `.akki` project
- GitHub Actions workflow for a debug APK artifact

## Build
Requires JDK 17, Android SDK 35, and Gradle 8.9.

```bash
gradle assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`

## Important format status
This Phase 1 starter does **not** yet import or export CDR, SVG, or PDF. The internal `.akki` project format is an initial local save format. SVG/PDF support and CDR import integration are planned for later phases; direct CDR writing is not claimed.

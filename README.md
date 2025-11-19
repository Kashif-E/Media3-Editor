# Media3 Editor

A lightweight Android sample demonstrating [Jetpack Media3](https://developer.android.com/jetpack/androidx/releases/media3) as the base for a hands-on video editing assistant. This is not a library just comprehensive wrapper on top of media3 that can be copy pasted to your app for a more developer ergonomic way of using media3

## Features

- **Preset-driven transformations:** Quickly size videos for square (1:1), portrait story (9:16), or widescreen (16:9) outputs while applying normalized crop rectangles and rotation.
- **Fine-grained controls:** Manually specify width/height, rotation degrees, toggle square crop, mute/remove video, or flatten slow-motion content.
- **Encoder tuning:** Choose a quality preset (LOW/MEDIUM/HIGH) or explicitly set bitrate, I-frame interval, operating rate, and priority via `TranscodeOptions`.
- **Progress reporting:** `MediaEditProgress` mirrors `Transformer.ProgressState`, giving live updates in the UI so users know when encoding is waiting, available, or finished.
- **Gallery export:** Transformations save to `cacheDir` and immediately copy into `MediaStore`, so the output shows up in the user’s gallery. On Android 9 and below the app requests `WRITE_EXTERNAL_STORAGE` first.
- **In-app preview:** The updated Compose screen embeds an ExoPlayer-based preview so you can scrub and play the edited clip without leaving the editor.
- **Robust manager:** `MediaEditManager` handles lifecycle, progress polling via `Transformer.ProgressHolder`, fallback events, cancellation, and optional encoder factory overrides.

## Structure

- `MediaEditModels.kt` defines the request/result/option data classes (crop, resize, transcode, progress, fallback, exception).
- `MediaEditManager.kt` orchestrates `Transformer`, encoder settings, effects (crop, presentation, rotate), and progress/fallback callbacks.
- `MainActivity.kt` renders the polished Compose UI: scrollable cards for source/presets/look/encoder, the run button, progress label, gallery save info, and ExoPlayer preview.

## Build & Run

```bash
./gradlew :app:assembleDebug
```

```bash
./gradlew :app:installDebug
```

Then launch the app on a device/emulator, pick a video, apply presets or custom settings, tap **Run edit**, and review the saved URI + preview.

## Additional notes

- The preview uses ExoPlayer `2.19.1`; deprecation warnings can be addressed later but the player functions today.
- `MainActivity` keeps the UI state in Compose (remembering presets, inputs, progress) and surfaces errors via a `SnackbarHost`.


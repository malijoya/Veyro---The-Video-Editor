# Veyro roadmap

Each milestone leaves the app building, tests passing and the app runnable. A
milestone counts as done only when its acceptance checks have actually been
run. Device-dependent checks need a device or emulator.

## M0 — Foundation (done 2026-10-14)
- Audited the template project and updated the toolchain: Kotlin 2.4.21,
  Compose BOM 2026.09.00, Java 17.
- Added `build-logic` convention plugins, type-safe project accessors and
  module structure (`:core:model`, `:core:designsystem`).
- Added the time base `TimeUs` and `FrameRate` (exact rational rates, explicit
  rounding), with unit tests.
- Wrote the architecture document.

## M1 — Timeline core (`:core:model`, `:core:timeline`, JVM only)
- Project, Track (video/audio/overlay/text), Clip, MediaRef, with stable IDs.
  `schemaVersion = 1`.
- Clips have source in/out, timeline start, and speed as an exact rational.
- Commands: insert, append, trim start/end, split, move, delete, ripple delete.
  Validation rejects overlaps and negative durations.
- Undo/redo stack with bounded history.
- `TimelineEvaluator`: the active clips and source time at timeline time t.
- **Acceptance:** exhaustive unit tests, including frame-accurate split and
  trim at 29.97 and 23.976 fps. No UI.

## M2 — Media import (`:media:import`)
- Photo picker (single and multiple, video and images). Persistable grants
  where available.
- Metadata probing with Media3 `MetadataRetriever`/`MediaExtractor`: duration,
  size, rotation, frame rate, codecs, audio presence.
- Clear errors for unsupported or corrupt files and for revoked permissions.
- **Acceptance:** instrumented test on a device with real files.

## M3 — Project persistence (`:data:project`)
- kotlinx-serialization JSON documents, Room index and migration framework.
- Autosave (debounced, atomic write-and-rename) and recovery after process death.
- Project list screen (`:feature:projects`): create, open, rename, delete.

## M4 — Preview playback (`:media:playback`)
- Build the Media3 player input from `TimelineEvaluator`. Play, pause, seek,
  frame step.
- Release and restore across lifecycle events. Handle missing media.

## M5 — Editor screen (`:feature:editor`)
- Preview surface and multi-track timeline with zoom, playhead and
  filmstrip thumbnails.
- Gestures for trim, split, move and delete, connected to M1 commands and undo.

## M6 — Export (`:media:export`)
- Transformer `Composition` built from the same evaluator. Resolution, frame
  rate and bitrate presets.
- Check device encoder capabilities and fall back. Foreground service with real
  progress and cancel. Save to `MediaStore`.
- **Acceptance:** exported file duration and frame count match the timeline.

## M7+ — Creative features
Each item follows the model → evaluator → playback and export pattern:
- Speed ramps.
- Text and stickers with keyframes.
- Transitions.
- Color and LUT filters (OpenGL ES `GlEffect`).
- Audio mixing, volume keyframes and fades.
- Aspect ratio and canvas.
- Picture-in-picture overlays.
- Later: on-device AI features (auto-captions, background removal), behind
  capability checks.

## Release hardening (before first public build)
- Enable R8 with verified keep rules.
- Baseline profiles.
- Backup rules that exclude media caches.
- Crash and ANR review, plus device matrix testing.

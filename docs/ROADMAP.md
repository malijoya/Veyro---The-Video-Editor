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

## M0.5 — App shell and project library (done 2026-10-14)
- Dark design system: color, type, spacing and shape tokens, plus reusable
  components.
- Adaptive navigation (bottom bar or rail) with Navigation 3.
- Home: new project, import from gallery or files, recent projects with
  thumbnails.
- Projects tab with sort, rename and delete.
- Settings: real storage and media-access figures, app permissions, version,
  export marked "Coming soon".
- Project creation and project overview. Brought forward from M2/M3 so
  Recent Projects shows real data:
  - JSON project documents with atomic writes
  - persisted URI grants, released when unused
  - metadata probing and a thumbnail cache
- The editor and export are clearly marked as not available yet.
- **Still open from M2/M3:** Media3-based frame-accurate probing, Room index,
  autosave of edits, and on-device verification.

## M1 — Timeline core (`:core:model`, `:core:timeline`, JVM only) (code complete 2026-10-14, awaiting build)
- **Done:**
  - Clip timeline and source ranges.
  - `TimelineEditor`: trim start/end, split, move/reorder, delete (close or
    keep the gap), close gaps.
  - Magnetic main track, frame-aware `Snapper`, `EditHistory` undo/redo.
  - Schema v3 storage.
  - Editor gestures: trim handles, long-press move, snap guide, toolbar.
- **Tests:**
  - trim boundaries (source, neighbours, one frame, photos)
  - split continuity and NTSC alignment
  - magnetic reorder and gap-fitting moves
  - gaps, durations, snapping, history
  - 500 random edits checked against all invariants
  - schema migration, editor ViewModel
- **Still open:**
  - speed changes
  - auto-scroll while dragging near the edge
  - moving clips between tracks
  - persisting undo history across process death

### M1.1 — Undo/redo and snapping hardening (code complete 2026-10-15, awaiting build)
- **Done:**
  - Undo history stores `TimelinePatch`es (only the changed clips) with the
    command, instead of timeline snapshots. Exact redo, including split IDs.
  - Conflict detection: a step that no longer fits the timeline is refused
    and reported in the editor instead of corrupting it.
  - Memory bounds: 200 steps and 20 000 clip records.
  - Snap distance setting (Fine/Normal/Strong) in project settings, stored
    as an optional v4 field (older files read with the default).
  - Zoom-independent threshold with a half-frame floor, deterministic tie
    breaks, and edge-to-edge snapping when moving clips.
- **Tests:** sequential edits with exact undo/redo per step, undo-all and
  redo-all, a 300-edit random session, redo of split IDs, empty and
  conflicting undo/redo, rejected and no-op edits, patch size and memory
  budget, snapping at six zoom levels, ViewModel undo-all/redo-all and
  conflict message, settings round trip and fallback.

Original scope:
- Track (video/audio/overlay/text) and Clip, with stable IDs, referencing the
  existing `MediaRef`s.
- Project document `schemaVersion = 3`, with tested v1/v2 → v3 migrations
  (v1 and v2 documents already exist on devices).
- Clips have source in/out, timeline start, and speed as an exact rational.
- Commands: insert, append, trim start/end, split, move, delete, ripple delete.
  Validation rejects overlaps and negative durations.
- Undo/redo stack with bounded history.
- `TimelineEvaluator`: the active clips and source time at timeline time t.
- **Acceptance:** exhaustive unit tests, including frame-accurate split and
  trim at 29.97 and 23.976 fps. No UI.

## M2 — Media import (`:media:import`) (code complete 2026-10-14, awaiting device run)
- **Done:**
  - Photo Picker and SAF for video, images and audio.
  - Persisted grants, flagging providers that refuse them.
  - Probing of duration, dimensions, rotation, frame rate, codecs, sample
    rate, channels, bitrate and tags.
  - Clear unsupported, unreadable and missing outcomes.
  - Selection screen with thumbnails, progress and select all/clear.
  - Import into a new or existing project.
  - Missing-media detection on the project screen.
  - Schema v2 with v1 migration.
- **Tests:**
  - JVM: assembler rules, MIME rules, frame-rate snapping, schema migration,
    import ViewModel.
  - Instrumented: real MP4/WAV/PNG/JPEG files generated on the device.
    These still need a device run.
- **Still open:** relinking missing media, and Media3 `MetadataRetriever` if
  extractor data proves insufficient on real devices.

Original scope:
- Photo picker (single and multiple, video and images). Persistable grants
  where available.
- Metadata probing with Media3 `MetadataRetriever`/`MediaExtractor`: duration,
  size, rotation, frame rate, codecs, audio presence.
- Clear errors for unsupported or corrupt files and for revoked permissions.
- **Acceptance:** instrumented test on a device with real files.

## M3 — Project persistence (`:data:project`) (code complete 2026-10-15, awaiting build)
- **Done:**
  - Schema v4: settings, effects, access flag.
  - Safe writes with `.bak` and `.tmp` recovery.
  - Room metadata index with reconciliation.
  - Debounced autosave with flush on stop and close.
  - Project file export and import.
  - Cache-path rejection.
  - Changed-media detection.
  - Project settings dialog.
  - Canvas-shaped preview and a snapping toggle in the editor.
- **Tests:**
  - close-and-reopen round trip (edits, settings, effects, exact NTSC
    clip timing)
  - v1–v3 migrations
  - backup and temp recovery
  - index reconciliation and failure tolerance
  - autosave debounce, flush and retry
  - interchange round trip and rejection
  - source policy
  - Room instrumented test
- **Still open:**
  - relinking missing or changed media
  - persisting undo history
  - backup rules (exclude the index database, keep documents)

Original scope:
- kotlinx-serialization JSON documents, Room index and migration framework.
- Autosave (debounced, atomic write-and-rename) and recovery after process death.
- Project list screen (`:feature:projects`): create, open, rename, delete.

## M4 — Preview playback (`:media:playback`) (single-asset preview code complete 2026-10-14, awaiting device run)
- **Done:**
  - `PreviewController` and `PreviewPlan` (single track, multi-segment
    ready).
  - ExoPlayer engine, plus a preview screen per source asset: play/pause,
    seek bar, ±5 s, time, aspect fit, rotation, loading, error and retry,
    switching assets.
  - Lifecycle-safe player ownership.
- **Tests:** JVM controller/plan/error/ViewModel tests, plus instrumented
  ExoPlayer and Compose tests.
- **Still open:** building plans from the M1 timeline, and frame-accurate
  stepping. Multi-layer compositing is M7.

Original scope:
- Build the Media3 player input from `TimelineEvaluator`. Play, pause, seek,
  frame step.
- Release and restore across lifecycle events. Handle missing media.

## M5 — Editor screen (`:feature:editor`) (timeline UI code complete 2026-10-14, awaiting device run)
- **Done:**
  - Ruler, Video/Audio tracks with labels, filmstrips, a fixed playhead
    synced to playback.
  - Scroll with fling, pinch and button zoom, zoom to fit, tap to select.
  - Compact layout and accessibility actions.
  - Main-track preview, including photos.
- **Tests:** JVM tests for scale/ruler/viewport geometry, preview-plan
  mapping and the editor ViewModel.
- **Still open:**
  - gestures for trim, split and move (they need M1 commands)
  - snapping
  - audio waveforms
  - multi-track preview (needs M7 compositing)

Original scope:
- Preview surface and multi-track timeline with zoom, playhead and
  filmstrip thumbnails.
- Gestures for trim, split, move and delete, connected to M1 commands and undo.

## M6 — Export (`:media:export`, `:feature:export`) (first pipeline code complete 2026-10-15, awaiting build and device run)
- **Done:**
  - `ExportPlanner` (JVM): clip order, exact source ranges, photos, and the
    audio track with gaps, trimmed to the video. Blocks missing media,
    out-of-range clips and empty main tracks. Warns about effects and extra
    tracks.
  - Media3 Transformer 1.11.1 export: H.264/AAC MP4 at the chosen size
    (letterboxed) and frame rate. Audio normalized to 16-bit stereo for
    mixing.
  - Real progress from `getProgress`, cancel (codecs freed, partial file
    deleted), and errors mapped from `ExportException` codes, plus storage
    full and save failures.
  - Output verification (tracks, duration, frame decode) before anything is
    saved or reported as done.
  - Destinations: gallery (MediaStore Movies/Veyro, Android 10+) or the
    system file picker.
  - Export screen with completion (play, share, size, length, codecs) and
    failure (reason, retry). Export button in the editor.
- **Tests:**
  - JVM: planner (order, ranges, gaps, trimming, blockers, warnings, fps
    rounding, size options), pipeline (success only after verification,
    renderer lies, empty files, failed verification, failed or full copy,
    free-space check, cancellation cleanup), export ViewModel.
  - Instrumented (`Media3ExportInstrumentedTest`): real Transformer export of
    generated MP4/PNG/WAV media with clipping, a photo, gaps and mixed
    sample rates and channels. The output is probed with `MediaExtractor` and
    played to the end with ExoPlayer. Also covers portrait size, a missing
    source and cancellation. Needs a device run.
- **Still open:** foreground-service export, bitrate presets, encoder
  capability checks before starting, and preview/export parity for the
  audio track. See "Not supported yet" in ARCHITECTURE.md.

Original scope:
- Transformer `Composition` built from the same evaluator. Resolution, frame
  rate and bitrate presets.
- Check device encoder capabilities and fall back. Foreground service with real
  progress and cancel. Save to `MediaStore`.
- **Acceptance:** exported file duration and frame count match the timeline.

## M6.5 — Audio tracks (code complete 2026-10-15, awaiting build and device run)
- **Done:**
  - Clip volume (0–100%), mute, fade-in and fade-out. Track volume and mute.
    Music and Voiceover tracks, and moving audio between them.
  - Gain keyframes in the model (source-anchored, rendered), ready for a
    keyframe editor.
  - Schema v5, with the audio fields optional on the v4 shape.
  - The editor preview now plays the whole timeline with
    `CompositionPlayer`, from the same plan and composition as export:
    every track mixed, with gains, at their positions.
  - Real waveforms (MediaCodec decode, 20 ms peaks, memory and disk cache)
    drawn on audio clips and under video clips with sound, with the gain
    line.
  - Clipping prevention: peak-based mix headroom before Media3's 16-bit
    mixer.
  - Missing audio streams handled: no controls, or silence if the stream
    vanished.
- **Tests:**
  - JVM: gain envelope (fades, overlap, mute, keyframes); waveform builder
    and cache format; audio commands, split behaviour, and undo/redo of
    sound edits and moves between tracks in both directions; planner sync
    positions, gaps, cuts, mutes, multiple tracks, overlaps and headroom;
    mix headroom (only overlaps, smoothness, early attack); controller
    keeps position on edits; ViewModels (preview reflects edits, mute
    removes a track, waveforms analysed once, export measures audio
    first); schema v5 round trip.
  - Instrumented (`AudioExportInstrumentedTest`): exported audio decoded
    back to PCM, then measured:
    - onsets at timeline positions, trimmed source ranges
    - fade ramps at 25% and 75%
    - 50% volume
    - muted track silent
    - two loud overlapping tracks with no clipped samples
    - silence for a vanished audio stream
  - Instrumented (`CompositionPreviewInstrumentedTest`): the editor preview
    engine plays a mixed timeline to the end.
  - All of these need a build and a device run.
- **Still open:** keyframe editing UI, voiceover recording, volume boost
  with a limiter, ducking.

## M7 — Text and overlays (code complete 2026-10-15, awaiting build and device run)
- **Done:**
  - Model: `Overlay` (text with font, bold, italic, size, color, alignment,
    outline, shadow, background box; or an image sticker) plus placement
    (position, size, rotation, opacity). Overlay lanes on the timeline,
    stacked top first. Schema v6.
  - Editing: add text or sticker, edit, move, resize, rotate, delete,
    duplicate, bring forward, send backward, to front, to back. Trim, split
    and move in time work as for any clip. Lanes left empty disappear.
    Undo covers all of it, including lanes added or removed.
  - Rendering: one painter for preview, export and the editing canvas.
    Media3 `OverlayEffect` at composition level, region-sized, straight
    alpha, redrawn only at layer boundaries.
  - Fonts: nine system families with family fallback, plus system glyph
    fallback for other scripts and emoji.
  - Editor: on-preview drag, pinch, twist and a resize/rotate handle, with
    centre and right-angle snapping; a tabbed text and style panel; a
    sticker sheet (project images or the photo picker); overlay lanes on
    the timeline.
- **Tests:**
  - JVM: model validation, colors, geometry (clockwise rotation, hit
    testing); stacking on add, every layer move, lane pruning, overlay
    trims, moves and splits, and undo/redo of a full sequence; render plan
    order, cuts, skips, missing stickers; schema v6 round trip, lenient
    decoding, v5 compatibility, stickers kept off the timeline; ViewModel
    (text at the playhead, drafts as one undo step, the selected overlay
    left out of the player's plan, sticker import, layer messages).
  - Instrumented (`OverlayRenderingInstrumentedTest`): family fallback,
    glyph fallback for CJK, Arabic, Devanagari and emoji in every font,
    position and size against the measured box, resolution independence,
    rotation, whole-overlay opacity, straight alpha, and redraws only at
    boundaries.
  - Instrumented (`OverlayExportInstrumentedTest`): an exported MP4 decoded
    back: title position and size, title only within its time range, a
    half-transparent sticker blended over the video and its transparent
    half untouched, and CJK/emoji glyphs drawn.
  - The preview engine plays a timeline with text and a sticker to the end.
  - All of these need a build and a device run.
- **Still open:** animation and keyframes for overlays, custom font import,
  text templates, animated stickers, picture-in-picture video overlays.

## M8 — Clip transforms (code complete 2026-10-15, awaiting build and device run)
- **Done:**
  - Model: `ClipTransform` (position, scale, clockwise rotation, anchor,
    crop, crop shape lock, fit or fill, opacity) on video-track clips, in
    canvas and source fractions only. `ClipPlacement` is the shared
    geometry and produces the render matrices. Schema v7.
  - Rendering: per-item crop, placement and opacity effects, one shader
    pass, identical in preview (CompositionPlayer) and export
    (Transformer); rotated and portrait sources are laid out upright from
    the decoded frame size.
  - Live preview: transform-only changes update the running composition in
    place and redraw the paused frame, without reloading.
  - Editor: tap to select the picture; drag, pinch, twist and a corner
    handle, with snapping; crop frame on the preview; Transform panel with
    Fit, Fill, Reset, position, crop shapes and anchor.
- **Tests:**
  - JVM: value validation and coercion; crop shapes, corner drags with a
    locked shape, moves; layout at several resolutions and with proxies;
    clockwise rotation about the anchor; conversions both ways; anchor and
    crop edits that keep the picture still; fit and fill of turned
    pictures; render matrices against the editor geometry. Edits, split,
    undo/redo; plan carries transforms and upright sizes; storage round
    trip, leniency, v6 compatibility; ViewModel drafts rendered in place as
    one undo step, fit, fill, reset; controller in-place path.
  - Instrumented (`ClipTransformExportInstrumentedTest`): exported frames
    measured against `ClipPlacement` at 640x360, 1280x720, 360x640 and
    480x480; a video with 90 degrees of rotation metadata, upright and
    turned; an EXIF-rotated photo cropped with and without the marker, with
    fill; opacity toward black. `CompositionPreviewInstrumentedTest`: a
    transformed timeline plays to the end and transform-only changes apply
    in place while timing changes are refused.
  - All of these need a build and a device run.
- **Still open:** keyframed transforms, flip, background fill other than
  black, picture-in-picture.

## M9 — Keyframes (built and unit-tested 2026-10-15, awaiting device run)
- **Done:**
  - Model: `ClipAnimation` of `KeyframeTrack`s over clip-local time, for
    position, scale, rotation and opacity (pictures and overlays) and effect
    parameters. Linear, hold, ease-in, ease-out, ease-in-out and custom
    cubic Bézier curves, evaluated deterministically. Schema v8.
  - Edits: trims and splits keep keyframes on their moment of the content.
    Keyframe moments can be added, deleted, eased, nudged and cleared, and
    gestures on keyframed clips key the playhead. All of it is undoable.
  - Rendering: per-frame evaluation at each frame's own time in the GL
    stages (preview and export alike) and in the overlay renderer. Keyframes
    and overlays update the running preview in place. The selected overlay
    is drawn by the player while playing.
  - Editor: keyframe bar (previous/next, add/delete, curve, ±1 frame,
    clear) and timeline markers. The canvas and panels show the clip as it
    is at the playhead.
- **Tests:**
  - JVM: easing reference values, symmetry, monotonicity and determinism.
    Interpolation, clip edges, order independence, editing and kept ranges.
    Clip-local time conversion. Transform, overlay and effect evaluation,
    clamping and multi-turn rotation. Edit rules (moments, rotation the
    short way, last keyframe, easing, moving). Splits (fixed and 60 random
    sessions), trims and moves leaving every frame unchanged. Undo and redo.
    Refusals. Plan evaluation equal to the model's at every frame, in any
    order. Overlay visibility. Storage round trip, leniency and v7
    compatibility. ViewModel (keyframes at the playhead, drags rendered in
    place, easing, nudge, delete, clear, reset, overlays, duplicate, live
    overlay hiding while paused).
  - Instrumented (`KeyframeExportInstrumentedTest`): an exported
    keyframed clip, second in the timeline, measured on five frames against
    the model's placement.
  - All of these need a build and a device run.
- **Then (2026-10-15):** keyframed crop (four edge channels, pictures
  only) and volume keyframe editing in the Sound panel (add, level, linear
  or hold, nudge, delete, clear, timeline markers).
- **Verified:** `assembleDebug testDebugUnitTest` builds and every unit test
  passes. The instrumented tests compile but still need a device run.
- **Still open:** keyframed anchor, a curve editor, tapping timeline
  markers, animated text styles, effect rendering.

## M7+ — Creative features
Each item follows the model → evaluator → playback and export pattern:
- Speed ramps.
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

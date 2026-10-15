# Veyro architecture

Veyro is a local-first, CapCut-style video editor for Android. All editing,
preview and export run on the device. This document records the architecture
decisions and conventions every milestone must follow. Update it when a
decision changes.

## Principles

1. **Non-destructive editing.** Source media is never modified. A project is a
   versioned document that *describes* an edit. Preview and export both
   evaluate that document.
2. **One canonical timeline.** Timing, effects, transitions and keyframes are
   defined once in the platform-independent model. Preview and export both use
   that definition. Neither has its own copy of timing logic.
3. **Integer time.** All timeline times are `Long` microseconds (see
   [Timing model](#timing-model)).
4. **UI-independent editing logic.** Edits are pure functions over immutable
   model state. Compose only renders state and sends intents.
5. **Portable core.** `:core:*` modules are pure Kotlin/JVM with no Android
   imports, so they can move to Kotlin Multiplatform for iOS later. We do not
   build for iOS yet.
6. **Real features only.** No mock data, simulated progress or placeholder
   actions. A feature that is not built yet does not appear in the UI.

## Module graph

```
                         :app
          ┌───────────────┼────────────────────────┐
     :feature:*      :core:designsystem      (wiring/DI)
          │                                         │
   ┌──────┴──────┬──────────────┬─────────────┐     │
:data:project  :media:import  :media:playback  :media:export   (Android)
   └──────┬──────┴──────────────┴─────────────┘
          │            :media:render (shared GL effects)
     :core:timeline
          │
     :core:model                                   (pure Kotlin/JVM)
```

| Module | Type | Status | Responsibility |
|---|---|---|---|
| `:app` | Android app | exists | Activity, Navigation 3 back stack, adaptive navigation suite, manual DI (`AppContainer`). No business logic. |
| `:core:model` | JVM | exists | Time base (`TimeUs`, `FrameRate`, `DurationFormat`), `Project`, `MediaRef`, `ProjectName` rules, and the first piece of the timeline: `Timeline`/`Track`/`Clip`, plus `DefaultArrangement`. |
| `:core:designsystem` | Android + Compose | exists | Dark theme tokens (color, type, spacing, shapes), icons, shared components, `MediaThumbnail` and its `ThumbnailProvider` seam. |
| `:core:timeline` | JVM | exists | Editing domain: `EditCommand`, `TimelineEditor` (trim, split, move/reorder, delete, close gaps), `Snapper` (edge, playhead and frame snapping, zoom-independent threshold), `TimelinePatch` and `EditHistory` (patch-based undo/redo). `export.ExportPlanner` turns a timeline into an `ExportPlan` (segments, source ranges, background audio with gaps, settings, warnings and blockers). |
| `:data:project` | Android | exists | `ProjectRepository`. `FileProjectRepository` stores versioned JSON documents through `ProjectDocumentStore` (safe writes with backup). `RoomProjectIndex` holds list metadata. Also `ProjectAutosave`, `ProjectInterchange` (portable project files), `ProjectFileGateway` (Storage Access Framework) and `SourceReferencePolicy`. |
| `:media:import` | Android | exists | `MediaProber` (metadata via `MediaExtractor` and `MediaMetadataRetriever`), `MediaMetadataAssembler` (pure merge rules), `MimeTypes`, `MediaAccess` (persisted grants, availability), `ThumbnailLoader` (video frame, image, audio cover art, LRU). Package is `media.importer` because `import` is a Kotlin keyword. |
| `:feature:library` | Android + Compose | exists | Home and Projects tabs (`LibraryViewModel`). |
| `:feature:project` | Android + Compose | exists | Media import (pickers, probing, selection, create or add to project) and project overview with missing-media detection. |
| `:feature:settings` | Android + Compose | exists | Storage, media access, permissions, about. |
| `:media:playback` | Android + Compose | exists | `PreviewPlan` (one asset, or a whole timeline's render plan), `PreviewController` (lifecycle, timing, errors), `PlaybackEngine` with `ExoPlaybackEngine` (single assets) and `CompositionPlaybackEngine` (the editor's timeline, every track mixed), preview composables. Holds the preview UI widgets because they bind directly to the Media3 `Player`. |
| `:media:export` | Android | exists | `ExportPipeline` (render → verify → publish, the only path to a completed export), `CompositionFactory` (plan → Media3 `Composition`), `Media3VideoRenderer` (Transformer, real progress, cancel), `AndroidOutputVerifier` (MediaExtractor + frame decode), `AndroidExportPublisher` (MediaStore or SAF document). |
| `:feature:export` | Android + Compose | exists | Export screen: size and frame-rate choice, destination, progress, cancel, completion and failure states. |
| `:media:render` | Android | exists | `CompositionFactory`: the one translation of a render plan into a Media3 `Composition`, used by preview (`CompositionPlayer`) and export (Transformer). Per-item gain through Media3 `GainProcessor`. Overlays: `OverlayPainter` (the one drawing routine for text and stickers), `FontResolver` (family and glyph fallback), `StickerImages`, and `TimelineOverlay` (a Media3 `BitmapOverlay`). Clip transforms: `ClipTransformEffects` (crop, placement and opacity stages, read live from a `LiveTransform`). GL effects and transitions will live here too (M7+). |
| `:feature:editor` | Android + Compose | exists | Editor screen: preview, timeline UI (ruler, tracks, filmstrips, playhead, zoom), selection, playhead/player sync. |

Modules are created in the milestone that first gives them real code, never
as empty shells.

### Dependency rules

- `:core:*` (JVM) depends only on other `:core:*` JVM modules and on pure
  Kotlin libraries (stdlib, coroutines-core, kotlinx-serialization). It never
  depends on Android or Media3.
- `:data:*` and `:media:*` depend on `:core:*`. They never depend on
  `:feature:*`, and they don't depend on each other unless that is written
  down here. The exception is `:media:playback` and `:media:export`, which
  share `:media:render`.
- `:feature:*` depends on `:core:*`, `:data:*` and `:media:*`, never on another
  `:feature:*`. Communication between features goes through navigation in
  `:app`. Features expose `XxxRoute(viewModel, callbacks)` composables, and
  `:app` creates the ViewModels and supplies navigation lambdas, so features
  never see navigation keys.
- `:media:import` depends on `:data:project` only to implement its
  `MediaAccessReleaser` interface.
- `:app` may depend on everything and contains only wiring.
- Media3 types stay inside `:media:*`. The model never refers to `MediaItem`,
  `Effect` and the like. `:media:*` translates model to Media3 at the edge.

## Package naming

The root package is `com.editor.veyro`, already used by the published
`applicationId`. Each module owns `com.editor.veyro.<group>.<module>`, and its
Android `namespace` is the same string:

- `:core:model` → `com.editor.veyro.core.model` (subpackages by concept:
  `.time`, `.project`, `.clip`, ...)
- `:core:designsystem` → `com.editor.veyro.core.designsystem` (`.theme`, `.component`)
- `:media:export` → `com.editor.veyro.media.export`
- `:feature:editor` → `com.editor.veyro.feature.editor`

## App shell and UI conventions

- **Navigation:** Navigation 3 (`androidx.navigation3` 1.2.0). There is one
  `@Serializable` back stack rooted at Home, saved across process death.
  ViewModels are scoped to back-stack entries with
  `rememberViewModelStoreNavEntryDecorator`.
- **Adaptive layout:** `NavigationSuiteScaffold` shows a bottom bar on compact
  windows and a navigation rail on wider ones. It's hidden on detail screens.
  Screen content is capped at `Layout.maxContentWidth`, and grids use adaptive
  columns.
- **Theme:** always dark, no dynamic color. Colors come only from
  `MaterialTheme.colorScheme` or `VeyroColors`, and spacing only from
  `Spacing`/`Layout`. Palette contrast ratios are documented in `Color.kt`.
- **Strings:** every user-facing string is a resource in its feature module,
  with plurals where counts appear.
- **Accessibility:** decorative images have no description, and cards merge
  their semantics. Destructive actions are confirmed, card actions are also
  available as accessibility custom actions, section titles are headings,
  touch targets are at least 48 dp, and text sizes use `sp`.
- **Unbuilt features:** they appear only where users would look for them,
  always with an `AvailabilityBadge` ("Coming soon") and a sentence saying what
  is missing. They never use active-looking buttons.
- **Thumbnails:** `MediaThumbnail` asks `LocalThumbnailProvider`, which `:app`
  binds to `ThumbnailLoader`. The design system stays free of media code.
- **DI:** manual (`AppContainer` in `VeyroApplication`). Hilt is deferred
  until KSP compatibility with Kotlin 2.4 is confirmed.

## Media import

- **Pickers:**
  - Photo Picker (`PickMultipleVisualMedia`) for photos and videos. It needs
    no storage permission and falls back to the document picker on old
    devices.
  - Storage Access Framework (`OpenMultipleDocuments`) for files from any
    provider, and for audio, which the Photo Picker doesn't offer.
  - Veyro requests no storage or media permissions.
- **No copying:** media is referenced by its `content://` URI, and read
  access is persisted with `takePersistableUriPermission` right after
  picking. Some providers refuse persistence. Those items are flagged
  "Access may expire after restart". Grants that no saved project
  references are released after import, discard and project deletion.
- **Probing:**
  - Opening the file descriptor first catches missing files and revoked
    grants.
  - The MIME type comes from the provider, falling back to the file
    extension.
  - `MediaExtractor` reports per-track codec, microsecond duration, frame
    rate, sample rate and channels.
  - `MediaMetadataRetriever` reports rotation, frame count, bitrate and tags.
  - `MediaMetadataAssembler` merges the two. These rules are unit-tested.
  - Declared frame rates (or frame count ÷ duration) snap to exact standard
    rates via `FrameRate.fromApproximateFps`.
- **Failures:**
  - `UNSUPPORTED_TYPE`: not video, image or audio.
  - `NOT_ACCESSIBLE`: missing file or revoked access.
  - `UNREADABLE`: corrupt file or no usable tracks.
  - Video containers holding only audio are imported as audio.
- **Selection:** probed files are selected by default. Failed files and
  files already in the target project are shown but can't be selected. The
  selection is stored as a set of deselected URIs, so files that finish
  probing later are selected automatically, and it survives process death.
- **Missing media:** the project screen re-checks every source file's
  availability when the project changes and on every resume, then flags
  missing files. Relinking is planned with the editor.

## Preview playback

- **Structure:**
  - `PreviewController` drives a `PlaybackEngine` (`ExoPlaybackEngine` in
    production, a fake in tests) through a `PreviewPlan`.
  - A plan is segments played back to back on a single track, in
    microseconds. Today it holds one whole asset.
  - The timeline milestones will build plans from clip sequences. Multi-segment
    seeking and positioning are already implemented and tested.
  - **Multi-layer compositing is not implemented.** Overlapping tracks,
    transitions and effects need a compositing renderer (M7).
- **Player lifetime:**
  - The ViewModel owns the controller, so recomposition and configuration
    changes never recreate the player.
  - `BindPreviewLifecycle` attaches on start and detaches (releasing
    decoders) on a real stop, but keeps the player across configuration
    changes.
  - Position and play state are restored on the next attach. `release()`
    runs in `onCleared`.
  - Engine factories capture only the application context.
- **Media changes:** `setPlan` reloads in place on the same player, and an
  identical plan is a no-op.
- **Rendering:** Media3 `ContentFrame` with a SurfaceView, `ContentScale.Fit`
  and a black shutter until the first frame. Decoders apply rotation
  metadata, so `VideoSize` is already upright and portrait clips keep their
  aspect ratio.
- **Time:** position is polled every 50 ms only while playing, and updated on
  every player event otherwise. Seek-bar drags seek once, on release.
- **Errors:** Media3 error codes map to `PreviewError` categories, each with
  its own message and a retry.

## Project persistence

- **Source of truth:** one versioned JSON document per project
  (`files/projects/<id>/project.json`). It holds:
  - settings: canvas size, frame rate, edge snapping, new-photo length
  - media references: URI plus a fingerprint (name, size, duration), the
    probed metadata, and whether access was persisted
  - tracks in order, with clips (timeline start, source in-point, duration,
    effects)

  All times are integer microseconds, and frame rates are exact fractions.
- **Index:** Room database `project-index.db` with tables `projects` and
  `project_media`. It serves project lists and "which files are still
  used" without opening documents.
  - It is derived data: reconciled with the folders on disk when the
    repository first starts (missing rows added, stale rows removed).
  - Its schema is exported to `data/project/schemas`.
  - A failed index migration falls back to a rebuild.
  - Index write failures never fail a save.
- **Safe writes (`ProjectDocumentStore`):**
  1. Write `.tmp` and `fsync` it.
  2. Rename the current document to `.bak`.
  3. Rename `.tmp` to `project.json`.

  Reads fall back to a complete `.tmp`, then `.bak`. Recovery is logged. A
  document from a newer app version is never replaced by an older backup.
- **Migrations:** v1 → v2 → v3 → v4 run on read, and documents are rewritten
  in the current schema on their next save. Every version has a verbatim
  fixture test.
- **Autosave (`ProjectAutosave`):**
  - Edits show immediately and are written 600 ms after the last edit, as
    one write per burst.
  - The editor flushes pending edits on `ON_STOP` and when it closes. The
    write runs in an app-wide scope, so it finishes after the screen is gone.
  - The editor's top bar shows Saved, Saving… or Not saved. Failed writes
    are retried by the next save.
- **Interchange:** `*.veyro.json` is `{format, formatVersion, project:
  <stored document>}`.
  - Export is in the project screen's menu, and import is on the Projects
    tab.
  - Imported projects get a new ID and go through the normal migrations.
  - On another device their media usually shows as missing, rather than the
    import failing.
- **Source references:** media is referenced where the user keeps it, never
  copied.
  - `SourceReferencePolicy` rejects `file:` paths in the app's cache, code
    cache, external cache, `/tmp` and `/data/local/tmp`. This is checked both
    at import and in the repository.
  - Providers that refuse persisted access are recorded per media item, so a
    later "missing" can be explained as expired access.
- **Media health:** when a project is opened and on every resume, each
  source file is checked. It is missing if it can't be opened, and changed
  if its size differs from import time. Both are flagged on the project
  screen. Relinking comes later.
- **Effects:** clips carry a portable effect list (`type` + numeric
  `params`), and edits keep it. **No effect is rendered or editable yet**
  (M7).

## Timeline editing

- **Clip ranges:** a clip has a timeline range `[startUs, endUs)` and a source
  range `[sourceInUs, sourceOutUs)` of equal length. Trimming moves an edge
  and the matching source point together. Photos have no source time.
- **Engine:** `TimelineEditor` (pure Kotlin, in `:core:timeline`) applies
  commands and always returns a valid timeline. Requests past a limit are
  clamped to it, and only impossible edits are rejected (unknown clip,
  split outside the clip or within a frame of its edge, nothing to change).
  The limits:
  - minimum clip length of one frame
  - source bounds for video and audio
  - no overlaps
  - nothing before 0
- **Gaps:**
  - The main video track is magnetic. It stays packed from 0, so a move
    reorders (by the clip's midpoint), and trims and deletes close the gap.
    This is also what keeps preview playback continuous.
  - Other tracks keep gaps. A move lands on the nearest free position that
    fits, delete optionally closes the gap, and `CloseGaps` packs the track.
- **Split:** the parts are adjacent on the timeline (`left.end ==
  right.start`) and contiguous in the source (`left.sourceOut ==
  right.sourceIn`), so playback across the cut is identical.
- **Snapping (`Snapper`):**
  - Drags snap to other clips' edges, the playhead or 0 when within the
    project's snap distance. Otherwise they round to the nearest frame of
    the timeline's frame rate. Splits snap the playhead to a frame.
  - The distance is set on screen (`ProjectSettings.snapDistanceDp`: 6, 12
    or 20 dp, default 12) and converted to time at the current zoom with
    `Snapper.thresholdUs`. A drag ending N pixels from a target therefore
    snaps the same way at every zoom level.
  - The threshold never drops below half a frame, so an off-grid target
    stays reachable when zoomed far in.
  - Moving a clip on a free track snaps whichever edge is closer to a
    target. Ties go to the start, and between targets to the earlier one.
- **Undo/redo (`EditHistory`, `TimelinePatch`):**
  - Each step is a `RecordedEdit`: the command plus a `TimelinePatch`
    holding only the clips it changed (old and new values per track).
    Clips point to media by ID and share their effect lists, so history
    never copies media or whole timelines.
  - Undo applies the patch's inverse; redo re-applies the patch. Neither
    re-runs the engine, so redo restores the exact clip IDs a split
    created.
  - A patch first checks that every clip it removes is present and
    unchanged, that added IDs are free, that nothing overlaps, and that
    the main track stays packed. If the timeline changed outside the
    history, the step is refused (`HistoryStep.Conflict`), the timeline is
    untouched, and the stale steps in that direction are dropped.
  - Bounded to 200 steps and 20 000 clip records (about 1–2 MB), dropping
    the oldest first. The newest step is always kept.
- **UI to domain:**
  - Compose only reports drag distances, converted once with
    `TimelineScale`.
  - `EditorViewModel` re-applies the command to the timeline as it was when
    the drag began, and shows the result as a draft. It saves a single
    undoable edit on release.
  - The preview plays the saved timeline, so drags don't reload the player.
  - Undo history lives as long as the editor's ViewModel.
- **Storage:** the edited timeline is saved in the project document
  (schema v3). Unedited projects store none and show the default
  arrangement. Media imported after editing is appended to the end of the
  matching track.

## Timeline UI

- **Arrangement:** the editor shows the stored timeline, or
  `DefaultArrangement.build(project)` for projects not edited yet:
  - videos and photos back to back on the Video track (photos 3 s)
  - audio on the Audio track
- **Editing gestures:**
  - Selected clips show trim handles at their true edges, positioned in the
    placement phase.
  - A long press picks a clip up to move it.
  - A snap guide line shows the snapped edge.
  - Child gestures consume events, and the background scroll detector
    yields to them.
- **Coordinates:** `TimelineScale` is the only time/pixel conversion.
  `TimelineViewportState` adds the scroll position. The playhead is fixed at
  the viewport centre and the timeline moves under it, so the playhead time
  is the scroll position. The ruler, clip layout, filmstrips, hit-testing and
  gestures all use `xForTime`/`timeForX`/`visibleSpan`.
- **Recomposition:** clip composables recompose only when clip data or
  selection change. Scroll, zoom and playback reach them through the
  layout phase (a custom `Layout` reads the viewport) and the draw phase
  (ruler and filmstrips). Off-screen clips are neither measured nor placed,
  and each clip is measured only for its visible span (plus a margin). That
  keeps widths bounded on very long timelines.
- **Filmstrips:**
  - Square tiles are drawn only across the visible span. Missing frames are
    requested from the draw pass and land in a state-backed LRU
    (`StripThumbnails`, 300 tiles), which triggers a redraw.
  - Frame times are quantised on a grid that coarsens as tiles cover more
    time.
  - `ThumbnailLoader` keeps the last 3 files' `MediaMetadataRetriever`s
    open, so frames don't re-open the container.
- **Gestures:** one detector handles everything:
  - drag scrolls, then flings with spline decay
  - pinch zooms around the playhead
  - a tap selects the clip under the finger, and empty space clears the
    selection
  Zoom buttons and accessibility actions offer the same controls without
  gestures.
- **Sync:** while nobody is touching the timeline, the player position moves
  the playhead. Scrubbing pauses playback and seeks at most every 80 ms,
  with a final exact seek on release.
- **Preview:** the editor previews one track, the main video track (or the
  audio track for audio-only projects). Photos are timed by the player (via
  Media3 image duration) and drawn by the UI over the surface. **Other
  tracks are not mixed in. There is no multi-layer compositing.**
- **Compact layouts:** windows shorter than 560 dp get slimmer tracks and a
  one-line selection bar.

## Timing model

The model uses the same time unit as Media3 (microseconds), so values reach
ExoPlayer and Transformer without conversion.

- Every timestamp and duration is a `Long` of microseconds, and its name ends
  in `Us` (`startUs`, `durationUs`, `sourceInUs`).
- Floating-point seconds are only for display. They are never stored or used
  in timing math.
- Frame rates are exact reduced fractions (`FrameRate.of(30_000, 1_001)`), never
  a `Double`.
- Frame `k` starts at `ceil(k · 10⁶ · den / num)` µs and is displayed during
  `[frameToUs(k), frameToUs(k + 1))`. `FrameRate.usToFrame` takes an explicit
  `FrameRounding`:
  - `FLOOR`: the frame on screen at that time. Use it for preview and seek.
  - `NEAREST`: use it for snapping edits.
  - `CEIL`: the first frame starting at or after that time.
- Arithmetic uses `Math.*Exact`, so overflow throws instead of corrupting a
  timeline silently.
- Each project has one timeline frame rate. Clips with a different source
  rate keep their source timestamps, and frame snapping happens against the
  project rate.

## Project model (direction for M1/M3)

- **Document:** an immutable tree `Project → Track[] → Clip[]`, plus effects,
  transitions and keyframes added later. It is serialized as JSON with
  `kotlinx-serialization` and carries an explicit `schemaVersion`. Migrations
  are pure functions `vN → vN+1` in `:core:model`, with a test fixture for
  every past version.
- **Edits:** each edit is a command applied to an immutable timeline.
  Undo/redo keeps a small reversible patch per edit (see Undo/redo above),
  so edits never mutate state.
- **Schema history:**
  - v1: name and timestamps, plus video and image media.
  - v2: audio media, video/audio track info and tags.
  - v3: the edited timeline (tracks, clips, frame rate). Every clip must
    reference media in the project, or the document is treated as corrupt.
  - v4: project settings (canvas, frame rate, snapping, photo length),
    clip effects, and media `accessPersisted`. The frame rate moved from the
    timeline into settings.

  Old versions are decoded and mapped forward in `ProjectDocumentCodec`, and
  documents are always saved in the current version. Each version has a
  verbatim fixture test.
- **Storage:** the JSON document in app-private storage is the source of
  truth. Room holds an index (project list, title, duration, thumbnail path,
  last-opened) and the media library. If the index is lost, it can be rebuilt
  from the documents.
- **Media references:** clips refer to a `MediaRef` holding a content URI,
  probed metadata and a fingerprint, so a lost or changed file is detected
  instead of being played wrongly.

## Rendering pipeline (direction for M4/M6/M7)

```
Project (core:model) ──► TimelineEvaluator (core:timeline)
                               │  render plan: segments, effects and keyframes, in µs
              ┌────────────────┴────────────────┐
     media:playback                       media:export
  Media3 player (preview)          Media3 Transformer Composition
              └──── media:render (GlEffect / GLSL shaders) ────┘
```

- Preview and export build their Media3 objects from the same render plan, and
  custom effects are the same `GlEffect` instances in both. That way exported
  frames match preview frames.
- Media3 `Composition`, `EditedMediaItemSequence`, `CompositionPlayer` and the
  `GlEffect` APIs include `@UnstableApi` surfaces. Use is allowed but limited
  to `:media:*`, and every Media3 upgrade is checked against the release notes.
- FFmpeg is not planned. It is added only if a required format or feature
  cannot be done with MediaCodec/Media3, and only after a license review
  (LGPL-only build, dynamic linking).

## Audio

Checked against the Media3 1.11.1 sources before implementation:
`CompositionPlayer`, `GainProcessor`, `AudioGraphInput`,
`AudioGraphInputAudioSink` and `AudioMixingUtil`.

- **Model (`core:model/audio`):**
  - `ClipAudio` on every clip: volume 0..1, mute, fade-in, fade-out, and
    gain keyframes. Keyframes are stored, carried through edits and
    rendered, but not editable in the UI yet.
  - `TrackAudio` on every track: volume 0..1 and mute.
  - Audio tracks have an `AudioRole` (MUSIC, VOICEOVER).
    `DefaultArrangement.withStandardTracks` gives older timelines both
    tracks without moving clips.
- **Gain (`GainEnvelope`):** one definition used by preview, export and the
  waveform drawing:
  - `volume × trackVolume × linear fades × keyframes`.
  - Fades that overlap shrink in proportion, so they always fit.
  - There is no boost above 100%, so one clip can't push itself into
    clipping.
- **Keyframes in source time:** a keyframe is pinned to a moment in the
  media file, not to the clip's edges. Trims don't move it, and after a
  split each part keeps the keyframes inside its own range. On split, the
  left part keeps the fade-in and the right part keeps the fade-out.
- **Edits:** `SetClipAudio`, `SetTrackAudio` and `MoveToTrack` (Music ↔
  Voiceover) are ordinary commands, so undo/redo works for them.
  `TimelinePatch` stores track setting changes too. When a patch is
  applied, every removal happens before any addition, so a clip moving
  between tracks is never on both at once.
- **Sync:** every audio track becomes its own `EditedMediaItemSequence`,
  with gaps for silence. All sequences start at time 0, so each sound plays
  at its timeline position. The video's own sound plays in the main
  sequence, in step with its frames.
- **Gain in Media3:**
  - Each item gets a `GainProcessor` fed by its `SegmentGain`.
  - Media3 positions per-item audio processors from the clipped item's
    start. Transformer passes offset 0 at the item start, and
    CompositionPlayer passes `presentationTime − item start`, including after
    seeks. So a fade lands in the same place in both.
  - Items at unity gain skip the processor and stay bit-exact.
- **Clipping:** Media3 mixes 16-bit PCM and saturates the sum, so a limiter
  after the mix would be too late. Instead `MixHeadroom` works before the
  mix:
  - From each file's measured peaks, it sums `gain × peak` per 20 ms window.
  - Where the sum would pass full scale, all sources are turned down
    together to −0.45 dBFS.
  - Gain drops ahead of the overlap, starting 40 ms early (the whole timeline
    is known), and comes back over 200 ms.
  - A single source is never touched.
  - Media that hasn't been analysed counts as full scale, and export
    analyses every file first.
- **Waveforms:**
  - `WaveformLoader` decodes the first audio stream with
    `MediaExtractor`/`MediaCodec` and records the 20 ms peaks, rounded up.
  - Results are cached in memory and in `cacheDir/waveforms`, keyed by URI,
    size and duration.
  - The timeline draws the real peaks scaled by the clip's gain, plus the
    gain line (fades show as ramps).
  - Video clips with sound get a waveform band under their filmstrip.
  - Without a waveform, nothing is made up: only the gain line is drawn.
- **Preview:** the editor plays the same render plan with `CompositionPlayer`
  (`CompositionPlaybackEngine`), so volume, mute, fades, mixing and photos
  match export.
  - An edit reloads the composition at the same position.
  - The preview renders at most 720 px on the short side.
- **Missing audio streams:**
  - A video without an audio stream contributes no audio, and its sound
    controls are replaced by a note.
  - If a file loses its stream after import, Media3 generates silence for it
    (the sequence declares an audio track), and export still completes.

## Text and overlays (M7)

```
Clip.overlay (core:model) ─► ExportPlanner ─► ExportPlan.overlays (OverlayLayer, bottom first)
                                                 │
            CompositionFactory: Presentation ─► OverlayEffect(TimelineOverlay)   (preview and export)
                                                 │
                         TimelineOverlay ─► OverlayPainter ◄─ OverlayCanvas (editor, the overlay being edited)
```

- **Model (`core:model/overlay`).** An `Overlay` is content (`Text` with a
  `TextStyle`, or `Sticker`) plus an `OverlayTransform` (centre as fractions
  of the canvas, scale, clockwise rotation, opacity). Font size is a
  fraction of the canvas's shorter side; outline, shadow and background are
  fractions of the font size. So a project looks the same at every output
  size, and the 720p preview matches a 4K export. Colors are `Argb`, stored
  as `#AARRGGBB`; fonts are portable keys (`OverlayFont`).
- **Timeline.** Overlays are clips on `TrackKind.OVERLAY` lanes, so trim,
  split, move, delete, snapping and undo work on them unchanged. Text clips
  have no media (`Clip.mediaId` is null); a sticker's media is a project
  image. Lanes are listed above the main track and the first is drawn on
  top; overlays on one lane never overlap in time, so the lane order is the
  whole stacking order.
- **Edits.** `AddOverlay` puts a new overlay above everything it overlaps,
  reusing a lane with room or inserting one. `ArrangeLayer` (forward,
  backward, to front, to back) considers only overlays that overlap in
  time. `SetOverlay` changes content and placement (text stays text). Lanes
  left empty are removed. `TimelinePatch` now records track-list changes
  (`TrackLayout` with clip-less track shells), so adding, removing and
  restacking lanes undo exactly.
- **One painter.** `OverlayPainter` draws an overlay on an Android `Canvas`
  in canvas pixels: background box, shadow, outline, fill, then opacity for
  the whole overlay through one layer. Text uses `StaticLayout` with linear
  advances, so line breaks don't depend on resolution. Lines wrap at 90% of
  the canvas width before scaling.
- **Preview and export.** `CompositionFactory` adds one composition-wide
  `OverlayEffect` after the `Presentation`, holding a `TimelineOverlay`.
  Checked in the Media3 1.11.1 sources: composition effects see composition
  time in both Transformer (each item's frames are offset by the length of
  the items before it) and CompositionPlayer (buffer times adjusted to
  composition time). So a layer shows on exactly the frames in its range.
  For each set of active layers it draws only the region they touch and
  places it pixel for pixel (scale 1, anchored at the region's centre). The
  set changes only at layer boundaries, so the uploaded texture is reused in
  between.
- **Transparency.** Android draws premultiplied alpha; Media3's overlay
  shader blends straight alpha (`overlay.rgb * a + video.rgb * (1 - a)`).
  The region is converted to straight alpha before upload, so translucent
  text, soft shadows and PNG sticker edges blend correctly. Stickers keep
  their alpha channel (decoded as ARGB_8888, turned upright by their EXIF
  rotation).
- **Fonts.** Nine families every Android device has shipped since Android 5
  (Roboto variants, Noto Serif, Droid Sans Mono, Dancing Script, Coming Soon,
  Carrois Gothic SC). `FontResolver` detects a missing family (Android
  silently returns the default typeface for unknown names) and uses the
  font's generic fallback (sans, serif or monospace) instead. Characters a
  font lacks (emoji, CJK, Arabic, Devanagari) come from the system fallback
  fonts, per character, in preview and export alike.
- **Editing on screen.** The player renders every overlay except the
  selected one. `OverlayCanvas` draws that one with the same painter, scaled
  from canvas pixels to the screen, plus its frame and handle. A drag,
  pinch or twist updates it on the next frame without reloading the player.
  Changes in progress are drafts in the ViewModel and are saved as one undo
  step when the gesture ends (or the slider is released). Moves snap to the
  canvas's centre lines and rotation to right angles.
- **Panel.** Tabs for Text, Font (family, bold, italic, alignment, size),
  Color, Outline, Shadow, Background, Adjust (opacity, size, rotation,
  centre, straighten) and Layer (forward, backward, front, back, duplicate,
  delete). Everything the gestures do can also be done there, which keeps it
  accessible.
- **Stickers.** Picked from the project's images or from the photo picker.
  A picked image joins the project's media with lasting read access
  (`addMedia(arrange = false)`), but not the main track. An unedited
  project first stores its default arrangement, so the new image can't be
  arranged into it later. A missing sticker image blocks export, like any
  missing file; during rendering, an unreadable one fails the export
  instead of being left out.

## Clip transforms (M8)

```
Clip.transform (core:model) ─► ExportPlanner ─► VideoSegment.transform
                                                   │
          CompositionFactory: per item  Crop ─► Placement ─► Opacity   (one shader pass, preview and export)
                                                   │
                        ClipPlacement (core:model) ◄─ editor handles, gestures, panel
```

- **Coordinate system.** Everything stored is a fraction, never a pixel
  of any screen or output:
  - *Canvas*: the output frame, (0, 0) top-left to (1, 1) bottom-right, y
    down. Position is where the anchor sits, in these units.
  - *Source*: the upright source frame (rotation metadata or EXIF applied),
    same convention. The crop is a rectangle in it.
  - *Picture*: the cropped part of the source, same convention. The anchor
    (pivot) is a point in it.
  - Rotation is clockwise degrees in (-180, 180], about the anchor, done in
    canvas pixels so nothing shears on a non-square canvas.
  - Size: a `FitMode` base (fit inside the canvas, or fill it), times
    `scale`. Both depend only on shapes, so a layout at 1280x720 is exactly
    half of the one at 2560x1440, and a proxy of another resolution lands
    in the same place.
  - Screen coordinates exist only inside the editor's gesture code, which
    divides by the canvas's size on screen at once.
- **One geometry.** `ClipPlacement` lays a transform out on a canvas of a
  given pixel size and produces both what the editor needs (corners, hit
  testing, canvas/picture/source conversions, edits that keep the picture
  still: `withAnchor`, `withCrop`, `fitted`) and the two OpenGL matrices the
  renderer uses (`cropMatrix`, `placementMatrix`). JVM tests check that the
  matrices map every source point exactly where the editor geometry says.
- **Rendering.** Each picture item gets three per-item Media3 effects:
  `CropStage` (maps the crop onto the frame; the shader clips the rest),
  `PlacementStage` (outputs the canvas size, places the picture) and
  `OpacityStage` (an `RgbMatrix` scaling colour, which is blending with the
  black below the main track; encoders drop alpha). Media3 merges
  consecutive matrix and colour effects into one `DefaultShaderProgram`, so
  it is a single draw per frame. Checked in the 1.11.1 sources: the effects
  receive upright frames in both Transformer and CompositionPlayer (the
  decoder applies `KEY_ROTATION` and Media3 swaps the reported size;
  images are decoded with their EXIF orientation), and `configure` passes
  the decoded size, so the layout uses the real frame rather than stored
  metadata.
- **Live preview.** The preview composition reads each segment's transform
  from a `LiveTransform` on every frame. When a new plan differs from the
  loaded one only in transforms, `CompositionPlaybackEngine.updateInPlace`
  writes the new values and, when paused, asks CompositionPlayer to redraw
  the last frame (replayable frame cache, experimental). `PreviewController`
  tries that before reloading. So a drag, pinch, twist or crop is shown by
  the real renderer, at gesture rate, with no reload; any other edit still
  reloads.
- **Editing.** Drafts work like overlays: `previewTransform` (not saved,
  rendered), `commitTransform` (one undo step), `setTransform` (buttons).
  `SetClipTransform` is valid on video-track clips only. Split copies the
  transform; trims and moves carry it.
- **UI.** Tap the picture in the preview to select its clip (a first touch
  only selects, so footage never moves by accident). Then drag to move,
  pinch to scale, twist to rotate, or use the corner handle; moves snap to
  the centre lines, rotation to right angles, scale to the fitted size. The
  Transform panel has Fit, Fill and Reset, a Position tab (scale, rotation,
  quarter turns, opacity, position), a Crop tab (free, original or preset
  shapes; on the preview the whole source is outlined and the crop frame's
  corners and inside are dragged, with the source held still), and an
  Anchor tab (nine presets and sliders; moving the anchor never moves the
  picture).
- **Storage.** Schema v7: an optional `transform` object per clip; absent
  means the default. Decoding is lenient (values clamped, crop edges
  ordered, an unreadable shape lock dropped, transforms on non-video tracks
  ignored).

## Export (M6)

```
Timeline ──► ExportPlanner (core:timeline, JVM) ──► ExportPlan
                                                     │
ExportPipeline (media:export):  render ──► verify ──► publish ──► Completed
                                Media3VideoRenderer  AndroidOutputVerifier  AndroidExportPublisher
```

Checked against the Media3 1.11.1 sources and the Transformer guides
(getting started, supported formats, composition) before implementation.

- **Plan:** the main track in order, each clip with its exact source range,
  and the first audio track with its gaps, padded or cut to the video's
  length. Missing files, clips past their source's end, and an empty main
  track block the export before it starts.
- **Composition:**
  - Sequence 1 is the main track. Video clips use `ClippingConfiguration`
    (µs start and end). Photos get `setImageDurationMs` and an exact
    `setDurationUs`, plus a frame rate.
  - Sequence 1 includes audio only if some clip has sound; Transformer then
    fills photos and silent clips with silence.
  - Sequence 2 is the audio track, using `addGap` for silence.
  - Every audio item is converted to 16-bit stereo PCM. Transformer requires
    all audio in a composition to match in sample format and channel count.
  - Every picture item carries its clip transform as per-item effects
    (see "Clip transforms"), which output exactly the chosen size. A
    composition-wide `Presentation.createForWidthAndHeight(...,
    LAYOUT_SCALE_TO_FIT)` follows as a no-op safety net.
  - Output is H.264 and AAC in MP4. On Android 10+, HDR sources are
    tone-mapped to SDR so mixed HDR/SDR sequences can export.
- **Threads:** a Transformer must be used from one Looper thread, and its
  listener is called there. The renderer runs on the main thread;
  Transformer's work runs on its own threads.
- **Progress:** `Transformer.getProgress` is polled every 250 ms. When the
  state isn't `PROGRESS_STATE_AVAILABLE`, the UI shows an indeterminate bar.
  Verifying and saving show as named stages without percentages. No progress
  is ever estimated.
- **Success:** `Transformer.start` only takes a file path, so output goes to
  `cacheDir/export` first. The file is then checked:
  - `MediaExtractor` must find a video track, and an audio track if the plan
    has sound.
  - The duration must be within max(0.5 s, 3%) of the plan.
  - `MediaMetadataRetriever` must decode a frame.

  Only then is the file copied to its destination, and only then does
  `Completed` appear. Size and duration on the completion screen are read
  from the file.
- **Destinations:**
  - MediaStore `Movies/Veyro`, Android 10+, no permission needed. The entry
    stays `IS_PENDING` until fully written and is deleted if the copy fails.
  - A document created with the system file picker, which works on every
    supported API level.
- **Cancel and cleanup:** cancelling the coroutine calls
  `Transformer.cancel()` (frees codecs) and deletes the partial file. The
  temporary file is deleted on every path, and leftovers from a killed
  process are cleared at startup. Pending autosave edits are flushed before
  planning.

### Not supported yet

| Operation | Behaviour today | Why |
|---|---|---|
| Background export / leaving the screen | Leaving the export screen cancels the export. The screen stays on while exporting. | Needs a foreground service (`dataSync`/`mediaProcessing` type) owning the Transformer. Planned. |
| Exact NTSC frame rates (29.97, 23.976, 59.94) | Written as 30, 24 and 60. | `EditedMediaItem.Builder.setFrameRate` takes an `int`. |
| Raising a video's frame rate | A 24 fps clip stays 24 fps in a 30 fps export. | For video, `setFrameRate` is a maximum: frames are dropped, never duplicated. |
| Clip effects | Saved but not rendered; the export screen warns. | The effects milestone (M7) adds shared `GlEffect`s. |
| Extra video tracks (picture-in-picture) | Ignored, with a warning. | Only the main video track is rendered. Text and stickers are, and every audio track is mixed. |
| Custom fonts (importing a font file) | Not available; nine built-in families. | Fonts would need to be copied into the project and travel with project files. |
| Animated text, overlay keyframes, text templates | Not available. An overlay is static for its whole range. | The model has room for keyframes (as audio gain has); the editor and renderer don't animate yet. |
| Animated stickers (GIF, animated WebP) | First frame only. | `StickerImages` decodes a still. |
| Vertical text, per-character styling, letter and line spacing | Not available. | One style per text overlay. |
| More than 15 SDR overlays at the same moment | Fine: all visible overlays are drawn into one Media3 overlay texture. | Media3's limit is per `TextureOverlay`, and Veyro uses one. |
| HDR footage with overlays in the preview | Tone-mapped to SDR in the preview (as export does). | Media3 blends plain bitmaps onto SDR frames only. |
| Volume above 100% | Not offered. | Avoids clipping a single source; a boost would need a limiter that runs before Media3's 16-bit mixer. |
| Editing gain keyframes | Stored, rendered and carried through edits, but no UI yet. | Planned with the keyframe editor (M7). |
| Recording a voiceover | Not available; import an audio file and move it to the Voiceover track. | Needs microphone capture (and its permission). |
| Ducking, EQ, noise reduction, audio effects | Not available. | Not in the model yet. |
| Audio-only projects | Blocked. | Output must have a video track. |
| Transitions, crossfades | Not available. | No transition model yet; Media3 Compositions don't support crossfading video or audio tracks. |
| Speed changes, keyframes | Not available. | Not in the timeline model yet. |
| Animated transforms (keyframed position, scale, rotation, crop) | Not available. A transform holds for the whole clip. | The effects read a transform per frame already; keyframes need the model and editor. |
| Flip / mirror, perspective, skew | Not available. | Not in `ClipTransform`. |
| Background other than black (colour, blur) behind a moved or shrunk clip | Black. | Needs a background layer below the main track. |
| Non-square pixels (anamorphic sources) | Laid out as if pixels were square. | Media3 reports the pixel aspect ratio but the placement ignores it. |
| Transforms on extra video tracks | Stored and editable through the engine, but those tracks aren't rendered. | Picture-in-picture is not rendered yet. |
| Live transform preview while paused | Redraws the paused frame through CompositionPlayer's replayable frame cache. | That API is experimental in Media3 1.11; if a redraw can't happen the change shows on the next frame or reload. |
| Bitrate and codec choice (HEVC, AV1), HDR output | Not offered. H.264/AAC, encoder's default bitrate, SDR. | Picked for compatibility; Transformer may still fall back on devices that can't encode H.264 at the chosen size, and the completion screen shows the size actually written. |
| More than 6 audio channels | Fails with a processing error. | Media3's default stereo down-mix covers 1–6 input channels. |
| Clips over GIF or other multi-frame images | First frame only. | `DefaultAssetLoaderFactory` behaviour. |
| Changed source files (`MediaHealth.CHANGED`) | Exported as stored. Clips past the new end block the export. | Clip timing can't be re-verified against a replaced file. |
| Headphones unplugged during editor preview | Playback continues. | `CompositionPlayer` has no "becoming noisy" option, unlike ExoPlayer. |
| HDR in the editor preview | Shown as decoded, without tone-mapping, unless the timeline has text or stickers. Export tone-maps to SDR. | CompositionPlayer supports OpenGL tone-mapping but not the MediaCodec mode; it is only switched on when overlays need SDR frames. |

## Platform concerns (owned by the media and data modules)

- **Storage and permissions:** the system photo picker
  (`PickVisualMedia`/`PickMultipleVisualMedia`, which falls back to a backport
  on older devices) needs no storage permission. `takePersistableUriPermission`
  is called for SAF URIs. Exports are written through `MediaStore`
  (Android 10+) or a user-created SAF document.
- **Codecs:** query `MediaCodecList` and the Media3 encoder selection before
  export, and offer a supported fallback resolution and bitrate. Never assume
  4K/HEVC/HDR support.
- **Memory:** thumbnails are decoded at display size and cached with bounded
  LRU. There is at most one active decoder pipeline per preview.
- **Lifecycle:** the player is released in `onStop`. Export runs in the
  export screen's ViewModel and can be cancelled. Moving it to a foreground
  service, so it survives navigation, is still to do.

## Build conventions

- **Convention plugins:** all module setup lives in
  [build-logic/convention](../build-logic/convention). Modules apply:
  - `veyro.jvm.library`: pure Kotlin, explicit API mode, JUnit.
  - `veyro.android.library` / `veyro.android.application`: SDK levels,
    Java 17, test runner.
  - `veyro.android.compose`: Compose compiler, BOM, tooling.
- **SDK levels:** set only in
  [VeyroBuildConfig.kt](../build-logic/convention/src/main/kotlin/com/editor/veyro/buildlogic/VeyroBuildConfig.kt).
  compileSdk/targetSdk 37, minSdk 24. Newer-API features are gated at
  runtime.
- **Kotlin:** AGP 9 compiles Kotlin itself (built-in Kotlin), so Android modules
  do not apply `org.jetbrains.kotlin.android`. The root build declares the Kotlin
  plugins so one Kotlin Gradle plugin version (`libs.versions.kotlin`) is used
  for the whole build, replacing the older version AGP would pull in.
- **Versions:** come only from
  [gradle/libs.versions.toml](../gradle/libs.versions.toml). Compose versions
  come from the BOM only. Modules refer to each other with type-safe accessors
  (`projects.core.model`).

### Dependency policy

A library is added in the milestone that first uses it. Before adding one:
1. Check the latest stable version on Google Maven or Maven Central.
2. Check its release notes for minSdk/compileSdk/Kotlin requirements.
3. Build.

Avoid alpha/beta libraries unless no stable alternative exists, and record
the reason here when one is used.

Versions checked against Google Maven and Maven Central on 2026-10-14:

| Library | In use | Latest stable | Planned milestone |
|---|---|---|---|
| Android Gradle Plugin | 9.4.1 | 9.4.1 | — |
| Gradle | 9.6.0 | — | — |
| Kotlin (and Compose compiler plugin) | 2.4.21 | 2.4.21 | — |
| Compose BOM | 2026.09.00 | 2026.09.00 | — |
| activity-compose | 1.13.0 | 1.13.0 | — |
| lifecycle | 2.11.0 | 2.11.0 | ViewModel in M2 |
| core-ktx | 1.19.1 | 1.19.1 | — |
| kotlinx-coroutines | 1.11.0 | 1.11.0 | — |
| kotlinx-serialization-json | 1.11.0 | 1.11.0 | — |
| navigation3 | 1.2.0 | 1.2.0 | — |
| lifecycle-viewmodel-navigation3 | 2.11.0 | 2.11.0 | — |
| material3-adaptive-navigation-suite | 1.4.0 (BOM) | 1.4.0 | — |
| material-icons-core | 1.7.8 (BOM, frozen) | 1.7.8 | Extra icons are copied into `VeyroIcons` instead of using the large extended artifact. |
| exifinterface | 1.4.2 | 1.4.2 | — |
| Media3 exoplayer, ui-compose | 1.11.1 | 1.11.1 | `ContentFrame` is `@UnstableApi`, so it is used only in `PreviewSurface.kt`. |
| Media3 transformer, effect | — | 1.11.1 | M6/M7 |
| Room (runtime, compiler, testing) | 2.8.5 | 2.8.5 | Project index. `room-runtime` includes coroutines and Flow support, so `room-ktx` isn't needed. |
| KSP | 2.3.12 | 2.3.12 | Versioned independently of Kotlin. 2.3.10+ supports AGP 9 built-in Kotlin and Kotlin 2.4 (per its release notes). Not yet verified by a build here. |

## Testing strategy

- `:core:*` gets exhaustive JVM unit tests: timing math, edit commands, undo,
  migrations. These are the cheapest tests and protect the canonical model.
- `:data:*`: Room/migration tests and document round-trip tests.
- `:media:*`: instrumented tests on a device or emulator with small
  checked-in sample clips, checking the actual decoded frames and durations of
  exported files.
- UI: Compose UI tests for editor interactions once they exist.
- A feature is reported as working only after it has been run on a device
  or emulator. Build-only verification is reported as such.

## Dev environment notes

- `JAVA_HOME` must point to a JDK 17+. Android Studio's bundled JBR works:
  `C:\Program Files\Android\Android Studio\jbr` (JDK 25).
- `gradle.properties` (and `build-logic/gradle.properties`, since included
  builds don't inherit it) sets `kotlin.compiler.execution.strategy=in-process`,
  because on the 12 GB development machine a separate Kotlin daemon could not
  allocate memory. If the Gradle daemon itself still crashes with
  `Native memory allocation failed`, close memory-heavy apps or build one task
  at a time with `--max-workers=1`.

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
| `:core:timeline` | JVM | exists | Editing domain: `EditCommand`, `TimelineEditor` (trim, split, move/reorder, delete, close gaps), `Snapper` (edge, playhead and frame snapping), `EditHistory` (undo/redo). |
| `:data:project` | Android (JVM-only code so far) | exists | `ProjectRepository`. `FileProjectRepository` stores versioned JSON documents with atomic writes. Room index planned for M3. |
| `:media:import` | Android | exists | `MediaProber` (metadata via `MediaExtractor` and `MediaMetadataRetriever`), `MediaMetadataAssembler` (pure merge rules), `MimeTypes`, `MediaAccess` (persisted grants, availability), `ThumbnailLoader` (video frame, image, audio cover art, LRU). Package is `media.importer` because `import` is a Kotlin keyword. |
| `:feature:library` | Android + Compose | exists | Home and Projects tabs (`LibraryViewModel`). |
| `:feature:project` | Android + Compose | exists | Media import (pickers, probing, selection, create or add to project) and project overview with missing-media detection. |
| `:feature:settings` | Android + Compose | exists | Storage, media access, permissions, about. |
| `:media:playback` | Android + Compose | exists | `PreviewPlan` (segments on one track), `PreviewController` (lifecycle, timing, errors), `PlaybackEngine` with `ExoPlaybackEngine`, preview composables (`PreviewSurface`, `PreviewControls`, `BindPreviewLifecycle`). Holds the preview UI widgets because they bind directly to the Media3 `Player`. |
| `:media:export` | Android | planned (M6) | Maps the timeline to a Media3 Transformer `Composition`, runs the export, falls back when codecs are unsupported. |
| `:media:render` | Android | planned (M7+) | OpenGL ES effects and transitions as Media3 `GlEffect`s, shared by playback and export. |
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
- **Snapping (`Snapper`):** within a fixed on-screen distance (12 dp,
  converted with the current scale), drags snap to other clips' edges, the
  playhead or 0. Otherwise they round to the nearest frame of the timeline's
  frame rate (from the first video, or 30 fps). Splits snap the playhead to
  a frame.
- **UI to domain:**
  - Compose only reports drag distances, converted once with
    `TimelineScale`.
  - `EditorViewModel` re-applies the command to the timeline as it was when
    the drag began, and shows the result as a draft. It saves a single
    undoable edit on release.
  - The preview plays the saved timeline, so drags don't reload the player.
  - Undo history (100 steps) lives as long as the editor's ViewModel.
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
- **Edits:** each edit is a command `(Project) -> Project`. Undo/redo keeps
  previous immutable snapshots, so edits never mutate state.
- **Schema history:**
  - v1: name and timestamps, plus video and image media.
  - v2: audio media, video/audio track info and tags.
  - v3: the edited timeline (tracks, clips, frame rate). Every clip must
    reference media in the project, or the document is treated as corrupt.

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

## Platform concerns (owned by the media and data modules)

- **Storage and permissions:** the system photo picker
  (`PickVisualMedia`/`PickMultipleVisualMedia`, which falls back to a backport
  on older devices) needs no storage permission. `takePersistableUriPermission`
  is called for SAF URIs. Exports are written through `MediaStore`.
- **Codecs:** query `MediaCodecList` and the Media3 encoder selection before
  export, and offer a supported fallback resolution and bitrate. Never assume
  4K/HEVC/HDR support.
- **Memory:** thumbnails are decoded at display size and cached with bounded
  LRU. There is at most one active decoder pipeline per preview.
- **Lifecycle:** the player is released in `onStop`. Export runs as a
  foreground service (user-visible progress, survives navigation) and can be
  cancelled.

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
| Room | — | 2.8.5 | M3. Needs KSP: verify that the KSP 2.3.x line works with Kotlin 2.4.21 before adding. |

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

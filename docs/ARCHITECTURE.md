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
| `:app` | Android app | exists | Activity, navigation, dependency wiring. No business logic. |
| `:core:model` | JVM | exists | Project document types, time base (`TimeUs`, `FrameRate`), IDs. |
| `:core:designsystem` | Android + Compose | exists | Theme, colors, typography, shared editor widgets. |
| `:core:timeline` | JVM | planned (M1) | Edit commands, undo/redo, timeline evaluation ("what is active at time t"). |
| `:data:project` | Android | planned (M3) | Project persistence: Room index plus versioned document files, migrations, autosave. |
| `:media:import` | Android | planned (M2) | Photo picker and SAF, persistable URI grants, metadata probing. |
| `:media:playback` | Android | planned (M4) | Maps the timeline to a Media3 player for preview. |
| `:media:export` | Android | planned (M6) | Maps the timeline to a Media3 Transformer `Composition`, runs the export, falls back when codecs are unsupported. |
| `:media:render` | Android | planned (M7+) | OpenGL ES effects and transitions as Media3 `GlEffect`s, shared by playback and export. |
| `:feature:projects` | Android + Compose | planned | Project list, create, open, delete. |
| `:feature:editor` | Android + Compose | planned | Editor screen, timeline UI, ViewModels. |

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
  `:app`.
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
| kotlinx-coroutines | — | 1.11.0 | M1/M2 |
| kotlinx-serialization-json | — | 1.11.0 | M3 |
| Media3 (exoplayer, transformer, effect) | — | 1.11.1 | M4/M6 |
| Room | — | 2.8.5 | M3. Needs KSP: verify that the KSP 2.3.x line works with Kotlin 2.4.21 before adding. |
| navigation-compose | — | 2.10.2 | M5 (also evaluate Navigation 3 when starting it) |

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
- `gradle.properties` sets `kotlin.compiler.execution.strategy=in-process`,
  because on the 12 GB development machine a separate Kotlin daemon could not
  allocate memory. If the Gradle daemon itself still crashes with
  `Native memory allocation failed`, close memory-heavy apps or build one task
  at a time with `--max-workers=1`.

# CROPCAST — Spec (saved from Imdad's prompts, 2026-11-10)

Product: CROPCAST — Selective Screen Recording Studio. Native Android, Kotlin,
Jetpack Compose, minSdk 26 (Android 8.1), targetSdk modern (36). Modular Clean
Architecture + MVVM. Open source (MIT) on Imdad's GitHub. No paid APIs, no
mandatory external services, no subscriptions. Design: minimal Apple-inspired,
premium, dark-first. Brand: CROPCAST, tagline "Record only what matters."

## Core idea
Selective-region recording: user defines a rectangular region BEFORE recording;
pipeline Capture → Crop → Encode → Mux → Save. MediaProjection captures the
full display (it cannot capture an arbitrary rect directly) → frames go through
an OpenGL ES pipeline → crop with normalized coords → MediaCodec H.264 input
surface → MediaMuxer MP4. NEVER save a full-screen intermediate. Bounded
buffers, release frames promptly. ImageReader fallback only if GPU pipeline is
unavailable AND validated.

## Compatibility override (Imdad, 2026-11-10 — part of the spec)
- minSdk 26 (Android 8.1) is MANDATORY. Do NOT make Android 14+ mandatory.
- MediaProjection via APIs available on 8.1; API-level checks for newer features;
  never invoke unavailable APIs on old devices.
- Baseline audio: microphone + silent. Internal playback audio OPTIONAL on
  Android 10+ where supported.
- Compatible foreground service + floating overlay; gracefully disable
  unsupported features instead of crashing. Modern target SDK, minSdk 26.
- Test API 26 compatibility; do not claim it without actual testing.

## Screens (12)
1. Splash — dark, CROPCAST wordmark, red dot, fast startup.
2. Onboarding — 3 pages (precision / control / privacy), Skip/Continue/Get Started.
   Never request permissions just because onboarding started.
3. Home Dashboard — logo, settings, status; big red record button; quick settings
   (quality, fps, audio, region, floating controls); recent recordings; real stats
   only (total recordings, duration, storage) from local data. Never fake analytics.
4. Recording Setup — modes: Custom Region (primary), Fixed Aspect (16:9, 9:16,
   1:1, 4:3), Full Screen, App Window (system picker where supported). Controls:
   width/height, aspect lock, resolution, fps, audio, quality.
5. Region Selection — full-screen: dimmed surround, rect boundary, 4 corner +
   4 edge handles, center drag, live W×H label, aspect lock, reset, confirm,
   cancel. Min dimensions enforced; never leave valid area. Hide selector before
   recording. Never promise overlays are excluded from capture.
6. Floating Controller — compact draggable red circle; states IDLE/RECORDING/
   PAUSED/STOPPING; tap opens panel (pause/resume/stop, elapsed time, dimensions,
   status). Never hide that recording is active; keep foreground notification
   with stop action; never disguise the app.
7. Recording Session — duration, pause/resume, stop, resolution, fps, audio,
   storage. State machine: IDLE → REQUESTING_PERMISSION → INITIALIZING →
   RECORDING → PAUSED → STOPPING → COMPLETED. No contradictory states.
8. Completion — thumbnail, duration, resolution, size, location, preview/edit/
   share/delete. Only after MP4 finalized + verified.
9. Library — grid/list, thumbnails, search, sort, filter, duration/size/
   resolution/date, multi-select, delete/share/rename/export. MediaStore for
   shared media; app-private storage for temp files.
10. Preview — play/pause, seek, duration, fullscreen, volume, details, share,
    delete. Media3. Handle errors + missing files.
11. Editor — trim start/end, crop, aspect ratio, mute, preview, export (Phase 1).
    Advanced if properly done: speed, resolution conversion, quality, thumbnail
    pick. No dead buttons.
12. Settings — Recording / Video / Appearance (dark/light/system, accent,
    haptics) / Storage (destination, info, temp cleanup) / Privacy (explanation,
    permissions, data deletion) / About (version, licenses, GitHub, privacy).

## Audio
Modes: No Audio / Microphone / Internal (10+ where supported, via
AudioPlaybackCaptureConfiguration, with consent + app policies respected).
RECORD_AUDIO only when mic selected; explain why; handle denial/unavailability.
On 8.1 internal capture is NOT available via public API — offer mic/silent.
A/V sync via timestamps; no drift; finalize muxing correctly. Never promise
every app's audio can be captured.

## Encoding
MediaCodec + MediaMuxer, H.264/AVC, MP4. Presets: STANDARD 720p30, HIGH 1080p30,
SMOOTH 1080p60 (where HW supports), CUSTOM within device caps. Discover encoder
caps; never assume. Surface input, EGL rendering, frame timestamps, crop
transform, bitrate config, muxer lifecycle, error handling, final validation.
Never finalize MP4 with unprocessed encoder buffers; pause/stop must not corrupt.

## Architecture (modules)
:app, :core:common, :core:designsystem, :core:database, :core:datastore,
:core:media, :core:permissions, :core:storage, :feature:home,
:feature:onboarding, :feature:region, :feature:recording, :feature:library,
:feature:player, :feature:editor, :feature:settings, :service:recording,
:media:renderer, :media:encoder, :media:audio.
Kotlin, Compose, Material 3, Coroutines/Flow, Room, DataStore, MediaProjection,
MediaCodec, MediaMuxer, OpenGL ES, Media3, WorkManager (deferred cleanup).
DI where justified. Pin stable versions. No experimental libs without reason.

## Data model
RecordingEntity (id, fileUri, title, createdAt, durationMs, width, height,
frameRate, videoCodec, audioMode, fileSizeBytes, thumbnailUri, recordingStatus).
RecordingSettingsEntity (id, selectedMode, regionLeft/Top/Width/Height,
aspectRatio, resolutionPreset, frameRate, bitrate, audioMode). ExportJobEntity
(id, sourceRecordingId, destinationUri, exportStatus, progress, createdAt,
completedAt, errorMessage). Migrations for schema changes. NEVER store raw
MediaProjection consent tokens. Never store unnecessary personal info.

## Design system
Bg #0B0B0D, Surface #151518, Elevated #1D1D22, Text #F5F5F7, Secondary #92929B,
Accent Red #FF453A (recording actions/states only), Success #30D158,
Warning #FFB340, Border #29292F. Clean sans-serif, strong hierarchy, generous
spacing, consistent radii, subtle borders, minimal gradients, smooth transitions,
accessible contrast, large touch targets. Vector/Material Symbols icons, never
emoji as icons. Responsive layouts, accessibility labels, font scaling, respect
reduced-motion.

## Security & privacy (hard rules)
Explicit user initiation always; fresh MediaProjection consent every session
(never reuse tokens, Android 14+ rules); register MediaProjection.Callback,
handle onStop, release VirtualDisplay + surfaces; never bypass FLAG_SECURE;
never disable privacy indicators; never hide recording notifications; never
secretly record; never auto-upload; never transmit screen content; never
collect screen content for analytics; never expose files via insecure paths;
never log frame data; never store mic audio in temp unnecessarily; delete temp
files after export/cleanup. No API keys, no hardcoded passwords, no INTERNET
permission unless a feature needs it (document every permission). No contacts/
location/camera/SMS/phone permissions. Private temp storage, Content URIs,
strict exported components, intent validation, safe DB access.

## Permissions (central PermissionManager)
FOREGROUND_SERVICE, FOREGROUND_SERVICE_MEDIA_PROJECTION,
FOREGROUND_SERVICE_MICROPHONE (only if the mic service needs it),
POST_NOTIFICATIONS (13+), RECORD_AUDIO (only when requested),
SYSTEM_ALERT_WINDOW (optional floating controls). MediaProjection consent is
separate from runtime permissions. Clear denied states; never pressure; never
silently open settings.

## State management
RecordingStateManager, validated transitions. Prevent: concurrent sessions,
multiple VirtualDisplays per token, duplicate stops, invalid pause/resume,
recording after termination, encoder leaks, muxer races. Handle: stop from
notification/floating controls, system termination, process death, rotation,
screen lock, low storage, encoder failure, permission revocation, unsupported
resolution, mic failure. Single reliable cleanup path.

## Performance
Bounded memory, HW encoding where supported, efficient GPU crop, no unbounded
queues, backpressure, nothing blocking on main thread, coroutine cancellation,
minimal DB queries during recording, no needless bitmaps, thumbnails off main
thread. If a config is unsupported, explain + offer a compatible preset. Never
crash on 60fps/unsupported resolution.

## Testing
Unit: crop coords, aspect ratio, rotation transforms, state transitions, file
naming, settings persistence, metadata parsing, export validation.
Instrumentation: DB, permission flows, navigation, recording lifecycle,
storage, overlay fallback, MediaCodec config.
Device: 8.1, 10+, 13+, 14+, modern; densities; portrait/landscape; low
storage; permission denial; screen lock. Verify real recordings: correct crop,
dimensions, orientation, valid MP4, audio where supported, playback, cleanup,
no full-screen intermediate saved. Never claim tests passed unless executed.

## GitHub / release
Repo: cropcast-android. README.md, LICENSE (MIT), CONTRIBUTING.md,
CODE_OF_CONDUCT.md, SECURITY.md, CHANGELOG.md, .gitignore,
.github/workflows/android-ci.yml (checkout, JDK, SDK, unit tests, lint,
assemble debug APK, upload artifacts), ISSUE_TEMPLATE, PR template. README:
intro, screenshots, features, architecture, build instructions, requirements,
permissions, privacy, known limits, testing, contributing, license. MIT with
the OWNER's copyright info — do not invent a company/legal entity/owner.
Never commit signing keys/keystores/passwords/local.properties. Release APK
only with owner-provided signing credentials; never invent secrets.

## Build & delivery
Deliverables: full Android Studio project, all Kotlin sources, Gradle config,
manifest, resources/icons, DB, recording engine, crop engine, audio, library,
preview+editor, unit + instrumentation tests, README, MIT license, CI workflow,
buildable APK, build/install instructions. APK from the actual source. Run:
Gradle build, unit tests, lint, APK assembly, install verification where a
device/emulator is available. Report exact outcome of each step.

## Phases
1. Foundation (project, Gradle, modules, design system, navigation,
   onboarding, dashboard). 2. Region Selection (selector, geometry, presets,
   rotation/density). 3. Recording Engine (consent, foreground service,
   VirtualDisplay, EGL, crop, MediaCodec, muxing, verify real cropped
   recordings). 4. Floating Controller (overlay, pause/resume/stop,
   notification, lifecycle). 5. Audio (mic, internal 10+, sync, permissions).
6. Library + Editor (MediaStore, thumbnails, playback, trim, export).
7. Quality + Security (tests, permissions, cleanup, compatibility, fix
   criticals). 8. Release (APK, install verify, GitHub, docs, release notes).
After each phase: compile, test, review errors, fix regressions, document,
proceed only on acceptance criteria.

## Acceptance criteria (all must hold; never mark complete on appearance)
Launches; onboarding works; dashboard nav works; region choose/resize/
reposition; aspect presets work; consent requested correctly; foreground
service correct; real frames captured; frames cropped BEFORE encoding; output
is only the crop; no full-screen intermediate saved; pause/resume/stop work;
floating controls work where permitted; notification controls work; mic audio
works where supported; internal audio where permitted+supported; MP4s play;
library works; export works; temp cleaned; denial handled; termination
handled; critical tests pass; APK builds; GitHub docs complete. Incomplete
items must be named explicitly.

## Negative prompt (strict)
No fake recorder, no static-only UI, no placeholder buttons, no simulated
recording timer, no mock data in production, no full-screen saved as selective,
no claiming MediaProjection captures arbitrary regions directly, no consent
bypass, no token reuse, no multiple VirtualDisplays per session, no secure
content capture, no FLAG_SECURE bypass, no hidden indicators/notifications,
no secret capture, no Accessibility abuse, no root-only features, no
unnecessary permissions, no uploads, no paid APIs, no analytics, no hardcoded
keys, no exposed private paths, no main-thread blocking, no unbounded buffers,
no resource leaks, no ignoring codec limits, no unsupported-audio claims, no
ignoring lifecycle, no dead buttons, no TODOs in critical paths, no untested
claims, no committed signing secrets, no copyrighted branding/copied assets,
no misleading docs, no screenshots instead of implementation.

# ADR 0001 — Native Android (Kotlin + Media3/ExoPlayer) for LiveWire

- **Status:** Accepted
- **Date:** 2026-09-23
- **Supersedes:** the Flutter implementation at `/local/home/gsill/livewire/` (~3,524 lines of Dart)

## Context

LiveWire is a pure-client Android **TV**/mobile IPTV & media player (no backend).
The hard product requirement is that it be **as quick, light, and lean as
possible** — it must run well on cheap TV sticks/boxes (often 1–1.5 GB RAM,
weak SoCs, shared with the OS).

A working Flutter prototype exists (onboarding, live-TV home with EPG,
media_kit player, EPG guide grid, ESPN sports scoreboard/standings + game→channel
picker, multi-provider management, cross-source search, settings). Measured
release APK (arm64, stripped) ≈ 33 MB, broken down as:

| Component | arm64 size | What it is |
|---|---|---|
| `libmpv.so` | 12.4 MB | mpv/ffmpeg software player + codecs |
| `libflutter.so` | 11.7 MB | Flutter engine (renderer + Dart VM) |
| `libapp.so` | 6.6 MB | our AOT-compiled Dart |
| `libsqlite3.so` | 1.7 MB | bundled SQLite |
| other | ~1 MB | ffi glue, helpers, assets |

Two facts drove the decision:

1. **~24 MB of 33 MB is engines** (mpv + Flutter); only 6.6 MB is our code.
   "Lean" is mostly a question of *which runtime we accept*, not how we write
   feature code.
2. **Runtime cost matters more than APK bytes on a weak TV SoC.** The dominant
   runtime costs are (a) the Flutter engine RAM floor (~40–80 MB idle) and
   (b) mpv's **software** video decoding (CPU/heat + tens-of-MB frame buffers).

## Decision

Rewrite LiveWire as a **native Android app in Kotlin**, using **Jetpack Compose
(with `androidx.tv` / Compose for TV)** for UI and **AndroidX Media3
(ExoPlayer)** as the player, with **hardware video decoding by default**.

**Playback is abstracted behind a `PlaybackEngine` interface from day one.**
The only implementation we ship now is `ExoPlayerEngine`. `MpvEngine` is a
**deferred fallback**: we add it only if real-world streams expose ExoPlayer
compatibility gaps. The interface is built now so that addition is drop-in.

## Rationale

- **Leanest realistic footprint.** Dropping the Flutter engine removes ~12 MB of
  binary and the ~40–80 MB idle RAM floor.
- **Hardware decode is the biggest runtime win.** ExoPlayer/Media3 uses the
  device's hardware decoders by default — far lighter on CPU, heat, and memory
  than mpv's software path. This is *every second of playback*, not a one-time
  cost.
- **This is what the category leaders do.** TiviMate and similar lean IPTV apps
  are native Kotlin + ExoPlayer for exactly these reasons.
- **mpv stays available.** The `PlaybackEngine` seam keeps the resilience option
  open without paying mpv's cost (12 MB binary + software decode + GPL exposure)
  unless/until we need it.

## Consequences

**Positive**
- Smallest RAM floor and best decode efficiency on target hardware.
- No `libmpv.so` in the APK **for now** → smaller download, and no GPL exposure
  from mpv until/unless we add it.
- First-party player (Media3) — one Gradle dependency, no native build to own.
- Compose for TV gives a first-class D-pad focus system (`Modifier.focusable`,
  focus restorers) instead of the hand-rolled `TvFocusable` we built in Flutter.

**Negative / costs**
- **Full rewrite.** ~3,524 lines of working, tested Dart are discarded and
  reimplemented in Kotlin. The Flutter app is kept read-only as the reference
  spec (its logic — XMLTV parsing, ESPN mapping, channel-fusion, search ranking —
  ports directly).
- **Android-only.** No shared iOS/mobile path. Acceptable: this is a TV-first app.
- **ExoPlayer is pickier than mpv** with malformed/exotic IPTV streams. This is
  the risk we accept; it is exactly what the deferred `MpvEngine` fallback exists
  to absorb.

## If/when we add the mpv fallback (deferred, documented so it isn't re-litigated)

- Implement `MpvEngine : PlaybackEngine` over `libmpv` via JNI (the mpv-android
  `MPVLib` wrapper is the proven reference); render into the same `Surface`.
- Trigger: on a **fatal** ExoPlayer error (unsupported codec / demuxer failure),
  swap the engine on the same surface and re-open the URL. Optionally remember
  per-stream which engine succeeded.
- Costs to re-accept at that point: +~12 MB `libmpv.so` per ABI, an NDK/`.so`
  build pipeline to own, and **GPL licensing exposure** (common mpv/ffmpeg builds
  link GPL components) — fine for personal/OSS, a real constraint for commercial.

## Alternatives rejected

- **Stay on Flutter, swap to ExoPlayer under `video_player`.** Gets the
  hardware-decode win with no rewrite, but keeps the ~12 MB + RAM-floor Flutter
  engine. Rejected because the explicit goal is *leanest possible*, and the
  Flutter floor is the remaining gap.
- **Stay on Flutter with media_kit/mpv.** The current prototype. Rejected as the
  heaviest runtime (Flutter floor + software decode).
- **Native + mpv as the default player.** Rejected: software decode on a weak SoC
  is the opposite of lean. mpv is a fallback, not a default.

## Phase 7 — lean measurement results

### APK size (measured, R8 + resource shrink)

| Build | Size | Notes |
|---|---|---|
| **Native release, universal, unsigned** | **2.7 MB** | R8-stripped; no bundled media engine (ExoPlayer uses OS decoders) |
| Native debug, universal | 16 MB | unstripped, debug symbols |
| Flutter release, arm64 (split) | 33 MB | Flutter engine (~12 MB) + libmpv (~12 MB) + app |
| Flutter debug, universal | 229 MB | all ABIs, unstripped |

The headline: the shipping native APK is **~2.7 MB vs the Flutter build's 33 MB** —
the Flutter engine and the bundled mpv/ffmpeg are both gone. (Native is universal
here; a per-ABI split would be marginally smaller still. It is unsigned — signing
adds negligible size.)

### Lean disciplines — audited in code

- **#1 Bounded image cache** — Coil `ImageLoader` capped at `maxSizePercent(0.15)`
  memory + `maxSizeBytes(64 MB)` disk (`di/ImageModule.kt`), app-wide via
  `LiveWireApp : ImageLoaderFactory`.
- **#2 EPG windowed in data** — `GuideViewModel` keeps only programmes overlapping
  the visible window (`stopMs > windowStart && startMs < windowEnd`), never the whole
  guide. Now-playing is gated by the setting, so the EPG fetch is skipped entirely
  when off.
- **Hardware decode default** — Media3/ExoPlayer, per the core decision.

### On-device measurements — TO FILL IN (needs a real TV)

Run `scripts/measure.sh <TV-IP>` (or the commands in its header) and record:

| Metric | Flutter build | Native build | How measured |
|---|---|---|---|
| Idle RAM (PSS) | | | `dumpsys meminfo com.livewire.tv` at Home |
| Playback RAM (PSS, 1080p) | | | same, during live playback |
| Cold start → first frame | | | `am start-activity -W` TotalTime |
| Installed size | | | `dumpsys package … codeSize/dataSize` |

Expectation from the architecture: native should show a markedly lower idle RAM
floor (no Flutter engine) and lower playback CPU/heat (hardware vs software decode).
These are the numbers that validate — or challenge — the rewrite decision.

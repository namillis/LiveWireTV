# LiveWire — Project Handoff Report

A standalone briefing for an agent picking up this project. Read this first; it is
self-contained.

## What LiveWire is

A **pure-client Android TV / mobile IPTV & media player**, written natively in
**Kotlin + Jetpack Compose (Compose for TV) + Media3/ExoPlayer**. "Pure-client"
means **no backend of its own** — every data source is either the user's own
server (their IPTV provider) or a public/free third-party API called directly
from the device. There is no auth server, no subscription server, nothing to run.

Design goal that drives most decisions: **as lean as possible** — it must run well
on cheap TV sticks/boxes (~1–1.5 GB RAM, weak SoCs).

## Where everything lives

- **GitHub:** https://github.com/namillis/LiveWireTV (branch `main`)
- **Local working copy:** `/local/home/gsill/livewire-native/`
- Package id: `com.livewire.tv` (release), `com.livewire.tv.debug` (debug builds,
  so they coexist on one device).

## Current status: prototype complete, awaiting on-device validation

The app is **feature-complete as a prototype** and builds/tests green. It has
**not yet been run on a real device** — that is the single most important open
item (see "Open work" below). Nothing about playback, D-pad navigation, the visual
UI, or real memory usage has been verified on hardware; only pure logic is unit-tested.

## Architecture & stack

Clean-ish per-feature layering under `app/src/main/kotlin/com/livewire/tv/`:

| Layer | Choice |
|---|---|
| UI | Jetpack Compose + `androidx.tv` (Compose for TV). Standard `LazyColumn`/`LazyRow` for rails (the `tv-foundation` lazy lists are deprecated; we removed that dep). |
| Player | **Media3 / ExoPlayer**, hardware decode by default, behind a swappable `PlaybackEngine` interface (`core/player/`). |
| DI | Hilt |
| Async | Coroutines + Flow; ViewModels expose `StateFlow<UiState>`. |
| Networking | OkHttp (single shared client via `di/NetworkModule`) + kotlinx.serialization. |
| XML | Android `XmlPullParser` (streaming) for XMLTV. |
| Storage | EncryptedSharedPreferences (provider creds) + DataStore (settings). Room is a dependency but not yet used. |
| Images | Coil, with an **explicitly bounded** cache (`di/ImageModule`). |
| Nav | Navigation-Compose (`navigation/LiveWireNavHost.kt`). |

### The player engine seam (important architectural decision)

Playback is abstracted behind `PlaybackEngine` (`core/player/PlaybackEngine.kt`).
The only implementation is `ExoPlayerEngine` (hardware decode = the leanness win).
An **mpv fallback is deliberately deferred** — the interface exists so `MpvEngine`
can be dropped in later for streams ExoPlayer can't handle, with no UI change. The
full rationale is in `docs/ADR-0001-native-kotlin-exoplayer.md`, including the mpv
integration path and its costs (≈12 MB `libmpv.so`, an NDK build, GPL exposure).
**Do not add mpv unless real streams prove ExoPlayer insufficient.**

## Features (all built, parity with an earlier Flutter prototype)

- **Onboarding** (`feature/onboarding/`) — first run: enter your own Xtream Codes
  provider (URL + credentials), validated against the provider, stored encrypted
  on-device. This replaces what was originally a login/auth wall — it is
  configuration, not authentication.
- **Home** (`feature/home/`) — live channel rails (categories → `LazyRow`s of
  focusable channel cards), now-playing EPG label + progress on each card. Select
  → player. Header buttons: Guide / Sports / Search / Settings.
- **Player** (`feature/player/`) — full-screen Media3 player, D-pad controls
  (OK = play/pause, Back = exit), buffering/error overlays with a Retry, an
  auto-hiding now-playing info bar (title + LIVE/PAUSED badge), keep-screen-on.
- **EPG** (`feature/epg/`) — streaming XMLTV parser (gzip-aware fetch from the
  provider's `xmltv.php`), `EpgGuide` with now/next lookups, and a guide grid
  screen. The grid is **windowed in data** (only programmes overlapping the visible
  time window are kept) — a lean discipline.
- **Sports** (`feature/sports/`) — ESPN hidden API (no key): scoreboard + standings,
  and the signature feature: a **game→channel picker** that fuzzy-matches a game's
  broadcast networks against the user's own live channels and plays the match.
  Provider-agnostic behind a `SportsProvider` interface.
- **Search** (`feature/search/`) — cross-source ranked search over channels, EPG
  programmes, and sports games.
- **Providers** (`feature/providers/`) — manage multiple providers: add/edit/delete,
  set active (first in the list = active).
- **Settings** (`feature/settings/`) — DataStore-backed, and **wired into consumers**:
  stream format (TS/HLS) → player URLs, guide window hours → guide, now-playing
  toggle → home cards (and it skips the EPG fetch entirely when off).

## Data sources (no backend)

- **Live TV / VOD / EPG** → the user's **Xtream Codes** provider (`player_api.php`,
  `xmltv.php`). Their URL + creds, on-device.
- **Sports** → **ESPN hidden API** directly (`site.api.espn.com`), no key. Unofficial;
  swap to a licensed feed behind a proxy if this ever goes commercial.
- Metadata/tracking sources (TMDB/TVDB/Trakt) are planned-not-built; if added, keys
  ship in the client (accept as semi-public, use free tiers) and OAuth must be PKCE
  (no client secret in the app).

## Lean disciplines (baked in, per ADR-0001)

1. **Bounded image cache** — Coil capped at 15% heap / 64 MB disk (`di/ImageModule`).
2. **EPG windowed in data** — never hold/render the whole guide.
3. **Hardware decode default** — ExoPlayer; mpv only as a deferred fallback.

Measured sizes: **release APK ≈ 2.7 MB** (R8, universal, no bundled media engine)
vs. the earlier Flutter build's 33 MB. Debug APK ≈ 16 MB (unstripped).

## Build, test, CI, releases

- **JDK 17 required** (AGP 8.5.2 / Gradle 8.9). The Kotlin toolchain is pinned to 17.
  The dev host's default `java` is JDK 25; the host-specific JDK-17 path lives in
  `~/.gradle/gradle.properties` (NOT in the repo). CI and other machines use their
  own JDK 17. See README "JDK note".
- **Commands:** `./gradlew :app:assembleDebug`, `:app:testDebugUnitTest`,
  `:app:assembleRelease`.
- **Unit tests: 23, all passing.** They cover the pure logic only — XMLTV parsing
  (incl. timezone→UTC), sports channel-fusion, search ranking, provider ordering,
  Xtream URL building. There are **no UI/instrumented tests**.
- **CI** (`.github/workflows/ci.yml`) — on push/PR to `main`: JDK 17 + runner's
  Android SDK, runs unit tests + `assembleDebug`, uploads the debug APK artifact.
  Green. (Note: do NOT re-add `android-actions/setup-android@v3` — it fails on the
  removed legacy `tools` package; the runner's preinstalled SDK is used instead.)
- **Releases** (`.github/workflows/release.yml`) — **tag-triggered** (`v*`). Derives
  version from the tag, builds `assembleRelease`, **signs** it with the user's
  keystore (from 4 GitHub secrets: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`,
  `KEY_ALIAS`, `KEY_PASSWORD` — all already configured), and publishes a GitHub
  Release with the APK. Verified end-to-end via a since-deleted `v0.0.1-test` dry
  run (signed APK confirmed, `CN=LiveWire`). **No real release cut yet** — the user
  wants on-device testing first. Signing activates only when `KEYSTORE_FILE` is a
  non-blank env var, so local/secret-less builds stay unsigned rather than failing.
  Full flow in `docs/RELEASING.md`.

## Docs in the repo

- `README.md` — status, stack, build requirements (incl. the JDK-17 note), CI badge.
- `docs/ADR-0001-native-kotlin-exoplayer.md` — the native-rewrite decision, the
  engine seam, lean measurements, the deferred-mpv path.
- `docs/REWRITE_PLAN.md` — the phased plan (Phases 0–7), what ports vs. rebuilds.
- `docs/RELEASING.md` — keystore + secrets + tag-to-release flow.
- `scripts/measure.sh` — adb-based on-device measurement (RAM/cold-start/size).

## Open work — in priority order

1. **On-device validation (BLOCKING everything's credibility).** Sideload the debug
   APK (`com.livewire.tv.debug`) to a real Android TV / Fire TV and verify, in order:
   onboarding with a real Xtream provider → Home rails load with logos → **play a
   channel** (the hardware-decode bet) → guide renders → sports scoreboard + channel
   picker. Watch `adb logcat | grep -iE "livewire|exoplayer|AndroidRuntime"`.
2. **Lean measurements** — run `scripts/measure.sh <TV-IP>` against a **release**
   build; fill the table in ADR-0001. Compare idle/playback RAM + cold start.
3. **Tune the fuzzy matchers against a real provider** — sports channel-fusion
   (`SportsRepository.matchChannels`) and EPG channel-id↔channel matching depend on
   the provider's actual naming; expect to adjust `normalize()` once real names are seen.
4. **Cut `v1.0.0`** once testing passes (`git tag v1.0.0 && git push origin v1.0.0`).
5. Known simplifications to revisit: the guide grid has no time-axis header/now-line
   yet; soccer standings show W/L not points; settings apply on next screen load, not
   live; Home nav is a plain button row, not a polished top bar; only the first ~6
   channel categories load (no lazy-load of the rest).

## Honest boundary on how this was built

All work so far was done in an environment that **cannot run an Android emulator**
(no KVM, headless). So: everything was **compile-verified and unit-tested**, and
the APK/signing/CI/release machinery is **proven**, but the **running app has never
been observed** — no playback, no rendered UI, no real RAM figures. Treat the pure
logic as trusted (it has tests) and everything visual/runtime as unverified until
the on-device pass in item 1.

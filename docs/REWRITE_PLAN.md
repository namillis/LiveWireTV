# LiveWire — Native (Kotlin) Rewrite Plan

Companion to `ADR-0001-native-kotlin-exoplayer.md`. Target: a lean, TV-first
native Android app that reaches parity with the Flutter prototype, then surpasses
it on footprint.

## Stack

| Concern | Choice | Notes |
|---|---|---|
| Language | Kotlin | |
| UI | Jetpack **Compose** + **`androidx.tv`** (Compose for TV) | Material3 + TV components; built-in D-pad focus |
| Player | **AndroidX Media3 (ExoPlayer)** | hardware decode by default; `PlaybackEngine` interface wraps it |
| DI | Hilt | lightweight, standard |
| Async | Coroutines + Flow | replaces Dart Future/Stream + ChangeNotifier |
| HTTP | **OkHttp + Retrofit** (or kotlinx-serialization + Ktor client) | replaces dio |
| JSON | kotlinx.serialization | ESPN/Xtream payloads |
| XML | Android `XmlPullParser` (streaming) | replaces the `xml` package for XMLTV |
| DB | **Room** | replaces drift/sqflite; SQLite is already on-device |
| Prefs | Jetpack **DataStore** | replaces shared_preferences |
| Secrets | **EncryptedSharedPreferences** (Jetpack Security) | replaces flutter_secure_storage |
| Images | **Coil** (`coil-compose`) | replaces cached_network_image; set a bounded memory cache |
| Nav | Compose Navigation | replaces go_router |

## What PORTS directly (logic, already designed + tested in Dart)

These are the load-bearing algorithms; the Dart is the reference implementation.
Re-expressed in Kotlin, verified with the same test cases:

- **XMLTV parsing** (`xmltv_parser.dart`) → Kotlin `XmlPullParser`. Same element
  handling, same timezone-offset→UTC normalization. Port the 5 parser tests.
- **EPG now/next lookup** (`epg_models.dart` `EpgGuide`) → Kotlin data classes +
  the same sorted-list now/next logic.
- **ESPN mapping** (`espn_sports_provider.dart`) → same endpoints, same
  scoreboard + `broadcasts[].names` extraction, same standings mapping.
- **Game→channel fusion** (`sports_repository.dart` `matchChannels`) → same
  normalize (strip HD/4K, non-alphanumerics) + containment match. Port the 5
  fusion tests.
- **Search ranking** (`search_index.dart`) → same exact>prefix>word>substring
  scoring. Port the 6 ranking tests.
- **Xtream URL/auth shapes** (`provider_models.dart`, `xtream_client.dart`) →
  same `player_api.php` params, same `user_info` auth, same stream URL format.

## What is REBUILT (UI + platform glue — Flutter widgets don't port)

- All screens (onboarding, home rails, guide grid, sports, providers, search,
  settings) → Compose for TV screens.
- `TvFocusable` / `TvHorizontalList` → **deleted**; use Compose `focusable()` +
  `TvLazyRow`/`TvLazyColumn` focus system (native D-pad handling, better than the
  hand-rolled version).
- `PlayerCore` (media_kit) → `PlaybackEngine` interface + `ExoPlayerEngine`.

## Phases

### Phase 0 — Project scaffold & toolchain
- New Gradle/Kotlin Android project (`com.livewire.tv`), min-SDK ~23, TV `MainActivity`, leanback launcher intent + `<uses-feature android:name="android.software.leanback">`, banner.
- Add Compose, `androidx.tv`, Media3, Hilt, Room, DataStore, Coil, Retrofit/kotlinx-serialization.
- CI-equivalent local loop: `./gradlew assembleDebug`, `./gradlew testDebugUnitTest`, `./gradlew lint`.
- **Exit:** empty app builds + installs on the TV, boots to a blank scaffold.

### Phase 1 — Data layer + provider onboarding (parity slice)
- Port `ProviderConfig`, Xtream client (Retrofit), EncryptedSharedPreferences storage.
- Onboarding screen (Compose): enter+validate Xtream provider, store on-device, route to Home.
- **Exit:** first run → onboarding → validated → stored; ports the auth flow.

### Phase 2 — Player (the core, and the ADR's whole point)
- `PlaybackEngine` interface + `ExoPlayerEngine` (Media3), rendering into a Compose `AndroidView`(`PlayerView`/`SurfaceView`).
- Player screen: D-pad controls (OK=play/pause, Back=exit), buffering/error overlay + retry, wakelock.
- **Exit:** a live Xtream stream plays with **hardware decode**; measure RAM on-device vs. the Flutter build. This is the leanness proof point.

### Phase 3 — Live TV home
- `LiveChannelsController` logic → a ViewModel; channels grouped into `TvLazyRow` rails; select → player.
- Coil image cache with an explicit bounded size (lean discipline #1).
- **Exit:** home rails from the user's provider, D-pad navigable, launch playback.

### Phase 4 — EPG (guide + enrichment)
- Port XMLTV parser + `EpgGuide`; repository fetches `xmltv.php` (gzip-aware).
- Now-playing on channel cards; full guide grid (time axis + channel column + programme lanes), **windowed in data, not just view** (lean discipline #2).
- **Exit:** guide grid renders, now/next correct, memory bounded on a large guide.

### Phase 5 — Sports + game→channel picker
- Port ESPN provider (scoreboard/standings) + fusion; Compose scoreboard, Games/Standings toggle, channel-picker sheet → player.
- **Exit:** signature feature at parity.

### Phase 6 — Search, multi-provider, settings
- Port search ranking; cross-source search screen.
- Providers management (list/add/edit/delete/set-active).
- Settings via DataStore; **wire settings into consumers** (stream format→player, guide window→guide, now-playing→cards) — the wiring the Flutter build left pending.
- **Exit:** full feature parity with the Flutter prototype.

### Phase 7 — Lean hardening + measurement
- Bounded Coil cache; EPG data windowing; verify no full-guide retention.
- Measure on the real TV: idle RAM, playback RAM (1080p/4K), cold start, APK size. Record against the Flutter baseline in the ADR.
- **Exit:** documented leanness wins; decide whether the `MpvEngine` fallback is needed (only if streams fail on ExoPlayer).

## Lean disciplines (bake in from Phase 0, per the ADR)

1. **Bounded image cache** — Coil memory cache capped explicitly; evict aggressively on the guide grid.
2. **Window the EPG in data** — never hold/render the whole guide; only visible time-window × channel-window.
3. **Hardware decode default** — ExoPlayer; software (mpv) only as a deferred fallback.

## Reference & disposition

- Flutter app at `/local/home/gsill/livewire/` becomes **read-only reference**
  (the behavioral spec). Not deleted until native reaches Phase 6 parity.
- Prior design docs remain valid
  (`PURE_CLIENT_ARCHITECTURE.md` especially — the no-backend data-source
  decisions and the ESPN sports choice carry over unchanged).

## Open items to decide as we go
- Exact HTTP stack (Retrofit vs Ktor) — pick at Phase 1.
- Min-SDK floor vs. target-device coverage.
- Whether to keep the `com.livewire.tv` package identity (yes — matches the built Flutter APK, no user-visible change).

# LiveWire

[![Latest release](https://img.shields.io/gitea/v/release/namillis/LiveWireTV?gitea_url=https%3A%2F%2Fgit.namillis.me&sort=semver)](https://git.namillis.me/namillis/LiveWireTV/releases/latest)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

LiveWire is an IPTV player for Android TV, Google TV and Fire TV. You bring
your own Xtream Codes or M3U provider. There's no account and no LiveWire
server. The app talks to your provider directly, and your login is stored only
on the device.

![Home screen](docs/images/home.jpg)

| Guide | Player options |
|---|---|
| ![Guide](docs/images/guide.jpg) | ![Player options panel](docs/images/player.jpg) |
| **Sports** | **Settings** |
| ![Sports scoreboard](docs/images/sports.jpg) | ![Settings](docs/images/settings.jpg) |

## Features

- **Home.** A hero band shows what's on the focused channel now and next,
  with rails of channels grouped by your provider's categories.
- **Guide.** A programme grid with a red now-line. You can filter it by
  category or keyword, and it keeps your place when you come back from a
  channel. Above the grid, a details band describes the focused programme,
  and after 2 seconds it also plays that channel muted. A downloaded guide is
  reused for up to 3 hours, so reopening the Guide in that time doesn't
  download it again.
- **Player.** Up and Down change channel. Left opens the channel list, and
  Right opens Options: favourite, audio track, subtitles, picture mode (fit,
  fill, zoom), reload and stream info.
- **Catch-up and time shift.** Some providers keep a recording of each
  channel for a few days (an archive). On those channels:
  - **In the Guide,** scroll left to go back up to 2 days. Programmes you can
    still play are marked ⟲. OK on one of them plays it from the start. OK on
    the programme airing now asks whether to watch live or start it over.
    The remote's rewind and fast-forward keys, or Page Up and Page Down,
    jump the Guide by 3 hours.
  - **In the player,** OK pauses live TV, and OK again carries on from where
    you paused, behind live. Up goes back to live. To seek, press Down to
    select the progress bar; while it's selected, Left and Right move back
    and forward instead of opening the panels. The rewind and fast-forward
    keys seek too.
  - On a channel without an archive, OK pauses and plays as before, and a
    one-time message says the channel can't rewind.
- **Favourites.** To add one, hold OK on a channel in Home, the Guide or
  Search and choose Favourite, or use the Favourite row in the player's
  Options. Favourites appear as a rail at the top of Home, a ★ Favourites
  filter in the Guide and a group at the top of the player's channel list.
  If you start watching from a favourite, Up and Down in the player move
  through your favourites only. Reorder or remove them under
  Settings → Favourites. They're saved separately for each provider, on the
  device only.
- **Home.** Rails run Favourites, Recently watched (the last 8 channels you
  played), then your pinned categories. To pin a category, hold OK on it in
  the Guide's category column; hold OK again to unpin it. Up to 6 can be
  pinned, and they show on Home in the order you pinned them. With nothing
  pinned, Home shows the provider's first 6 categories. Pins are saved
  separately for each provider, on the device only.
- **Search.** Results are grouped into channels, programmes that are on now
  or later, and games. When the field is empty it shows your recent searches
  and the channels you watched last.
- **Sports.** Scores and schedules from ESPN for the NFL, college football, the
  NBA, men's college basketball, MLB, the NHL and the Premier League. Choosing
  a game opens a picker listing your channels whose names match the game's
  network or teams, ranked by how closely they match and checked against your
  guide.
- **Providers.** Add more than one Xtream or M3U provider and switch between
  them. Placeholder rows that some providers use as category headers
  (`##### NEWS #####`) are hidden.
- **Backup provider.** In Settings → Providers, open another provider and
  choose Use as backup. When the active provider is Xtream and doesn't
  answer a login within 6 seconds, Home, the Guide, Search and Sports use the
  backup instead, and Home says so. LiveWire checks the active provider again
  every 2 minutes while it's down and switches back once it answers.
  Favourites, pins and recents are saved per provider, so the backup shows its
  own.
- **Settings.** Stream format (MPEG-TS or HLS), guide window, channel preview,
  now-playing text on Home cards, favourites, and About with Check for
  updates.
- **Updates.** LiveWire checks git.namillis.me for a new release once a day
  and offers to install it. It installs an update only if the file's checksum
  matches the release and it's signed with the same key as the installed app.

Everything works with a TV remote's D-pad. On phones and touch TVs, taps work too.

## Install

LiveWire isn't in the Play Store or the Amazon Appstore. Download the APK from
the [latest release](https://git.namillis.me/namillis/LiveWireTV/releases/latest)
and sideload it.

1. **Allow installs from unknown sources** for the app you'll install with.
   Menu names vary by device.
   - **Fire TV:** Settings → My Fire TV → Developer options → Install unknown
     apps. If Developer options is hidden, open Settings → My Fire TV → About
     and select the device name seven times.
   - **Google TV / Android TV:** Settings → Apps → Security & restrictions →
     Unknown sources, then allow the app you'll use to install LiveWire
     (for example Downloader or your file manager).
2. **Install the APK** in one of these ways:
   - **Downloader app** (Fire TV and Google TV), free from the Amazon
     Appstore or Google Play: enter
     `https://git.namillis.me/namillis/LiveWireTV/releases/latest`, scroll to *Assets*, and
     select the `livewire-<version>.apk` link.
   - **adb** from a computer on the same network. Turn on network or ADB
     debugging in Developer options, and find the TV's IP address under its
     network settings:
     ```bash
     adb connect <tv-ip>:5555
     adb install -r livewire-<version>.apk
     ```
   - **A file manager**, from a USB drive or network share.
3. **Updating:** LiveWire checks git.namillis.me for a new release once a day
   and offers to install it from Home. You can also check under Settings →
   About → Check for updates. The first time, Android asks you to allow
   LiveWire to install apps. To update by hand, install the new APK over the
   old one. Every release from v0.2.2 on is signed with the same key, so your
   providers and settings are kept. Releases up to v0.2.1 used a different
   key: uninstall that version once before installing v0.2.2 or later.

## Setup

On first launch a short setup walks you through adding a provider: choose
Xtream Codes or M3U, enter the details, and LiveWire connects and shows the
channel count before you start watching. Your IPTV provider gives you these
details when you sign up. You can add more later under
Settings → Manage providers.

- **Xtream Codes:** server address, username and password. The guide is
  loaded from the same server. If your provider gave you a single link
  (`get.php?username=…&password=…` or `player_api.php?…`), paste it into
  Server URL and LiveWire splits it into the three fields.
- **Catch-up:** works only if your provider keeps an archive for a channel.
  For Xtream that comes from the provider. For M3U, the playlist needs
  catch-up attributes (`catchup`, `catchup-source`, `catchup-days`); without
  them channels show no archive.
- **M3U playlist:** the playlist URL, and optionally a guide (XMLTV) URL. If
  you leave the guide URL empty, LiveWire uses the one advertised in the
  playlist header (`url-tvg` or `x-tvg-url`), if there is one. Movie and
  series entries in a playlist are skipped; LiveWire plays live TV only.

Plain `http://` providers are allowed, because many IPTV servers don't offer
HTTPS. Traffic to those servers, including your login, is not encrypted.

If channels won't play, try switching Stream format between MPEG-TS and HLS
in Settings.

## Privacy

- Provider logins are stored on the device with Android's encrypted shared
  preferences. The key never leaves the device.
- Backups and device-to-device transfer are turned off, so app data isn't
  copied to Google Drive or a new device.
- LiveWire connects to your provider (channels, guide, streams), to ESPN's
  public scoreboard API for the Sports screen, to wherever your provider's
  channel list says the channel logos are hosted, and to git.namillis.me to
  check for and download updates. You can turn off the daily update check
  under Settings → About.
- There are no analytics, ads or crash reporting. The full source is in this
  repository if you want to check.

## Requirements

- Android 6.0 (API 23) or later.
- Built for TVs and TV sticks. It also installs on phones and tablets.

## Build from source

You need JDK 17 and the Android SDK. Gradle comes with the wrapper, and the
Android Gradle Plugin, SDK levels and library versions are set in
`app/build.gradle.kts` and `gradle/libs.versions.toml`.

```bash
git clone https://git.namillis.me/namillis/LiveWireTV.git
cd LiveWireTV
```

```bash
./gradlew :app:assembleDebug          # debug APK in app/build/outputs/apk/debug/
./gradlew :app:testDebugUnitTest      # unit tests
./gradlew :app:lintDebug              # Android lint
./gradlew :app:assembleRelease        # minified release APK
```

The debug build's package is `com.livewire.tv.debug`, so it installs next to a
release build without replacing it. To launch it from adb:

```bash
adb shell am start -n com.livewire.tv.debug/com.livewire.tv.MainActivity
```

If your default `java` isn't 17, point Gradle at a JDK 17 without editing
tracked files, either with `org.gradle.java.home` in
`~/.gradle/gradle.properties` or by exporting `JAVA_HOME`.

Run the unit tests and lint before opening a pull request:

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug
```

## Project layout

```
app/src/main/kotlin/com/livewire/tv/
  core/player/     PlaybackEngine interface and its ExoPlayer implementation
  core/net/        Response stream helpers (gzip handling)
  di/              Hilt modules
  navigation/      Nav host and the left navigation drawer
  ui/              Theme, shared surfaces, the shared provider form, touch support
  feature/
    onboarding/    First-run setup wizard
    providers/     Xtream and M3U clients, provider storage, Providers screen
    epg/           Guide screen, XMLTV parsing and the guide cache
    catchup/       Archive URLs and past guide listings for catch-up
    home/          Home screen
    player/        Player screen, overlay, Options and channel list panels
    favorites/     Favourites storage, hold-OK menu, Channel info, manage screen
    search/        Search and search history
    sports/        ESPN scoreboard and the channel picker
    settings/      Settings screen and stored preferences
    update/        Update check, download, verification and install
scripts/measure.sh Memory, start-up and APK size measurements on a real device
```

The player UI talks to `PlaybackEngine`, not to ExoPlayer directly, so another
playback engine can be added later without changing the screens.

## Releasing

`scripts/release.py` builds a signed release APK and publishes it as a release
on git.namillis.me, with a `.sha256` checksum file and `update.json` that the
in-app updater reads. See [docs/RELEASING.md](docs/RELEASING.md) for the steps,
version numbering and signing setup.

## Credits

- Playback by [AndroidX Media3 / ExoPlayer](https://github.com/androidx/media).
- Fonts: [Inter](https://github.com/rsms/inter),
  [Space Grotesk](https://github.com/floriankarsten/space-grotesk) and
  [JetBrains Mono](https://github.com/JetBrains/JetBrainsMono), all under the
  SIL Open Font License. They're bundled, trimmed to Latin characters, because
  many TV devices can't download fonts. Licence texts and source versions are
  in [third_party/fonts/](third_party/fonts/).
- Sports data comes from ESPN's public site API. LiveWire isn't affiliated
  with or endorsed by ESPN.

## Disclaimer

LiveWire is a player only. It doesn't provide, host or link to any channels or
streams. You need your own provider, and you're responsible for making sure
you have the right to watch what it serves.

## License

[MIT](LICENSE)

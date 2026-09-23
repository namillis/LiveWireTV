# LiveWire (native)

[![CI](https://github.com/namillis/LiveWireTV/actions/workflows/ci.yml/badge.svg)](https://github.com/namillis/LiveWireTV/actions/workflows/ci.yml)

Pure-client Android **TV**/mobile IPTV & media player. Native rewrite of the
Flutter prototype — Kotlin + Jetpack Compose (Compose for TV) + Media3/ExoPlayer,
no backend. See `docs/ADR-0001-native-kotlin-exoplayer.md` for why, and
`docs/REWRITE_PLAN.md` for the phased plan.

## Status

Phase 0 (scaffold) complete: the app builds to an installable Android TV APK and
boots to a blank Compose-for-TV surface. Features land in Phases 1–7 per the plan.

## Build requirements

- **JDK 17** (AGP 8.5 requires it; the toolchain is pinned to it).
- **Android SDK** with platform 35 + build-tools (this host: `~/android-sdk`).
- Gradle is provided by the wrapper (`./gradlew`), pinned to **8.9**.

### JDK note (important on this host)

AGP 8.5 requires **JDK 17**. The Kotlin compile toolchain is pinned via
`kotlin { jvmToolchain(17) }` in `app/build.gradle.kts`, so compiled bytecode is
always JDK-17.

If your machine's default `java` is **not** 17 (this dev host defaults to JDK 25,
which Gradle cannot run under), point Gradle at a JDK 17 **without editing any
tracked file** — the tracked `gradle.properties` intentionally carries no host
paths. Use either:

```bash
# option A — user-global Gradle properties (never in the repo):
#   ~/.gradle/gradle.properties
org.gradle.java.home=/absolute/path/to/jdk-17
org.gradle.java.installations.paths=/absolute/path/to/jdk-17

# option B — environment, per shell:
export JAVA_HOME=/absolute/path/to/jdk-17
```

On this dev host, option A is already set in `~/.gradle/gradle.properties`
pointing at `/usr/lib/jvm/java-17-amazon-corretto.x86_64`, so `./gradlew` works
with no per-shell setup.

## Common commands

```bash
./gradlew :app:assembleDebug          # build the debug APK
./gradlew :app:testDebugUnitTest      # run JVM unit tests
./gradlew :app:lint                   # Android lint
./gradlew :app:assembleRelease        # minified release APK (R8 + resource shrink)
```

Debug APK output: `app/build/outputs/apk/debug/app-debug.apk`

## Install on a device / TV

```bash
adb connect <TV-IP>:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat | grep -i livewire        # watch logs while testing
```

The app registers a `LEANBACK_LAUNCHER` intent, so it appears on the Android TV
home row, and a normal `LAUNCHER` intent so it also installs on phones/tablets.

## Layout

```
app/src/main/kotlin/com/livewire/tv/
  LiveWireApp.kt        # Hilt @HiltAndroidApp
  MainActivity.kt       # @AndroidEntryPoint, hosts the Compose-for-TV scaffold
  ui/theme/Theme.kt     # LiveWire teal, dark
app/src/main/res/       # manifest resources: icon, TV banner, theme, strings
docs/                   # ADR + rewrite plan
```

## Stack

Compose + `androidx.tv` · Media3/ExoPlayer (+HLS) · Hilt · Room · DataStore ·
EncryptedSharedPreferences · Coil · Retrofit + kotlinx-serialization + OkHttp ·
Navigation-Compose · Coroutines. Versions are centralized in
`gradle/libs.versions.toml`.

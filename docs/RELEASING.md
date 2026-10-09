# Releasing LiveWire

Releases are built by Forgejo Actions on `git.namillis.me` from a git tag.
Pushing a tag named `v*` runs `.forgejo/workflows/release.yml`. It builds a
minified release APK, signs it, and publishes a release on
[git.namillis.me/namillis/LiveWireTV](https://git.namillis.me/namillis/LiveWireTV/releases)
with the APK, its checksum and `update.json` attached.

Releases up to v0.2.1 were built on GitHub. The GitHub account is suspended, so
those builds check `api.github.com` for updates and can't find any. Anyone on
v0.2.1 or older has to sideload v0.2.2 once by hand. From v0.2.2 on, the app
checks `git.namillis.me` and updates itself.

## One-time setup

### Runner

Both workflows run on `runs-on: docker` inside a `node:22-bookworm` container,
so they need a [Forgejo runner](https://forgejo.org/docs/latest/admin/actions/)
registered for this repo (or for the user or instance) with the `docker` label,
backed by Docker or Podman. They install JDK 17 and the Android command-line
tools themselves (`scripts/ci/setup-android-sdk.sh`), and Gradle downloads the
SDK platform and build-tools. A release build needs about 4 GB of memory and
5 GB of disk.

The runner needs outbound access to `data.forgejo.org` (the `actions/*`
mirror), `dl.google.com` (Android SDK), the Temurin JDK download and Maven
repositories.

Then turn Actions on for the repo: **Settings → Units → Actions**.

### Signing secrets

Add these under the repo's **Settings → Actions → Secrets**:

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | The release keystore file, base64-encoded |
| `KEYSTORE_PASSWORD` | The keystore password |
| `KEY_ALIAS` | The key's alias inside the keystore |
| `KEY_PASSWORD` | The key's password |

Use the **same keystore as the GitHub releases**. The updater only installs an
APK signed with the same key as the installed app, and Android refuses it too.

If `KEYSTORE_BASE64` is missing, the workflow still succeeds but publishes an
**unsigned** APK and says so in the release notes. Android won't install an
unsigned APK, so add the secrets and tag the next patch version.

The signing values reach Gradle only as environment variables. The keystore is
decoded outside the checkout and deleted at the end of the job. Nothing about
the key is committed or logged.

No token secret is needed. The workflow publishes with Forgejo's automatic
`FORGEJO_TOKEN`, which can write releases on its own repository.

## Version numbers

Tags follow `vMAJOR.MINOR.PATCH`. While the app is below 1.0:

- **PATCH** (`v0.2.1` → `v0.2.2`): a normal release of whatever is on `main`.
  Features, fixes and restyles all ship as patch releases.
- **MINOR** (`v0.2.x` → `v0.3.0`): a milestone, or a release that changes stored
  data in a way an older build can't read (for example the provider store or the
  guide cache format), so testers know to expect a re-setup.
- **MAJOR**: reserved for the first public release (`v1.0.0`).

The tag is the only place a version is set. `scripts/ci/version-from-tag.sh`
turns it into the two values Gradle needs:

- `versionName` is the tag without the `v` (`v0.2.2` → `0.2.2`).
- `versionCode` is `MAJOR × 10000 + MINOR × 100 + PATCH` (`v0.2.2` → `202`).

On GitHub, `versionCode` was the workflow's run number, and v0.2.1 reached 9.
Forgejo's run numbers start again at 1, and Android refuses to install an APK
over one with a higher `versionCode`. So the code now comes from the tag, which
always rises when the version does. The script refuses a tag whose MINOR or
PATCH is above 99, or whose code isn't above 9.

Local builds use `versionName = 1.0.0-dev` and `versionCode = 1` unless you pass
`-PversionName` / `-PversionCode`.

## When to release

There's no fixed schedule. Tag a release when `main` has user-visible changes
that have been checked on the emulator or a device, and CI on `main` passed.

## Cutting a release

1. Make sure your local `main` matches the server, and that CI passed on the
   last merge (the **Actions** tab on git.namillis.me):

   ```bash
   git fetch origin --tags
   git status -sb    # main...origin/main with nothing ahead or behind
   ```

2. Tag the commit and push the tag. Use an annotated tag, and push it by name:

   ```bash
   git tag -a v0.2.2 origin/main -m "LiveWire v0.2.2"
   git push origin v0.2.2
   ```

3. Watch the Release run in the **Actions** tab (a few minutes, longer on the
   first run while the runner downloads the SDK).

4. Check the published APK (see [Verifying a release](#verifying-a-release)).

Never move or re-push an existing tag. If a release is bad, fix it on `main`
and tag the next patch version.

If a run fails after it created the release, it leaves a **draft**. The
workflow refuses to overwrite an existing release, so delete the draft on the
Releases page before re-running the job.

## Release assets

Each release publishes three files:

| Asset | Purpose |
|---|---|
| `livewire-X.Y.Z.apk` | The app. |
| `livewire-X.Y.Z.apk.sha256` | `sha256sum`-format checksum (`<64-hex>  <filename>`). The in-app updater downloads this and verifies the APK's SHA-256 against it before installing. |
| `update.json` | A small machine-readable manifest: `{ version, versionCode, sha256, size, minSdk }`. |

`scripts/ci/publish-forgejo-release.sh` creates the release as a draft, uploads
all three files, and only then publishes it. The updater reads
`releases/latest`, which skips drafts, so it never sees a release without its
`.sha256`.

The in-app updater (`feature/update/`) reads
`https://git.namillis.me/api/v1/repos/namillis/LiveWireTV/releases/latest`. It
compares the tag against the running `versionName`, downloads the APK, and
verifies its size, SHA-256 (against the `.sha256` asset) and signing
certificate (which must match the installed app). Then it hands the APK to
Android's installer. A release without the `.sha256` asset fails verification
rather than installing unverified.

Do not remove these files or rename them without updating
`BuildConfig.UPDATE_ASSET_PATTERN` in `app/build.gradle.kts`. The updater looks
for `livewire-<version>.apk` and its `.sha256` sidecar by name. The update host
is `updateApiBase` in the same file.

### Creating or replacing the keystore

```bash
keytool -genkeypair -v -keystore livewire-release.jks -alias livewire \
  -keyalg RSA -keysize 4096 -validity 10000
base64 -i livewire-release.jks | pbcopy     # macOS; paste into KEYSTORE_BASE64
```

**Keep the keystore and its passwords backed up somewhere safe, outside the
repo.** Every update must be signed with the same key. If the key is lost or
replaced, existing installs can't be updated in place: users have to uninstall
LiveWire, which deletes their providers, and install again.

## Verifying a release

Download the APK and confirm it's signed with the same certificate as the
previous release:

```bash
mkdir -p /tmp/lw && cd /tmp/lw
curl -fLO https://git.namillis.me/namillis/LiveWireTV/releases/download/v0.2.2/livewire-0.2.2.apk
curl -fLO https://git.namillis.me/namillis/LiveWireTV/releases/download/v0.2.2/livewire-0.2.2.apk.sha256
shasum -a 256 -c livewire-0.2.2.apk.sha256
"$ANDROID_HOME"/build-tools/*/apksigner verify --print-certs livewire-0.2.2.apk \
  | grep 'SHA-256'
```

The SHA-256 digest must match the one from the previous release. Then install it
over the previous release on the emulator or a TV and open it:

```bash
adb install -r livewire-0.2.2.apk
adb shell monkey -p com.livewire.tv -c android.intent.category.LAUNCHER 1
```

`adb install -r` failing with `INSTALL_FAILED_UPDATE_INCOMPATIBLE` means the
signing key changed; `INSTALL_FAILED_VERSION_DOWNGRADE` means the `versionCode`
went backwards.

The release APK uses the package `com.livewire.tv`; debug builds use
`com.livewire.tv.debug`, so both can be installed side by side.

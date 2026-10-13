# Releasing LiveWire

Releases are built and published by Forgejo Actions on git.namillis.me. Pushing
a tag like `v0.2.6` runs `.forgejo/workflows/release.yml` on a runner on the
project's VM. It runs `scripts/release.py`, which runs the tests and lint,
builds a minified release APK signed with the release key, checks it, and
publishes a release on
[git.namillis.me](https://git.namillis.me/namillis/LiveWireTV/releases) with
the APK, its checksum and `update.json` attached. The same script also runs by
hand (see [Releasing by hand](#releasing-by-hand)).

Every pull request and every push to `main` runs the unit tests and lint
(`.forgejo/workflows/ci.yml`).

## Version numbers

Tags follow `vMAJOR.MINOR.PATCH`. While the app is below 1.0:

- **PATCH** (`v0.1.0` → `v0.1.1`): a normal release of whatever is on `main`.
  Features, fixes and restyles all ship as patch releases.
- **MINOR** (`v0.1.x` → `v0.2.0`): a milestone, or a release that changes stored
  data in a way an older build can't read (for example the provider store or the
  guide cache format), so testers know to expect a re-setup.
- **MAJOR**: reserved for the first public release (`v1.0.0`).

The version you pass to the script is the only place a version is set:

- `versionName` is the version itself (`0.2.2`), and the tag is `v0.2.2`.
- `versionCode` is `MAJOR*10000 + MINOR*100 + PATCH` (`0.2.2` → `202`), so it
  rises with every release. Android refuses to install an APK over one with a
  higher `versionCode`. MINOR and PATCH must stay below 100.

Local builds use `versionName = 1.0.0-dev` and `versionCode = 1` unless you pass
`-PversionName` / `-PversionCode`.

## When to release

There's no fixed schedule. Tag a release when `main` has user-visible changes
that have been checked on the emulator or a device, and the unit tests and lint
pass.

## Cutting a release

1. Make sure the commit you want to release is merged to `main` and its CI run
   passed.

2. Optionally dry-run it in CI: on the repo's Actions tab, run the **Release**
   workflow on `main` with the version, for example `0.2.6`. It builds, signs
   and checks the APK and prints the release notes, but publishes nothing.

3. Push the tag:

   ```bash
   git fetch origin
   git tag v0.2.6 origin/main
   git push origin refs/tags/v0.2.6
   ```

   The Release workflow builds that commit and publishes the release. It takes
   about 15 minutes. If it fails, the tag stays but no release is published:
   fix the problem on `main` and release the next patch version.

Never move or re-push an existing tag. If a release is bad, fix it on `main`
and release the next patch version.

The release notes list the commit subjects since the previous tag, without
their `feat:` / `fix:` prefixes. The in-app update dialog shows them as
What's new.

## Releasing by hand

If CI is down, the same script runs from a dev machine. You need:

- JDK 17 or later as `JAVA_HOME`, and an Android SDK with build-tools 36.0.0 as
  `ANDROID_HOME`.
- The release keystore and its password (see [Signing](#signing)).
- A git.namillis.me access token with write access to the repo, as
  `FORGEJO_TOKEN`.

Check out the commit to release (it must be on `main`) with a clean worktree,
then dry-run and release:

```bash
LIVEWIRE_KEYSTORE=/path/to/livewire-release.p12 \
LIVEWIRE_KEYSTORE_PASS_FILE=/path/to/livewire-signing-recovery.txt \
python3 scripts/release.py 0.2.6 --dry-run

FORGEJO_TOKEN=... LIVEWIRE_KEYSTORE=... LIVEWIRE_KEYSTORE_PASS_FILE=... \
python3 scripts/release.py 0.2.6
```

Without an existing tag, the script creates `v0.2.6` at HEAD when it publishes
the release.

## Release assets

Each release publishes three files:

| Asset | Purpose |
|---|---|
| `livewire-X.Y.Z.apk` | The app. |
| `livewire-X.Y.Z.apk.sha256` | `sha256sum`-format checksum (`<64-hex>  <filename>`). The in-app updater downloads this and verifies the APK's SHA-256 against it before installing. |
| `update.json` | A small machine-readable manifest: `{ version, versionCode, sha256, size, minSdk }`. Handy for a lightweight "is there an update" probe without parsing the releases API. |

The in-app self-update feature (in `feature/update/`) reads
`https://git.namillis.me/api/v1/repos/namillis/LiveWireTV/releases/latest`,
compares the tag against the running `versionName`, downloads the APK, verifies
its size, SHA-256 (against the `.sha256` asset) and signing certificate (which
must match the installed app), then hands it to Android's installer. The
`.sha256` asset is therefore **required** for the updater to install a release:
a release without it fails verification rather than installing unverified.

Do not rename these files without updating `BuildConfig.UPDATE_ASSET_PATTERN`
in `app/build.gradle.kts`. The updater looks for `livewire-<version>.apk` and
its `.sha256` sidecar by name.

## Signing

The release key is a PKCS12 keystore with the alias `livewire`, created on
2026-10-09. Its certificate SHA-256 is:

```
B9:39:21:94:61:93:74:36:89:74:08:D8:92:ED:A7:6C:C7:7F:DA:AC:FB:F2:79:3F:29:F8:61:AD:1C:1D:74:50
```

`scripts/release.py` refuses to publish an APK signed with any other
certificate. It passes the keystore to Gradle only as environment variables
(`KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`), so nothing
about the key is committed or logged. A release build without those variables
is left unsigned.

In CI the key comes from two repo secrets: `LIVEWIRE_KEYSTORE_BASE64` (the
keystore, base64-encoded) and `LIVEWIRE_KEYSTORE_PASS`. The Release workflow
writes the keystore to a temporary file for the run and deletes it afterwards.
The secrets are stored encrypted by Forgejo on the same VM as the runner.

**Keep the keystore and its password backed up somewhere safe, outside the
repo.** Every update must be signed with the same key. If the key is lost or
replaced, existing installs can't be updated in place: users have to uninstall
LiveWire, which deletes their providers, and install again.

Releases up to v0.2.1 were signed with an earlier key that was lost when the
project moved off GitHub. Installs of those versions have to be uninstalled
once before installing v0.2.2 or later.

## Verifying a release

Download the APK and confirm it's signed with the release certificate:

```bash
curl -LO https://git.namillis.me/namillis/LiveWireTV/releases/download/v0.2.2/livewire-0.2.2.apk
"$ANDROID_HOME"/build-tools/36.0.0/apksigner verify --print-certs livewire-0.2.2.apk \
  | grep 'SHA-256'
```

The digest must match the one above. Then install it over the previous release
on the emulator or a TV and open it:

```bash
adb install -r livewire-0.2.2.apk
adb shell monkey -p com.livewire.tv -c android.intent.category.LAUNCHER 1
```

`adb install -r` failing with `INSTALL_FAILED_UPDATE_INCOMPATIBLE` means the
signing key changed; `INSTALL_FAILED_VERSION_DOWNGRADE` means the `versionCode`
went backwards.

The release APK uses the package `com.livewire.tv`; debug builds use
`com.livewire.tv.debug`, so both can be installed side by side.

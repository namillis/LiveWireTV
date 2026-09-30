# Releasing LiveWire

Releases are built by GitHub Actions from a git tag. Pushing a tag named
`v*` runs `.github/workflows/release.yml`, which builds a minified release APK,
signs it, and publishes a GitHub Release with the APK attached and release
notes generated from the merged PRs.

## Version numbers

Tags follow `vMAJOR.MINOR.PATCH`. While the app is below 1.0:

- **PATCH** (`v0.1.0` → `v0.1.1`): a normal release of whatever is on `main`.
  Features, fixes and restyles all ship as patch releases.
- **MINOR** (`v0.1.x` → `v0.2.0`): a milestone, or a release that changes stored
  data in a way an older build can't read (for example the provider store or the
  guide cache format), so testers know to expect a re-setup.
- **MAJOR**: reserved for the first public release (`v1.0.0`).

The tag is the only place a version is set. CI passes it to Gradle:

- `versionName` is the tag without the `v` (`v0.1.1` → `0.1.1`).
- `versionCode` is the Release workflow's run number, so it rises by at least one
  on every release. Android refuses to install an APK over one with a higher
  `versionCode`, so **don't rename or recreate `release.yml`**: GitHub would treat
  it as a new workflow and restart the run number at 1.

Local builds use `versionName = 1.0.0-dev` and `versionCode = 1` unless you pass
`-PversionName` / `-PversionCode`.

## When to release

There's no fixed schedule. Tag a release when `main` has user-visible changes
that have been checked on the emulator or a device, and CI on `main` is green.

## Cutting a release

1. Make sure your local `main` matches GitHub and CI passed on the last merge:

   ```bash
   git fetch origin --tags
   gh run list --workflow ci.yml --branch main --limit 1
   ```

2. Tag the commit and push the tag. Use an annotated tag, and push it by name:

   ```bash
   git tag -a v0.1.1 origin/main -m "LiveWire v0.1.1"
   git push origin v0.1.1
   ```

3. Watch the Release run (about 3 minutes):

   ```bash
   gh run watch "$(gh run list --workflow release.yml --limit 1 --json databaseId --jq '.[0].databaseId')"
   ```

4. Check the published APK (see [Verifying a release](#verifying-a-release)).

Never move or re-push an existing tag. If a release is bad, fix it on `main`
and tag the next patch version.

## Signing

The workflow signs the APK when these repository secrets are set
(Settings → Secrets and variables → Actions):

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | The release keystore file, base64-encoded |
| `KEYSTORE_PASSWORD` | The keystore password |
| `KEY_ALIAS` | The key's alias inside the keystore |
| `KEY_PASSWORD` | The key's password |

If `KEYSTORE_BASE64` is missing, the workflow still succeeds but publishes an
**unsigned** APK and says so in the release notes. Android won't install an
unsigned APK, so add the secrets and tag the next patch version.

The signing values reach Gradle only as environment variables, and the keystore
is decoded outside the checkout. Nothing about the key is committed or logged.

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
gh release download v0.1.1 --pattern '*.apk' --dir /tmp/lw
"$ANDROID_HOME"/build-tools/*/apksigner verify --print-certs /tmp/lw/livewire-0.1.1.apk \
  | grep 'SHA-256'
```

The SHA-256 digest must match the one from the previous release. Then install it
over the previous release on the emulator or a TV and open it:

```bash
adb install -r /tmp/lw/livewire-0.1.1.apk
adb shell monkey -p com.livewire.tv -c android.intent.category.LAUNCHER 1
```

`adb install -r` failing with `INSTALL_FAILED_UPDATE_INCOMPATIBLE` means the
signing key changed; `INSTALL_FAILED_VERSION_DOWNGRADE` means the `versionCode`
went backwards.

The release APK uses the package `com.livewire.tv`; debug builds use
`com.livewire.tv.debug`, so both can be installed side by side.

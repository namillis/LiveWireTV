# Releasing LiveWire

Releases are **tag-triggered**: pushing a `v*` tag runs `.github/workflows/release.yml`,
which builds a release APK, signs it (if signing secrets are configured), and publishes
a GitHub Release with the APK attached.

## One-time: create the signing keystore

Android requires every update to be signed with the **same** key as the installed build.
Generate a long-lived release key once and keep it safe forever.

```bash
keytool -genkeypair -v \
  -keystore livewire-release.jks \
  -alias livewire \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storetype PKCS12 \
  -dname "CN=LiveWire, O=namillis, C=US"
```

**Back it up** (password manager + offline copy). If you lose the keystore or its
password, you can never update installed copies again — only uninstall/reinstall.
Never commit it (`.gitignore` already blocks `*.jks`/`*.keystore`); keep it outside
the repo directory.

## One-time: add the four GitHub secrets

```bash
gh secret set KEYSTORE_BASE64   --repo namillis/LiveWireTV < <(base64 -w0 livewire-release.jks)  # macOS: base64 -i livewire-release.jks
gh secret set KEYSTORE_PASSWORD --repo namillis/LiveWireTV
gh secret set KEY_ALIAS         --repo namillis/LiveWireTV   # livewire
gh secret set KEY_PASSWORD      --repo namillis/LiveWireTV
```

Until these exist, tagged releases still publish — but the APK is **unsigned** (the
release notes say so). Add the secrets, then re-tag to get a signed build.

## Cut a release

```bash
git tag v1.0.0
git push origin v1.0.0
```

The workflow derives `versionName` from the tag (`v1.0.0` → `1.0.0`) and uses the CI
run number as `versionCode` (monotonic, so updates always install). The signed APK
lands on the repo's **Releases** page as `livewire-1.0.0.apk`.

## How signing is handled (security notes)

- Secrets live only in **GitHub Actions encrypted secrets** — never in the repo.
- The keystore is decoded to `$RUNNER_TEMP` (outside the workspace), and signing
  values reach Gradle **only as environment variables** — never CLI args, never logged.
- `app/build.gradle.kts` populates the release signing config only when `KEYSTORE_FILE`
  is present, so local builds and secret-less CI stay unsigned rather than failing.

## Install on a device

```bash
adb install -r livewire-<version>.apk
```

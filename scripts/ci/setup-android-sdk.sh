#!/usr/bin/env bash
# Install the Android command-line tools on a Forgejo Actions runner and accept the
# SDK licences. The Gradle build then downloads the platform and build-tools it needs.
#
# Forgejo's docker runners use plain Debian/Node images with no Android SDK (unlike
# GitHub's ubuntu-latest), so CI and the release workflow both call this first.
#
# The zip is pinned by URL and SHA-256. To update it, take the new linux zip name from
# https://dl.google.com/android/repository/repository2-3.xml (cmdline-tools;latest),
# download it, and replace both values below with the file name and `sha256sum` output.
set -euo pipefail

CLT_ZIP="commandlinetools-linux-16111833_latest.zip"
CLT_SHA256="0877a1d048fe4a24efe2eff536ca4223f7adeb58648bb81909d33c446918cfa8"

SDK="${ANDROID_HOME:-$HOME/android-sdk}"

if ! command -v unzip >/dev/null 2>&1; then
  apt-get update -qq && apt-get install -y -qq unzip >/dev/null
fi

if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
  tmp="$(mktemp -d)"
  curl -fsSL -o "$tmp/clt.zip" "https://dl.google.com/android/repository/$CLT_ZIP"
  echo "$CLT_SHA256  $tmp/clt.zip" | sha256sum -c -
  mkdir -p "$SDK/cmdline-tools"
  unzip -q "$tmp/clt.zip" -d "$tmp"
  rm -rf "$SDK/cmdline-tools/latest"
  mv "$tmp/cmdline-tools" "$SDK/cmdline-tools/latest"
  rm -rf "$tmp"
fi

yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --licenses >/dev/null 2>&1 || true

# Expose the SDK to later steps. Forgejo supports GitHub's $GITHUB_ENV file.
echo "ANDROID_HOME=$SDK" >> "$GITHUB_ENV"
echo "ANDROID_SDK_ROOT=$SDK" >> "$GITHUB_ENV"

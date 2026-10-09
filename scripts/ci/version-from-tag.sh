#!/usr/bin/env bash
# Print "versionName versionCode" for a release tag like v0.2.2.
#
#   versionName = the tag without the leading "v"        (v0.2.2 -> 0.2.2)
#   versionCode = MAJOR*10000 + MINOR*100 + PATCH          (v0.2.2 -> 202)
#
# Deriving versionCode from the tag (instead of the CI run number, as on GitHub) keeps
# it rising across CI hosts: Forgejo restarts run numbers at 1, and GitHub releases
# already reached versionCode 9. Android refuses to install a lower versionCode over a
# higher one. MINOR and PATCH must stay below 100 for the scheme to stay ordered.
set -euo pipefail

tag="${1:?usage: version-from-tag.sh vMAJOR.MINOR.PATCH}"
if [[ ! "$tag" =~ ^v([0-9]+)\.([0-9]+)\.([0-9]+)$ ]]; then
  echo "Tag '$tag' is not vMAJOR.MINOR.PATCH" >&2
  exit 1
fi
major=$((10#${BASH_REMATCH[1]}))
minor=$((10#${BASH_REMATCH[2]}))
patch=$((10#${BASH_REMATCH[3]}))
if (( minor > 99 || patch > 99 )); then
  echo "Tag '$tag': MINOR and PATCH must be 0-99 for the versionCode scheme" >&2
  exit 1
fi
code=$(( major * 10000 + minor * 100 + patch ))
if (( code <= 9 )); then
  # v0.2.1 shipped as versionCode 9 from GitHub's run number; anything at or below it
  # would be refused as a downgrade by every existing install.
  echo "Tag '$tag' gives versionCode $code, which is not above the last GitHub release (9)" >&2
  exit 1
fi
echo "${tag#v} $code"

#!/usr/bin/env python3
"""Build, sign, verify and publish a LiveWire release on git.namillis.me.

Run from the repo root on an up-to-date, clean `main`:

    python3 scripts/release.py 0.2.2 --dry-run
    python3 scripts/release.py 0.2.2

Steps:
  1. Check that HEAD is origin/main, the worktree is clean, and tag v<version>
     doesn't exist yet.
  2. Run unit tests, lint and the release build with versionName <version> and
     versionCode MAJOR*10000 + MINOR*100 + PATCH, signed with the release key.
  3. Check the APK: package com.livewire.tv, the expected version, and the
     release certificate below. A build signed with any other key is refused,
     because it could not update existing installs.
  4. Write dist/livewire-<version>.apk, its .sha256 sidecar and update.json.
  5. Tag HEAD as v<version>, create a draft Forgejo release with the three
     files attached, then publish it. --dry-run stops before this step.

Environment:
  LIVEWIRE_KEYSTORE        path to the PKCS12 release keystore (alias "livewire")
  LIVEWIRE_KEYSTORE_PASS   keystore password; or LIVEWIRE_KEYSTORE_PASS_FILE, a
                           file whose "Keystore password:" line holds it
  ANDROID_HOME             Android SDK with build-tools 36.0.0
  JAVA_HOME                JDK 17 or later

Publishing uses the `fj` CLI (forgejo-cli), signed in to git.namillis.me with
`fj -H git.namillis.me auth login`.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import urllib.request
from pathlib import Path

PACKAGE = "com.livewire.tv"
KEY_ALIAS = "livewire"
CERT_SHA256 = "b939219461937436897408d892eda76cc77fdaacfbf2793f29f861ad1c1d7450"
HOST = "git.namillis.me"
REPO = "namillis/LiveWireTV"
API = f"https://{HOST}/api/v1/repos/{REPO}"
BUILD_TOOLS = "36.0.0"
MIN_SDK = 23

ROOT = Path(__file__).resolve().parent.parent
APK_DIR = ROOT / "app" / "build" / "outputs" / "apk" / "release"
DIST = ROOT / "dist"


def die(msg: str) -> None:
    print(f"release: {msg}", file=sys.stderr)
    sys.exit(1)


def run(cmd: list[str], stderr=subprocess.PIPE) -> str:
    return subprocess.run(cmd, check=True, stdout=subprocess.PIPE, stderr=stderr, text=True).stdout


def git(*args: str) -> str:
    return run(["git", "-C", str(ROOT), *args]).strip()


def secret(name: str) -> str | None:
    value = os.environ.get(name)
    if value:
        return value
    path = os.environ.get(name + "_FILE")
    if not path:
        return None
    text = Path(path).expanduser().read_text()
    m = re.search(r"^Keystore password: (\S+)$", text, re.M)
    return m.group(1) if m else text.strip()


def version_code(ver: str) -> int:
    m = re.fullmatch(r"(\d+)\.(\d+)\.(\d+)", ver)
    if not m:
        die(f"version must look like 0.2.2, got {ver!r}")
    major, minor, patch = map(int, m.groups())
    if minor > 99 or patch > 99:
        die("minor and patch must be below 100 to keep versionCode increasing")
    return major * 10000 + minor * 100 + patch


def release_notes(tag: str) -> str:
    tags = git("tag", "--merged", "HEAD", "--sort=-v:refname", "--list", "v*").split()
    prev = next((t for t in tags if t != tag), None)
    span = f"{prev}..HEAD" if prev else "HEAD"
    subjects = git("log", "--no-merges", "--reverse", "--format=%s", span).splitlines()
    lines = [re.sub(r"^\w+(\([^)]*\))?!?:\s*", "", s) for s in subjects if s.strip()]
    notes = "\n".join(f"- {line}" for line in lines) or "- Maintenance release."
    return (f"## What's new\n\n{notes}\n\n"
            f"Sideload: download the APK and run `adb install -r livewire-{tag[1:]}.apk`.\n")


def build(ver: str, code: int, keystore: str, password: str) -> None:
    env = dict(os.environ)
    env.update({
        "KEYSTORE_FILE": keystore,
        "KEYSTORE_PASSWORD": password,
        "KEY_ALIAS": KEY_ALIAS,
        "KEY_PASSWORD": password,
    })
    log = DIST / "build.log"
    print(f"release: running tests, lint and the release build (log: {log})")
    with open(log, "w") as fh:
        rc = subprocess.run(
            [str(ROOT / "gradlew"), "--console=plain", f"-PversionName={ver}", f"-PversionCode={code}",
             "clean", ":app:testDebugUnitTest", ":app:lintDebug", ":app:assembleRelease"],
            cwd=ROOT, env=env, stdout=fh, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL).returncode
    if rc != 0:
        die(f"Gradle failed (exit {rc}); see {log}")


def verify(apk: Path, ver: str, code: int) -> None:
    sdk = os.environ.get("ANDROID_HOME") or die("set ANDROID_HOME")
    tools = Path(sdk) / "build-tools" / BUILD_TOOLS
    badging = run([str(tools / "aapt2"), "dump", "badging", str(apk)]).splitlines()[0]
    want = f"package: name='{PACKAGE}' versionCode='{code}' versionName='{ver}'"
    if not badging.startswith(want):
        die(f"APK reports {badging!r}, expected {want!r}")
    certs = run([str(tools / "apksigner"), "verify", "--print-certs", str(apk)], stderr=subprocess.DEVNULL)
    digests = re.findall(r"certificate SHA-256 digest: ([0-9a-f]{64})", certs)
    if digests != [CERT_SHA256]:
        die(f"APK is signed with {digests}, not the release certificate {CERT_SHA256}")


def package(ver: str, code: int) -> list[Path]:
    built = sorted(APK_DIR.glob("*.apk"))
    if len(built) != 1:
        die(f"expected one APK in {APK_DIR}, found {[p.name for p in built]}")
    apk = DIST / f"livewire-{ver}.apk"
    shutil.copyfile(built[0], apk)
    verify(apk, ver, code)
    digest = run(["sha256sum", str(apk)]).split()[0]
    sidecar = DIST / f"{apk.name}.sha256"
    sidecar.write_text(f"{digest}  {apk.name}\n")
    manifest = DIST / "update.json"
    manifest.write_text(json.dumps({
        "version": ver, "versionCode": code, "sha256": digest,
        "size": apk.stat().st_size, "minSdk": MIN_SDK}, indent=2) + "\n")
    print(f"release: {PACKAGE} {ver} ({code}), {apk.stat().st_size} bytes, sha256 {digest}")
    return [apk, sidecar, manifest]


def fj(*args: str) -> None:
    fj_bin = shutil.which("fj") or str(Path.home() / ".cargo" / "bin" / "fj")
    subprocess.run([fj_bin, "-H", HOST, *args], check=True, cwd=ROOT, stdin=subprocess.DEVNULL)


def publish(files: list[Path], tag: str, sha: str) -> None:
    name = f"LiveWire {tag}"
    attach = [a for f in files for a in ("--attach", str(f))]
    fj("release", "create", "-r", REPO, "--create-tag", tag, "--branch", sha,
       "--body", release_notes(tag), "--draft", *attach, name)
    fj("release", "edit", "-r", REPO, "--draft", "false", name)
    with urllib.request.urlopen(f"{API}/releases/tags/{tag}", timeout=60) as r:
        rel = json.load(r)
    names = sorted(a["name"] for a in rel.get("assets", []))
    want = sorted(f.name for f in files)
    if rel.get("draft") or names != want:
        die(f"release {tag} is not complete: draft={rel.get('draft')}, assets={names}, expected {want}")
    tagged = git("ls-remote", "origin", f"refs/tags/{tag}").split()[0]
    if tagged != sha:
        die(f"tag {tag} points at {tagged}, expected {sha}")
    print(f"release: published {rel['html_url']}")


def main() -> None:
    ap = argparse.ArgumentParser(description="Build and publish a LiveWire release.")
    ap.add_argument("version", help="release version without the v, for example 0.2.2")
    ap.add_argument("--dry-run", action="store_true", help="build and verify only; publish nothing")
    args = ap.parse_args()

    ver = args.version
    code = version_code(ver)
    tag = f"v{ver}"

    run(["git", "-C", str(ROOT), "fetch", "--quiet", "--tags", "origin"])
    if git("status", "--porcelain"):
        die("the worktree has uncommitted changes")
    sha = git("rev-parse", "HEAD")
    if sha != git("rev-parse", "origin/main"):
        if not args.dry_run:
            die("HEAD is not origin/main; check out main and pull first")
        print("release: note: HEAD is not origin/main, so only a dry run is possible")
    if git("ls-remote", "--tags", "origin", f"refs/tags/{tag}"):
        if not args.dry_run:
            die(f"tag {tag} already exists; pick the next version")
        print(f"release: note: {tag} already exists, so only a dry run is possible")

    keystore = os.environ.get("LIVEWIRE_KEYSTORE") or die("set LIVEWIRE_KEYSTORE")
    password = secret("LIVEWIRE_KEYSTORE_PASS") or die("set LIVEWIRE_KEYSTORE_PASS or LIVEWIRE_KEYSTORE_PASS_FILE")

    DIST.mkdir(exist_ok=True)
    build(ver, code, keystore, password)
    files = package(ver, code)

    if args.dry_run:
        print("release: dry run, nothing published")
        print(release_notes(tag))
        return
    publish(files, tag, sha)


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Build, sign, verify and publish a LiveWire release on git.namillis.me.

Releases normally run in CI: pushing a tag like v0.2.6 starts
.forgejo/workflows/release.yml, which runs this script on that tag. It also runs
by hand from the repo root, on a clean checkout of a commit that is on main:

    python3 scripts/release.py 0.2.6 --dry-run
    python3 scripts/release.py 0.2.6

Steps:
  1. Check that the worktree is clean and HEAD is on origin/main, that tag
     v<version> either doesn't exist yet or already points at HEAD, and that no
     release exists for it.
  2. Run unit tests, lint and the release build with versionName <version> and
     versionCode MAJOR*10000 + MINOR*100 + PATCH, signed with the release key.
  3. Check the APK: package com.livewire.tv, the expected version, and the
     release certificate below. A build signed with any other key is refused,
     because it could not update existing installs.
  4. Write dist/livewire-<version>.apk, its .sha256 sidecar and update.json.
  5. Create a draft Forgejo release for v<version> with the three files
     attached, then publish it. Publishing creates the tag at HEAD if it doesn't
     exist yet. --dry-run stops before this step.

Environment:
  LIVEWIRE_KEYSTORE        path to the PKCS12 release keystore (alias "livewire")
  LIVEWIRE_KEYSTORE_PASS   keystore password; or LIVEWIRE_KEYSTORE_PASS_FILE, a
                           file whose "Keystore password:" line holds it
  FORGEJO_TOKEN            git.namillis.me token with write access to the repo
                           (CI provides one); not needed for --dry-run
  ANDROID_HOME             Android SDK with build-tools 36.0.0
  JAVA_HOME                JDK 17 or later
"""

from __future__ import annotations

import argparse
import json
import os
import re
import secrets
import shutil
import subprocess
import sys
import urllib.error
import urllib.parse
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


def api(method: str, path: str, body: dict | None = None, data: bytes | None = None,
        content_type: str = "application/json") -> dict | None:
    """Call the repo's Forgejo API. A GET that finds nothing returns None; any other error exits."""
    token = os.environ.get("FORGEJO_TOKEN") or os.environ.get("GITHUB_TOKEN")
    if method != "GET" and not token:
        die("set FORGEJO_TOKEN to a git.namillis.me token with write access to the repo")
    headers = {"Accept": "application/json"}
    if token:
        headers["Authorization"] = f"token {token}"
    if body is not None:
        data = json.dumps(body).encode()
    if data is not None:
        headers["Content-Type"] = content_type
    req = urllib.request.Request(API + path, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=300) as r:
            raw = r.read()
    except urllib.error.HTTPError as e:
        if method == "GET" and e.code == 404:
            return None
        die(f"{method} {path} failed with HTTP {e.code}: {e.read()[:300].decode(errors='replace')}")
    return json.loads(raw) if raw else {}


def upload(release_id: int, path: Path) -> None:
    boundary = secrets.token_hex(16)
    head = (f'--{boundary}\r\nContent-Disposition: form-data; name="attachment"; filename="{path.name}"\r\n'
            "Content-Type: application/octet-stream\r\n\r\n").encode()
    data = head + path.read_bytes() + f"\r\n--{boundary}--\r\n".encode()
    api("POST", f"/releases/{release_id}/assets?name={urllib.parse.quote(path.name)}",
        data=data, content_type=f"multipart/form-data; boundary={boundary}")


def tag_commit(tag: str) -> str | None:
    """The commit tag points at on origin, or None if origin has no such tag."""
    if not git("ls-remote", "--tags", "origin", f"refs/tags/{tag}"):
        return None
    run(["git", "-C", str(ROOT), "fetch", "--quiet", "origin", f"+refs/tags/{tag}:refs/tags/{tag}"])
    return git("rev-parse", f"refs/tags/{tag}^{{commit}}")


def publish(files: list[Path], tag: str, sha: str) -> None:
    rel = api("POST", "/releases", body={
        "tag_name": tag, "target_commitish": sha, "name": f"LiveWire {tag}",
        "body": release_notes(tag), "draft": True, "prerelease": False})
    for f in files:
        upload(rel["id"], f)
    api("PATCH", f"/releases/{rel['id']}", body={"draft": False})
    rel = api("GET", f"/releases/tags/{tag}") or {}
    names = sorted(a["name"] for a in rel.get("assets", []))
    want = sorted(f.name for f in files)
    if rel.get("draft", True) or names != want:
        die(f"release {tag} is not complete: draft={rel.get('draft')}, assets={names}, expected {want}")
    tagged = tag_commit(tag)
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
    problems = []
    if subprocess.run(["git", "-C", str(ROOT), "merge-base", "--is-ancestor", sha, "origin/main"]).returncode:
        problems.append("HEAD is not on origin/main; release a merged commit")
    tagged = tag_commit(tag)
    if tagged and tagged != sha:
        problems.append(f"tag {tag} already exists on {tagged[:12]}, not HEAD; pick the next version")
    if api("GET", f"/releases/tags/{tag}"):
        problems.append(f"a release for {tag} already exists")
    for problem in problems:
        if not args.dry_run:
            die(problem)
        print(f"release: note: {problem}, so only a dry run is possible")

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

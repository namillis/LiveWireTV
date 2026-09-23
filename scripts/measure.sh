#!/usr/bin/env bash
# LiveWire — Phase 7 on-device lean measurement.
#
# Collects the numbers that validate the native rewrite (ADR-0001): RAM floor,
# playback RAM, cold-start, installed size. Run against a real Android TV / Fire TV
# on the same network. Record the results in docs/ADR-0001 (Phase 7 table).
#
# Usage:   scripts/measure.sh <TV-IP[:PORT]>
# Example: scripts/measure.sh 192.168.1.42
#
# Prereqs: adb on PATH; TV developer mode + network debugging enabled; the release
#          APK installed (build with: ./gradlew :app:assembleRelease, then sign+install,
#          or install the debug APK for a rough read).

set -euo pipefail
PKG="com.livewire.tv"
ACT="com.livewire.tv/.MainActivity"
TV="${1:?Usage: measure.sh <TV-IP[:PORT]>}"
[[ "$TV" == *:* ]] || TV="$TV:5555"

echo "== Connecting to $TV =="
adb connect "$TV" >/dev/null
adb -s "$TV" wait-for-device

section() { echo; echo "== $1 =="; }

section "Installed size (codeSize / dataSize / cacheSize, KB)"
adb -s "$TV" shell dumpsys package "$PKG" | grep -E "codeSize|dataSize|cacheSize" || echo "  (not reported on this Android build)"

section "Cold start -> first frame"
adb -s "$TV" shell am force-stop "$PKG"
sleep 1
# TotalTime is the cold-start-to-first-frame the OS reports.
adb -s "$TV" shell am start-activity -W -n "$ACT" | grep -E "ThisTime|TotalTime|WaitTime" || true

section "Idle RAM at Home (let it settle ~8s)"
sleep 8
adb -s "$TV" shell dumpsys meminfo "$PKG" | grep -E "TOTAL PSS|TOTAL RSS|TOTAL:" | head -3

echo
echo "== Playback RAM =="
echo "   Now, on the TV: open a channel and start playback, then press ENTER here."
read -r _
adb -s "$TV" shell dumpsys meminfo "$PKG" | grep -E "TOTAL PSS|TOTAL RSS|TOTAL:" | head -3

echo
echo "Done. Record these in docs/ADR-0001-native-kotlin-exoplayer.md (Phase 7 table),"
echo "alongside the same measurements from the Flutter build (package com.livewire.tv too,"
echo "so uninstall one before measuring the other)."

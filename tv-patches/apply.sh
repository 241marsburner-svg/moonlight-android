#!/usr/bin/env bash
# Artemide TV: applies the patches in tv-patches/ to the submodules before a build.
#
# moonlight-common-c is upstream moonlight-stream/moonlight-common-c at 874ac95 (newer than the
# Artemide base: nanors SIMD FEC decoding, gamepad input batched without a 1 ms wait, microsecond
# frame times, lock and leak fixes), plus the same five patches pabragin/moonlight-tcl ships on
# TCL TVs (its common-c "tcl" branch is exactly this tree):
#   0001 server commands over the control stream (Apollo/Artemis protocol extension)
#   0002 empty keep-alive payload (Apollo/Artemis protocol extension, keeps TV Wi-Fi awake)
#   0003 RFI after a multi-block frame loss points at the right frames
#   0004 a partially dropped IDR frame waits for a new IDR frame
#   0005 no speculative loss reports while RFI is off (one lost packet could drop 120 frames)
# 0003-0005 are upstream fixes from September 2026 (Cameron Gutman).
#
# Safe to run more than once: if the whole series is already in place nothing is changed.
set -euo pipefail
shopt -s nullglob

root="$(cd "$(dirname "$0")/.." && pwd)"
cc="$root/app/src/main/jni/moonlight-core/moonlight-common-c"

if [ ! -f "$cc/src/RtpVideoQueue.c" ] || [ ! -f "$cc/nanors/rs.c" ] || [ ! -f "$cc/enet/host.c" ]; then
  echo "moonlight-common-c (with enet and nanors) is missing: run 'git submodule update --init --recursive' first" >&2
  exit 1
fi

patches=( "$root"/tv-patches/moonlight-common-c/*.patch )
if [ ${#patches[@]} -eq 0 ]; then
  echo "no moonlight-common-c patches"
  exit 0
fi

# The patches touch neighbouring lines, so "already applied" is checked for the whole series:
# undo it on a scratch copy of src/, last patch first.
series_applied() {
  local tmp i
  tmp="$(mktemp -d)"
  cp -r "$cc/src" "$tmp/src"
  for (( i=${#patches[@]}-1; i>=0; i-- )); do
    if ! (cd "$tmp" && git apply --reverse "${patches[$i]}" 2>/dev/null); then
      rm -rf "$tmp"
      return 1
    fi
  done
  rm -rf "$tmp"
  return 0
}

if series_applied; then
  echo "already applied: ${#patches[@]} moonlight-common-c patches"
  exit 0
fi

for p in "${patches[@]}"; do
  git -C "$cc" apply "$p"
  echo "applied: $(basename "$p")"
done

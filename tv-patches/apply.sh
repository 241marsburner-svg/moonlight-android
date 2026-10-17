#!/usr/bin/env bash
# Artemide TV: applies the patches in tv-patches/ to the submodules before a build.
#
# moonlight-common-c: three frame-loss fixes from upstream moonlight-common-c (Cameron Gutman,
# September 2026), the same ones pabragin/moonlight-tcl ships on TCL TVs:
#   0001 RFI after a multi-block frame loss points at the right frames
#   0002 a partially dropped IDR frame waits for a new IDR frame
#   0003 no speculative loss reports while RFI is off (one lost packet could drop 120 frames)
#
# Safe to run more than once: a patch that is already in place is skipped.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
cc="$root/app/src/main/jni/moonlight-core/moonlight-common-c"

if [ ! -f "$cc/src/RtpVideoQueue.c" ]; then
  echo "moonlight-common-c is missing: run 'git submodule update --init --recursive' first" >&2
  exit 1
fi

for p in "$root"/tv-patches/moonlight-common-c/*.patch; do
  name="$(basename "$p")"
  if git -C "$cc" apply --reverse --check "$p" 2>/dev/null; then
    echo "already applied: $name"
  else
    git -C "$cc" apply "$p"
    echo "applied: $name"
  fi
done

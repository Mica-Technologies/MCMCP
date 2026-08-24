#!/usr/bin/env bash
#
# Stages the headless binary where tauri-bundler will pick it up as a sidecar.
#
# The desktop app cannot serve MCP over stdio — its own stdin and stdout belong to whatever launched
# it — so an MCP client spawns `mcmcp-orchestrator shim` instead, and the app's Settings panel hands
# out a config snippet pointing at that binary *next to itself*.
#
# Which means shipping the app without the shim produces an install that looks complete, offers a
# configuration, and points it at a file that is not there. That is precisely what the first
# installer build did, because nothing had ever run it: the MSI contained
# `mcmcp-orchestrator-app.exe` and nothing else.
#
# Tauri's `externalBin` wants the file suffixed with the target triple, and strips it again when
# placing the binary beside the app. This copies and renames.
#
# Usage:
#   stage-orchestrator-sidecar.sh [target-triple] [profile]

set -euo pipefail

TRIPLE="${1:-}"
PROFILE="${2:-release}"

if [ -z "$TRIPLE" ]; then
  # `rustc -vV` reports the host triple, which is what a build with no --target produces.
  TRIPLE="$(rustc -vV | awk '/^host: /{print $2}')"
fi

case "$TRIPLE" in
  *windows*) SUFFIX=".exe" ;;
  *)         SUFFIX="" ;;
esac

# A --target build nests under target/<triple>/; a plain one does not. Accept whichever exists so the
# script works the same locally and in CI.
for candidate in \
  "orchestrator/target/${TRIPLE}/${PROFILE}/mcmcp-orchestrator${SUFFIX}" \
  "orchestrator/target/${PROFILE}/mcmcp-orchestrator${SUFFIX}"
do
  if [ -f "$candidate" ]; then
    SOURCE="$candidate"
    break
  fi
done

if [ -z "${SOURCE:-}" ]; then
  echo "==> FAIL: no headless binary built for ${TRIPLE} (${PROFILE})."
  echo "    Build it first:  cargo build --${PROFILE} -p mcmcp-orchestrator"
  exit 1
fi

DESTINATION="orchestrator/app/binaries/mcmcp-orchestrator-${TRIPLE}${SUFFIX}"
mkdir -p "$(dirname "$DESTINATION")"
cp "$SOURCE" "$DESTINATION"

# The app's build script writes a placeholder here when no real binary has been staged, so that a
# plain `cargo test --workspace` can build the crate at all. That placeholder must never reach an
# installer — it would produce exactly the failure this staging step exists to prevent, except
# harder to spot, because the file would be present and simply not be a program.
if head -c 64 "$DESTINATION" | grep -q 'sidecar placeholder'; then
  echo "==> FAIL: ${DESTINATION} is the build script's placeholder, not the shim."
  echo "    The copy above should have replaced it. Check that ${SOURCE} is a real binary."
  exit 1
fi

echo "==> Staged the shim for bundling"
echo "    from ${SOURCE}"
echo "    to   ${DESTINATION}"
